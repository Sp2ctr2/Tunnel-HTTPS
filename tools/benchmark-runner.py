#!/usr/bin/env python3

import argparse
import json
import math
import os
import re
import shlex
import signal
import subprocess
import sys
import threading
import time
import uuid
from datetime import datetime, timezone
from ipaddress import ip_address
from pathlib import Path


PROJECT_DIR = Path(__file__).resolve().parent.parent
PROBE_DIR = PROJECT_DIR / "tools" / "device-probe"
DEFAULT_ADB = Path(os.environ.get(
    "DEVICE_PROBE_ADB",
    "/home/sp2ctr2/.cache/latch-toolchain/android-sdk/platform-tools/adb",
))
DEFAULT_SERIAL = os.environ.get("DEVICE_PROBE_SERIAL", "emulator-5554")
DEFAULT_APP_PACKAGE = "com.tunnelvpn.app.turbo"
HELPER_PACKAGE = "com.luna.deviceprobe"
LOCAL_HOSTS = {"localhost", "10.0.2.2", "fec0::2"}
SAMPLE_MARKER = "BENCH_SAMPLE_JSON "
BATCH_MARKER = "BENCH_BATCH_JSON "
DONE_MARKER = "BENCH_DONE runId="


def parse_args(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("kind", choices=("latency", "throughput", "concurrency"))
    parser.add_argument("--mode", required=True, choices=("direct", "current", "next"))
    parser.add_argument("--probe", choices=("http", "tcp4", "tcp6"), default="http")
    parser.add_argument("--host", default="10.0.2.2")
    parser.add_argument("--port", type=int, default=18080)
    parser.add_argument("--tcp-port", type=int, default=18081)
    parser.add_argument("--url")
    parser.add_argument("--public-example-com", action="store_true")
    parser.add_argument("--size", type=int)
    parser.add_argument("--sizes", default="1048576,10485760")
    parser.add_argument("--levels", default="1,8,32")
    parser.add_argument("--warmup-runs", type=int)
    parser.add_argument("--measured-runs", type=int)
    parser.add_argument("--timeout-ms", type=int, default=5_000)
    parser.add_argument("--duration-cap-ms", type=int, default=120_000)
    parser.add_argument("--repeat-delay-ms", type=int, default=0)
    parser.add_argument("--serial", default=DEFAULT_SERIAL)
    parser.add_argument("--adb", default=str(DEFAULT_ADB))
    parser.add_argument("--app-package", default=DEFAULT_APP_PACKAGE)
    parser.add_argument("--expected-production-sha256")
    parser.add_argument("--node", default="node")
    parser.add_argument("--output-dir")
    parser.add_argument("--host-deadline-ms", type=int)
    parser.add_argument("--no-resource-sampling", action="store_true")
    return parser.parse_args(argv)


def fail(message):
    raise ValueError(message)


def positive_int(value, name, maximum):
    if not isinstance(value, int) or value < 1 or value > maximum:
        fail(f"{name} must be between 1 and {maximum}")
    return value


def bounded_int(value, name, minimum, maximum):
    if not isinstance(value, int) or value < minimum or value > maximum:
        fail(f"{name} must be between {minimum} and {maximum}")
    return value


def parse_integer_list(text, name, minimum, maximum):
    values = []
    for token in text.split(","):
        if not token.strip():
            continue
        try:
            value = int(token.strip())
        except ValueError:
            fail(f"{name} contains a non-integer: {token}")
        values.append(bounded_int(value, name, minimum, maximum))
    if not values:
        fail(f"{name} must not be empty")
    return values


def local_host(value):
    host = value.strip().strip("[]").lower()
    if host in LOCAL_HOSTS or host.endswith(".local") or host.endswith(".test"):
        return host
    try:
        address = ip_address(host)
    except ValueError:
        fail(f"host is not an approved local fixture address: {value}")
    if address.is_private or address.is_loopback or address.is_link_local:
        return host
    fail(f"host is not an approved local fixture address: {value}")


def host_for_url(host):
    return f"[{host}]" if ":" in host and not host.startswith("[") else host


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def stamp():
    return datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")


def adb_command(adb, serial, *arguments):
    return [str(adb), "-s", serial, *[str(argument) for argument in arguments]]


def run_adb(adb, serial, arguments, timeout=5):
    try:
        result = subprocess.run(adb_command(adb, serial, *arguments), text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                timeout=timeout, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        return "", str(error), 1
    return result.stdout, result.stderr, result.returncode


def process_pids(adb, serial, package):
    stdout, _, returncode = run_adb(adb, serial, ("shell", "pidof", package), timeout=2)
    if returncode != 0:
        return []
    result = []
    for token in stdout.split():
        if token.isdigit():
            result.append(int(token))
    return result


def proc_status(adb, serial, pid):
    stdout, _, returncode = run_adb(adb, serial, ("shell", "cat", f"/proc/{pid}/status"), timeout=2)
    if returncode != 0:
        return {}
    result = {}
    for line in stdout.splitlines():
        if ":" not in line:
            continue
        key, value = line.split(":", 1)
        match = re.search(r"-?\d+", value)
        if match and key in {"VmRSS", "VmSize", "Threads"}:
            result[key] = int(match.group(0))
    return result


def proc_cpu_ticks(adb, serial, pid):
    stdout, _, returncode = run_adb(adb, serial, ("shell", "cat", f"/proc/{pid}/stat"), timeout=2)
    if returncode != 0 or ")" not in stdout:
        return None
    remainder = stdout.rsplit(")", 1)[1].split()
    if len(remainder) < 13:
        return None
    try:
        return int(remainder[11]) + int(remainder[12])
    except ValueError:
        return None


def proc_socket_count(adb, serial, pid):
    stdout, _, returncode = run_adb(adb, serial,
                                    ("shell", "ls", "-1", f"/proc/{pid}/fd"), timeout=2)
    if returncode != 0:
        return None
    return len([line for line in stdout.splitlines() if line.strip()])


def package_pss(adb, serial, package):
    stdout, _, returncode = run_adb(adb, serial, ("shell", "dumpsys", "meminfo", package), timeout=4)
    if returncode != 0:
        return None
    for line in stdout.splitlines():
        if "TOTAL PSS" not in line:
            continue
        match = re.search(r"TOTAL PSS:\s*([0-9]+)", line)
        if match:
            return int(match.group(1))
    return None


def installed_apk_provenance(adb, serial, package):
    stdout, stderr, returncode = run_adb(adb, serial, ("shell", "pm", "path", package), timeout=5)
    paths = []
    for line in stdout.splitlines():
        if line.startswith("package:"):
            paths.append(line[len("package:"):].strip())
    hashes = []
    for apk_path in paths:
        hash_stdout, hash_stderr, hash_returncode = run_adb(
            adb, serial, ("exec-out", "sha256sum", apk_path), timeout=10
        )
        digest = hash_stdout.split()[0] if hash_returncode == 0 and hash_stdout.split() else None
        hashes.append({"path": apk_path, "sha256": digest, "error": hash_stderr.strip(),
                       "returnCode": hash_returncode})
    return {
        "package": package,
        "pmPathReturnCode": returncode,
        "pmPathError": stderr.strip(),
        "paths": paths,
        "hashes": hashes,
        "available": bool(paths) and all(item["sha256"] for item in hashes),
    }


def cdp_diagnostics(node_command):
    expression = """(() => {
      const parse = name => {
        try { return JSON.parse(TunnelAndroid[name]()); } catch (error) {
          return { available: false, error: String(error) };
        }
      };
      return {
        savedConfig: parse('getSavedConfig'),
        turboState: parse('getTurboState'),
        currentState: parse('getCurrentStateSnapshot'),
        trafficStats: parse('getTrafficStats')
      };
    })()"""
    command = [node_command, str(PROJECT_DIR / "tools" / "webview_eval.mjs"), expression]
    try:
        result = subprocess.run(command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.PIPE, timeout=20, check=False)
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"available": False, "error": str(error), "command": shlex.join(command)}
    try:
        value = json.loads(result.stdout) if result.stdout.strip() else None
    except json.JSONDecodeError:
        value = None
    return {
        "available": result.returncode == 0 and isinstance(value, dict),
        "value": value,
        "stderr": result.stderr.strip(),
        "returnCode": result.returncode,
        "command": shlex.join(command),
    }


def network_observation(lines):
    capabilities = []
    links = []
    active = []
    for line in lines:
        for prefix, target in (("ACTIVE_NETWORK ", active),
                               ("ACTIVE_CAPABILITIES ", capabilities),
                               ("ACTIVE_LINKS ", links)):
            index = line.find(prefix)
            if index >= 0:
                target.append(line[index + len(prefix):])
    combined = " ".join(capabilities + links)
    mtu_matches = re.findall(r"(?i)\bMTU:\s*([0-9]+)", combined)
    vpn_present = bool(re.search(r"(?i)\bVPN\b|tun[0-9]+|10\.111\.|fd00:111:", combined))
    return {
        "activeNetwork": active[-1] if active else None,
        "activeCapabilities": capabilities[-1] if capabilities else None,
        "activeLinks": links[-1] if links else None,
        "mtu": int(mtu_matches[-1]) if mtu_matches else None,
        "vpnPresent": vpn_present,
        "observationComplete": bool(capabilities and links),
    }


class ResourceSampler(threading.Thread):
    def __init__(self, adb, serial, package, interval=0.5):
        super().__init__(name="benchmark-resource-sampler", daemon=True)
        self.adb = adb
        self.serial = serial
        self.package = package
        self.interval = interval
        self.finished = threading.Event()
        self.observations = []
        self.ticks_per_second = os.sysconf(os.sysconf_names["SC_CLK_TCK"])

    def snapshot(self):
        timestamp = time.time()
        pids = process_pids(self.adb, self.serial, self.package)
        if not pids:
            return {"timestampEpochMs": int(timestamp * 1000), "available": False}
        pid = pids[0]
        status = proc_status(self.adb, self.serial, pid)
        ticks = proc_cpu_ticks(self.adb, self.serial, pid)
        observation = {
            "timestampEpochMs": int(timestamp * 1000),
            "available": True,
            "package": self.package,
            "pid": pid,
            "rssKb": status.get("VmRSS"),
            "vmSizeKb": status.get("VmSize"),
            "threads": status.get("Threads"),
            "socketCount": proc_socket_count(self.adb, self.serial, pid),
            "pssKb": package_pss(self.adb, self.serial, self.package),
        }
        if ticks is not None and self.observations:
            previous = self.observations[-1]
            previous_ticks = previous.get("cpuTicks")
            previous_timestamp = previous.get("timestampEpochMs")
            if previous_ticks is not None and previous_timestamp is not None:
                elapsed = (observation["timestampEpochMs"] - previous_timestamp) / 1000.0
                if elapsed > 0:
                    observation["cpuPercent"] = round(
                        (ticks - previous_ticks) / self.ticks_per_second / elapsed * 100.0, 3
                    )
        observation["cpuTicks"] = ticks
        return observation

    def run(self):
        while not self.finished.is_set():
            try:
                self.observations.append(self.snapshot())
            except Exception as error:
                self.observations.append({"timestampEpochMs": int(time.time() * 1000),
                                          "available": False, "error": str(error)})
            self.finished.wait(self.interval)

    def stop(self):
        self.finished.set()

    def summary(self):
        available = [item for item in self.observations if item.get("available")]
        cpu = [item["cpuPercent"] for item in available if isinstance(item.get("cpuPercent"), (int, float))]
        rss = [item["rssKb"] for item in available if isinstance(item.get("rssKb"), int)]
        pss = [item["pssKb"] for item in available if isinstance(item.get("pssKb"), int)]
        threads = [item["threads"] for item in available if isinstance(item.get("threads"), int)]
        sockets = [item["socketCount"] for item in available if isinstance(item.get("socketCount"), int)]
        return {
            "package": self.package,
            "available": bool(available),
            "sampleCount": len(self.observations),
            "availableSampleCount": len(available),
            "cpuPercentAverage": round(sum(cpu) / len(cpu), 3) if cpu else None,
            "cpuPercentPeak": round(max(cpu), 3) if cpu else None,
            "rssKbPeak": max(rss) if rss else None,
            "pssKbPeak": max(pss) if pss else None,
            "threadCountPeak": max(threads) if threads else None,
            "socketCountPeak": max(sockets) if sockets else None,
            "unavailableFields": ["flowCount", "packetQueueDepth"],
            "observations": self.observations,
        }


def terminate_process(process):
    if process is None or process.poll() is not None:
        return
    try:
        process.send_signal(signal.SIGTERM)
        process.wait(timeout=2)
    except (OSError, subprocess.TimeoutExpired):
        try:
            process.kill()
            process.wait(timeout=2)
        except (OSError, subprocess.TimeoutExpired):
            pass


def parse_sample(line):
    marker_index = line.find(SAMPLE_MARKER)
    if marker_index < 0:
        return None
    try:
        value = json.loads(line[marker_index + len(SAMPLE_MARKER):].strip())
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, dict) and value.get("record") == "sample" else None


def parse_batch(line):
    marker_index = line.find(BATCH_MARKER)
    if marker_index < 0:
        return None
    try:
        value = json.loads(line[marker_index + len(BATCH_MARKER):].strip())
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, dict) and value.get("record") == "batch" else None


def run_case(options, run_dir, scenario, size_bytes, concurrency, warmup_runs, measured_runs):
    case_id = f"{options.mode}-{scenario}-{options.probe}-{size_bytes}-{concurrency}-{uuid.uuid4().hex[:10]}"
    raw_dir = run_dir / "raw"
    case_dir = run_dir / "cases" / case_id
    raw_dir.mkdir(parents=True, exist_ok=True)
    case_dir.mkdir(parents=True, exist_ok=True)
    run_id = f"bench-{case_id}"
    host = local_host(options.host) if not options.public_example_com else "example.com"
    if options.probe == "http":
        if options.public_example_com:
            url = options.url or "https://example.com/"
            if url != "https://example.com/":
                fail("public benchmark is restricted to https://example.com/")
            probe_args = [("--es", "http-url", url), ("--ez", "allow-public", "true")]
        else:
            url = options.url or f"http://{host_for_url(host)}:{options.port}/bytes?size={{size}}"
            if not url.startswith("http://"):
                fail("benchmark HTTP URL must use local http:// fixture traffic")
            probe_args = [("--es", "http-url", url)]
    else:
        family = "4" if options.probe == "tcp4" else "6"
        probe_args = [("--es", "tcp-host", host), ("--ei", "tcp-port", options.tcp_port),
                      ("--es", "tcp-family", family)]
    helper_args = [
        "--es", "run-id", run_id,
        "--es", "probe", options.probe,
        "--el", "fixture-size", size_bytes,
        "--ei", "warmup-runs", warmup_runs,
        "--ei", "measured-runs", measured_runs,
        "--ei", "concurrency", concurrency,
        "--ei", "timeout-ms", options.timeout_ms,
        "--ei", "duration-cap-ms", options.duration_cap_ms,
        "--ei", "repeat-delay-ms", options.repeat_delay_ms,
        "--ei", "max-body-bytes", max(size_bytes + 64 * 1024, 1),
        *sum(([flag, str(key), str(value)] for flag, key, value in probe_args), []),
    ]
    launch_command = [str(PROBE_DIR / "run.sh"), "launch", *[str(value) for value in helper_args]]
    helper_log_path = raw_dir / f"{case_id}-helper.log"
    helper_jsonl_path = raw_dir / f"{case_id}-helper.jsonl"
    helper_batch_jsonl_path = raw_dir / f"{case_id}-helper-batches.jsonl"
    helper_csv_path = raw_dir / f"{case_id}-helper.csv"
    records_path = raw_dir / f"{case_id}-records.jsonl"
    started_at = None
    resource_sampler = None
    diagnostics = cdp_diagnostics(options.node)
    production_apk = installed_apk_provenance(options.adb, options.serial,
                                               options.app_package)
    helper_apk = installed_apk_provenance(options.adb, options.serial, HELPER_PACKAGE)
    if not options.no_resource_sampling:
        resource_sampler = ResourceSampler(options.adb, options.serial, options.app_package)
        resource_sampler.start()
    logcat_start = datetime.fromtimestamp(time.time() - 1).strftime("%m-%d %H:%M:%S.%f")[:-3]
    logcat_arguments = ("logcat", "-d", "-v", "threadtime", "-T", logcat_start,
                        "-s", "LunaDeviceProbe:I", "*:S")
    launch = None
    lines = []
    csv_lines = []
    samples = []
    batches = []
    seen_start = False
    seen_log_lines = set()
    done_status = None
    deadline_ms = options.host_deadline_ms or max(
        60_000, (warmup_runs + measured_runs + 1) * options.duration_cap_ms + 30_000
    )
    started_at = utc_now()
    start = time.monotonic()
    try:
        launch = subprocess.run(launch_command, text=True, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=max(30, deadline_ms / 1000.0),
                                check=False)
        while time.monotonic() - start < deadline_ms / 1000.0:
            logcat_stdout, logcat_stderr, logcat_returncode = run_adb(
                options.adb, options.serial, logcat_arguments, timeout=10
            )
            if logcat_returncode != 0:
                lines.append("ADB_LOGCAT_ERROR returnCode=" + str(logcat_returncode)
                             + " error=" + logcat_stderr.strip())
            for stripped in logcat_stdout.splitlines():
                if stripped in seen_log_lines:
                    continue
                seen_log_lines.add(stripped)
                if not seen_start:
                    if run_id not in stripped:
                        continue
                    seen_start = True
                lines.append(stripped)
                csv_header_index = stripped.find("BENCH_CSV_HEADER ")
                if csv_header_index >= 0:
                    header = stripped[csv_header_index + len("BENCH_CSV_HEADER "):]
                    schema_index = header.find(" schema,")
                    csv_lines.append(header[schema_index + 1:] if schema_index >= 0 else header)
                csv_index = stripped.find("BENCH_SAMPLE_CSV ")
                if csv_index >= 0:
                    csv_lines.append(stripped[csv_index + len("BENCH_SAMPLE_CSV "):])
                sample = parse_sample(stripped)
                if sample is not None:
                    sample["mode"] = options.mode
                    sample["scenario"] = scenario
                    sample["sizeBytes"] = size_bytes
                    sample["caseId"] = case_id
                    samples.append(sample)
                batch = parse_batch(stripped)
                if batch is not None:
                    batch["mode"] = options.mode
                    batch["scenario"] = scenario
                    batch["sizeBytes"] = size_bytes
                    batch["caseId"] = case_id
                    batch["attemptedBytes"] = size_bytes * concurrency if size_bytes >= 0 else -1
                    completed_bytes = size_bytes * batch.get("passed", 0) if size_bytes >= 0 else -1
                    batch["completedBytes"] = completed_bytes
                    if completed_bytes > 0 and batch.get("durationMs", 0) > 0:
                        batch["aggregateBytes"] = completed_bytes
                        batch["aggregateThroughputMbps"] = round(
                            batch["aggregateBytes"] * 8.0
                            / (batch["durationMs"] / 1000.0) / 1_000_000.0, 6
                        )
                    else:
                        batch["aggregateBytes"] = None
                        batch["aggregateThroughputMbps"] = None
                        batch["aggregateUnavailableReason"] = "no successfully completed payload"
                    batches.append(batch)
                done_index = stripped.find(DONE_MARKER)
                if done_index >= 0:
                    done_text = stripped[done_index + len(DONE_MARKER):]
                    done_fields = dict(field.split("=", 1) for field in done_text.split()
                                       if "=" in field)
                    done_status = done_fields.get("status", "unknown")
                    break
            if done_status is not None:
                break
            time.sleep(0.25)
        if done_status is None:
            done_status = "host-timeout"
    except subprocess.TimeoutExpired:
        done_status = "host-timeout"
    finally:
        run_adb(options.adb, options.serial, ("shell", "am", "force-stop", HELPER_PACKAGE), timeout=5)
        if resource_sampler:
            resource_sampler.stop()
            resource_sampler.join(timeout=3)
    diagnostics_after = cdp_diagnostics(options.node)
    helper_log_path.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    helper_csv_path.write_text("\n".join(csv_lines) + ("\n" if csv_lines else ""), encoding="utf-8")
    with helper_jsonl_path.open("w", encoding="utf-8") as stream:
        for sample in samples:
            raw_sample = dict(sample)
            for key in ("mode", "scenario", "sizeBytes", "caseId"):
                raw_sample.pop(key, None)
            stream.write(json.dumps(raw_sample, sort_keys=True) + "\n")
    with helper_batch_jsonl_path.open("w", encoding="utf-8") as stream:
        for batch in batches:
            raw_batch = dict(batch)
            for key in ("mode", "scenario", "sizeBytes", "caseId", "attemptedBytes",
                        "completedBytes", "aggregateBytes", "aggregateThroughputMbps",
                        "aggregateUnavailableReason"):
                raw_batch.pop(key, None)
            stream.write(json.dumps(raw_batch, sort_keys=True) + "\n")
    with records_path.open("w", encoding="utf-8") as stream:
        for sample in samples:
            stream.write(json.dumps(sample, sort_keys=True) + "\n")
        for batch in batches:
            stream.write(json.dumps(batch, sort_keys=True) + "\n")
    observed_network = network_observation(lines)
    expected_vpn = options.mode != "direct"
    mode_matches = (
        observed_network["observationComplete"]
        and observed_network["vpnPresent"] == expected_vpn
    )
    expected_hash_matches = True
    if options.expected_production_sha256:
        installed_hashes = [item["sha256"] for item in production_apk["hashes"]]
        expected_hash_matches = options.expected_production_sha256.lower() in installed_hashes
    provenance_status = "pass"
    provenance_failures = []
    if not mode_matches:
        provenance_status = "fail"
        provenance_failures.append("mode-mismatch-or-missing-active-network-observation")
    if not production_apk["available"]:
        provenance_status = "fail"
        provenance_failures.append("production-apk-hash-unavailable")
    if not helper_apk["available"]:
        provenance_status = "fail"
        provenance_failures.append("helper-apk-hash-unavailable")
    if not diagnostics.get("available"):
        provenance_status = "fail"
        provenance_failures.append("cdp-production-diagnostics-unavailable")
    if not expected_hash_matches:
        provenance_status = "fail"
        provenance_failures.append("installed-production-apk-hash-mismatch")
    resources = resource_sampler.summary() if resource_sampler else {
        "available": False,
        "sampleCount": 0,
        "unavailableFields": ["resource sampling disabled"],
    }
    write_json(case_dir / "resources.json", resources)
    metadata = {
        "schema": 1,
        "caseId": case_id,
        "runId": run_id,
        "mode": options.mode,
        "scenario": scenario,
        "probe": options.probe,
        "sizeBytes": size_bytes,
        "concurrency": concurrency,
        "warmupRuns": warmup_runs,
        "measuredRuns": measured_runs,
        "timeoutMs": options.timeout_ms,
        "durationCapMs": options.duration_cap_ms,
        "startedAt": started_at,
        "completedAt": utc_now(),
        "status": "pass" if launch and launch.returncode == 0 and done_status == "pass"
        and provenance_status == "pass" else "fail",
        "doneStatus": done_status,
        "launchReturnCode": launch.returncode if launch else None,
        "launchOutput": launch.stdout if launch else "",
        "command": shlex.join(launch_command),
        "rawLog": str(helper_log_path),
        "rawSamples": str(helper_jsonl_path),
        "rawBatches": str(helper_batch_jsonl_path),
        "rawCsv": str(helper_csv_path),
        "records": str(records_path),
        "sampleCount": len(samples),
        "batchCount": len(batches),
        "resources": str(case_dir / "resources.json"),
        "provenanceStatus": provenance_status,
        "provenanceFailures": provenance_failures,
        "expectedMode": options.mode,
        "expectedVpnPresent": expected_vpn,
        "observedNetwork": observed_network,
        "productionApk": production_apk,
        "helperApk": helper_apk,
        "expectedProductionSha256": options.expected_production_sha256,
        "productionDiagnostics": diagnostics,
        "productionDiagnosticsAfter": diagnostics_after,
    }
    write_json(case_dir / "case.json", metadata)
    return metadata


def environment(options, run_dir):
    devices, devices_error, devices_rc = run_adb(options.adb, options.serial, ("devices", "-l"), timeout=5)
    props = {}
    for key in ("ro.build.version.sdk", "ro.build.version.release", "ro.product.cpu.abi",
                "ro.kernel.qemu", "ro.boot.qemu.avd_name", "ro.product.model",
                "ro.hardware", "ro.boot.hardware"):
        stdout, _, returncode = run_adb(options.adb, options.serial, ("shell", "getprop", key), timeout=3)
        props[key] = stdout.strip() if returncode == 0 else None
    nproc, _, nproc_returncode = run_adb(options.adb, options.serial,
                                         ("shell", "nproc"), timeout=3)
    wm_size, _, wm_size_returncode = run_adb(options.adb, options.serial,
                                             ("shell", "wm", "size"), timeout=3)
    wm_density, _, wm_density_returncode = run_adb(options.adb, options.serial,
                                                   ("shell", "wm", "density"), timeout=3)
    value = {
        "schema": 1,
        "recordedAt": utc_now(),
        "mode": options.mode,
        "serial": options.serial,
        "adb": str(options.adb),
        "deviceListing": devices,
        "deviceListingError": devices_error,
        "deviceListingReturnCode": devices_rc,
        "properties": props,
        "emulatorHardware": {
            "nproc": nproc.strip() if nproc_returncode == 0 else None,
            "wmSize": wm_size.strip() if wm_size_returncode == 0 else None,
            "wmDensity": wm_density.strip() if wm_density_returncode == 0 else None,
        },
        "host": {"python": sys.version, "cpuCount": os.cpu_count()},
        "fixture": {"host": options.host, "httpPort": options.port,
                     "tcpPort": options.tcp_port, "privateUserTraffic": False},
    }
    write_json(run_dir / "environment.json", value)
    return value


def requested_cases(options):
    if options.kind == "latency":
        size = options.size if options.size is not None else (-1 if options.public_example_com else 4_096)
        warmup = options.warmup_runs if options.warmup_runs is not None else 10
        measured = options.measured_runs if options.measured_runs is not None else 30
        return [("latency", size, 1, warmup, measured)]
    if options.kind == "throughput":
        sizes = [options.size] if options.size is not None else parse_integer_list(
            options.sizes, "sizes", 1, 100 * 1024 * 1024
        )
        warmup = options.warmup_runs if options.warmup_runs is not None else 0
        measured = options.measured_runs if options.measured_runs is not None else 5
        return [("throughput", size, 1, warmup, measured) for size in sizes]
    levels = parse_integer_list(options.levels, "levels", 1, 32)
    payload_size = options.size if options.size is not None else 1 * 1024 * 1024
    bounded_int(payload_size, "size", 1, 100 * 1024 * 1024)
    warmup = options.warmup_runs if options.warmup_runs is not None else 1
    measured = options.measured_runs if options.measured_runs is not None else 5
    return [("concurrency", payload_size, level, warmup, measured) for level in levels]


def main(argv):
    options = parse_args(argv)
    options.adb = Path(options.adb).resolve()
    bounded_int(options.timeout_ms, "timeout-ms", 250, 30_000)
    bounded_int(options.duration_cap_ms, "duration-cap-ms", 250, 120_000)
    bounded_int(options.repeat_delay_ms, "repeat-delay-ms", 0, 5_000)
    positive_int(options.tcp_port, "tcp-port", 65_535)
    positive_int(options.port, "port", 65_535)
    if not options.public_example_com:
        local_host(options.host)
    elif options.kind != "latency" or options.probe != "http":
        fail("public benchmark is limited to latency on the HTTP probe")
    if options.kind == "latency":
        bounded_int(options.warmup_runs if options.warmup_runs is not None else 10,
                    "warmup-runs", 0, 1_000)
        bounded_int(options.measured_runs if options.measured_runs is not None else 30,
                    "measured-runs", 1, 100)
    else:
        bounded_int(options.warmup_runs if options.warmup_runs is not None else 0,
                    "warmup-runs", 0, 1_000)
        bounded_int(options.measured_runs if options.measured_runs is not None else 5,
                    "measured-runs", 1, 100)
    if options.output_dir:
        run_dir = Path(options.output_dir).resolve()
    else:
        run_dir = PROJECT_DIR / "benchmark-results" / f"{stamp()}-{options.mode}-{options.kind}"
    if run_dir.name.startswith("baseline-") or "baseline-20260905-1240" in str(run_dir):
        fail("refusing to write the preserved parent baseline archive")
    run_dir.mkdir(parents=True, exist_ok=True)
    environment(options, run_dir)
    cases = []
    for scenario, size, concurrency, warmup, measured in requested_cases(options):
        cases.append(run_case(options, run_dir, scenario, size, concurrency, warmup, measured))
    summary_command = [sys.executable, str(PROJECT_DIR / "tools" / "benchmark-summarize.py"),
                       "--input", str(run_dir)]
    summary = subprocess.run(summary_command, text=True, stdout=subprocess.PIPE,
                             stderr=subprocess.STDOUT, check=False)
    (run_dir / "summarizer-output.txt").write_text(summary.stdout, encoding="utf-8")
    result = {
        "schema": 1,
        "recordedAt": utc_now(),
        "mode": options.mode,
        "kind": options.kind,
        "runDirectory": str(run_dir),
        "environment": str(run_dir / "environment.json"),
        "cases": cases,
        "summaryReturnCode": summary.returncode,
        "status": "pass" if cases and all(item["status"] == "pass" for item in cases)
        and summary.returncode == 0 else "fail",
    }
    write_json(run_dir / "run.json", result)
    print(json.dumps(result, indent=2, sort_keys=True))
    return 0 if result["status"] == "pass" else 1


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except (ValueError, OSError) as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2)

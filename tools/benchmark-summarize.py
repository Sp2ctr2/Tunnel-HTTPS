#!/usr/bin/env python3

import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path


def parse_args(argv):
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", required=True)
    parser.add_argument("--output-json")
    parser.add_argument("--output-md")
    return parser.parse_args(argv)


def source_paths(value):
    path = Path(value).resolve()
    if path.is_file():
        return [path]
    if not path.is_dir():
        raise ValueError(f"input does not exist: {path}")
    paths = []
    for candidate in sorted(path.rglob("*.jsonl")):
        if candidate.name.endswith("-records.jsonl"):
            paths.append(candidate)
    if not paths:
        paths = sorted(path.rglob("*.jsonl"))
    return paths


def load_records(paths):
    records = []
    errors = []
    for path in paths:
        try:
            with path.open("r", encoding="utf-8") as stream:
                for line_number, line in enumerate(stream, 1):
                    text = line.strip()
                    if not text:
                        continue
                    try:
                        record = json.loads(text)
                    except json.JSONDecodeError as error:
                        errors.append(f"{path}:{line_number}: {error.msg}")
                        continue
                    if isinstance(record, dict) and record.get("record") in {"sample", "batch"}:
                        record["_source"] = str(path)
                        records.append(record)
        except OSError as error:
            errors.append(f"{path}: {error}")
    return records, errors


def numeric(value):
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value)


def quantile(values, probability):
    ordered = sorted(values)
    if not ordered:
        return None
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * probability
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    weight = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * weight


def rounded(value):
    if value is None:
        return None
    return round(value, 3)


def group_key(record):
    return (
        record.get("mode", "unlabelled"),
        record.get("scenario", "unlabelled"),
        record.get("probe", "unknown"),
        record.get("sizeBytes", record.get("requestedBytes", -1)),
        record.get("concurrency", 1),
        record.get("phase", "measured"),
    )


def summarize(records):
    groups = {}
    batch_groups = {}
    for record in records:
        if record.get("phase") != "measured":
            continue
        key = group_key(record)
        if record.get("record") == "batch":
            batch_groups.setdefault(key, []).append(record)
        elif record.get("record") == "sample":
            groups.setdefault(key, []).append(record)
    summaries = []
    for key in sorted(groups, key=lambda value: tuple(str(item) for item in value)):
        mode, scenario, probe, size_bytes, concurrency, phase = key
        group_records = groups[key]
        passed = [record for record in group_records if record.get("status") == "pass"]
        durations = [float(record["durationMs"]) for record in passed
                     if numeric(record.get("durationMs")) and float(record["durationMs"]) >= 0]
        useful_payload_rate = []
        for record in passed:
            duration = record.get("durationMs")
            size = record.get("sizeBytes", record.get("requestedBytes", -1))
            if numeric(duration) and numeric(size) and duration > 0 and size >= 0:
                useful_payload_rate.append(size * 8.0 / (duration / 1000.0) / 1_000_000.0)
        summary = {
            "mode": mode,
            "scenario": scenario,
            "probe": probe,
            "sizeBytes": size_bytes,
            "concurrency": concurrency,
            "phase": phase,
            "sampleCount": len(group_records),
            "passCount": len(passed),
            "failedCount": len(group_records) - len(passed),
            "durationSampleCount": len(durations),
            "latencyMs": {
                "p50": rounded(quantile(durations, 0.50)),
                "p95": rounded(quantile(durations, 0.95)),
            },
            "rawSources": sorted({record["_source"] for record in group_records}),
        }
        if len(durations) >= 100:
            summary["latencyMs"]["p99"] = rounded(quantile(durations, 0.99))
        if useful_payload_rate:
            summary["perFlowUsefulPayloadMbps"] = {
                "p50": rounded(quantile(useful_payload_rate, 0.50)),
                "p95": rounded(quantile(useful_payload_rate, 0.95)),
                "min": rounded(min(useful_payload_rate)),
                "max": rounded(max(useful_payload_rate)),
                "sampleCount": len(useful_payload_rate),
            }
        batches = batch_groups.get(key, [])
        aggregate_rates = []
        for record in batches:
            completed = record.get("completedBytes")
            duration = record.get("durationMs")
            if numeric(completed) and numeric(duration) and completed > 0 and duration > 0:
                aggregate_rates.append(completed * 8.0 / (duration / 1000.0) / 1_000_000.0)
        if batches:
            summary["batchAccounting"] = {
                "sampleCount": len(batches),
                "failedBatchCount": sum(1 for record in batches if record.get("failed", 0) > 0),
                "attemptedBytes": sum(record.get("attemptedBytes", 0) for record in batches
                                       if numeric(record.get("attemptedBytes"))),
                "completedBytes": sum(record.get("completedBytes", 0) for record in batches
                                       if numeric(record.get("completedBytes"))),
            }
        if aggregate_rates:
            summary["batchAggregateThroughputMbps"] = {
                "p50": rounded(quantile(aggregate_rates, 0.50)),
                "p95": rounded(quantile(aggregate_rates, 0.95)),
                "min": rounded(min(aggregate_rates)),
                "max": rounded(max(aggregate_rates)),
                "sampleCount": len(aggregate_rates),
                "definition": "successfully completed payload bytes divided by batch wall time",
            }
        summaries.append(summary)
    return summaries


def format_size(value):
    if not numeric(value) or value < 0:
        return "-"
    if value % (1024 * 1024) == 0:
        return f"{int(value // (1024 * 1024))} MiB"
    if value % 1024 == 0:
        return f"{int(value // 1024)} KiB"
    return str(int(value))


def format_number(value):
    return "-" if value is None else str(value)


def markdown(result):
    lines = [
        "# Benchmark summary",
        "",
        f"Generated: `{result['generatedAt']}`",
        "",
        "Latency percentiles are calculated from passing measured samples only; failed samples remain disclosed in the counts. Per-flow useful payload rate is payload bytes divided by that flow's elapsed time. Batch aggregate throughput is reported separately as successfully completed payload bytes divided by batch wall time. Raw samples remain in the listed JSONL sources. p99 is omitted unless at least 100 passing measured samples exist.",
        "",
        "| Mode | Scenario | Probe | Size | Concurrency | Samples | Pass | Fail | p50 ms | p95 ms | p99 ms | Useful payload p50 Mbps | Useful range Mbps | Batch aggregate p50 Mbps | Batch range Mbps |",
        "|---|---|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for group in result["groups"]:
        latency = group["latencyMs"]
        useful = group.get("perFlowUsefulPayloadMbps", {})
        aggregate = group.get("batchAggregateThroughputMbps", {})
        useful_range = "-"
        aggregate_range = "-"
        if "min" in useful:
            useful_range = f"{useful['min']}–{useful['max']}"
        if "min" in aggregate:
            aggregate_range = f"{aggregate['min']}–{aggregate['max']}"
        lines.append(
            "| {mode} | {scenario} | {probe} | {size} | {concurrency} | {samples} | {passed} | {failed} | {p50} | {p95} | {p99} | {useful} | {useful_range} | {aggregate} | {aggregate_range} |".format(
                mode=group["mode"],
                scenario=group["scenario"],
                probe=group["probe"],
                size=format_size(group["sizeBytes"]),
                concurrency=group["concurrency"],
                samples=group["sampleCount"],
                passed=group["passCount"],
                failed=group["failedCount"],
                p50=format_number(latency.get("p50")),
                p95=format_number(latency.get("p95")),
                p99=format_number(latency.get("p99")),
                useful=format_number(useful.get("p50")),
                useful_range=useful_range,
                aggregate=format_number(aggregate.get("p50")),
                aggregate_range=aggregate_range,
            )
        )
    if not result["groups"]:
        lines.extend(["| - | - | - | - | - | 0 | 0 | 0 | - | - | - | - | - | - | - |"])
    lines.extend(["", "Source JSONL files:"])
    for source in result["sourceFiles"]:
        lines.append(f"- `{source}`")
    if result["parseErrors"]:
        lines.extend(["", "Parse errors:"])
        lines.extend(f"- `{error}`" for error in result["parseErrors"])
    return "\n".join(lines) + "\n"


def main(argv):
    args = parse_args(argv)
    paths = source_paths(args.input)
    records, parse_errors = load_records(paths)
    result = {
        "schema": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "sourceFiles": [str(path) for path in paths],
        "rawRecordCount": len(records),
        "parseErrors": parse_errors,
        "groups": summarize(records),
    }
    input_path = Path(args.input).resolve()
    output_json = Path(args.output_json).resolve() if args.output_json else (
        input_path / "summary.json" if input_path.is_dir() else input_path.with_name("summary.json")
    )
    output_md = Path(args.output_md).resolve() if args.output_md else (
        input_path / "summary.md" if input_path.is_dir() else input_path.with_name("summary.md")
    )
    output_json.parent.mkdir(parents=True, exist_ok=True)
    output_md.parent.mkdir(parents=True, exist_ok=True)
    output_json.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    output_md.write_text(markdown(result), encoding="utf-8")
    print(json.dumps({"summaryJson": str(output_json), "summaryMarkdown": str(output_md),
                      "rawRecordCount": len(records), "groupCount": len(result["groups"])},
                     sort_keys=True))
    return 0 if not parse_errors else 2


if __name__ == "__main__":
    try:
        raise SystemExit(main(sys.argv[1:]))
    except ValueError as error:
        print(str(error), file=sys.stderr)
        raise SystemExit(2)

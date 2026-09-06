import importlib.util
import json
import tempfile
import unittest
from pathlib import Path


TOOLS_DIR = Path(__file__).resolve().parents[1]


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


runner = load_module("benchmark_runner", TOOLS_DIR / "benchmark-runner.py")
summarizer = load_module("benchmark_summarize", TOOLS_DIR / "benchmark-summarize.py")


class BenchmarkToolTests(unittest.TestCase):
    def test_network_observation_uses_interface_mtu(self):
        lines = [
            "ACTIVE_CAPABILITIES runId=x value=[ Transports: WIFI|VPN ]",
            "ACTIVE_LINKS runId=x value={InterfaceName: tun0 LinkAddresses: [ 10.111.0.2/32 ] MTU: 32768 Routes: [ 0.0.0.0/0 -> 0.0.0.0 tun0 mtu 0 ]}",
        ]
        observed = runner.network_observation(lines)
        self.assertEqual(observed["mtu"], 32768)
        self.assertTrue(observed["vpnPresent"])
        self.assertTrue(observed["observationComplete"])

    def test_summarizer_loads_samples_and_batches(self):
        sample = {
            "record": "sample",
            "mode": "current",
            "scenario": "throughput",
            "probe": "http",
            "sizeBytes": 1048576,
            "concurrency": 1,
            "phase": "measured",
            "status": "pass",
            "durationMs": 100,
        }
        batch = {
            "record": "batch",
            "mode": "current",
            "scenario": "throughput",
            "probe": "http",
            "sizeBytes": 1048576,
            "concurrency": 1,
            "phase": "measured",
            "failed": 1,
            "attemptedBytes": 1048576,
            "completedBytes": 524288,
            "durationMs": 100,
        }
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "records.jsonl"
            path.write_text(
                json.dumps(sample) + "\n" + json.dumps(batch) + "\n",
                encoding="utf-8",
            )
            records, errors = summarizer.load_records([path])
        self.assertEqual(errors, [])
        self.assertEqual(len(records), 2)
        summary = summarizer.summarize(records)
        self.assertEqual(len(summary), 1)
        self.assertEqual(summary[0]["sampleCount"], 1)
        self.assertEqual(summary[0]["batchAccounting"]["sampleCount"], 1)
        self.assertEqual(summary[0]["batchAccounting"]["failedBatchCount"], 1)
        self.assertEqual(summary[0]["batchAggregateThroughputMbps"]["sampleCount"], 1)
        self.assertEqual(summary[0]["batchAggregateThroughputMbps"]["p50"], 41.943)
        self.assertEqual(summary[0]["batchAggregateThroughputMbps"]["definition"], "successfully completed payload bytes divided by batch wall time")


if __name__ == "__main__":
    unittest.main()

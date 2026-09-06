#!/usr/bin/env python3

import argparse
import csv
import json
from pathlib import Path


FIELDS = [
    "policy",
    "status",
    "scope",
    "samples",
    "initial_success_rate",
    "served_success_rate",
    "served_latency_p95_ms",
    "successful_latency_p95_ms",
    "wrong_choice_rate",
    "fallback_rate",
    "fallback_recovery_rate",
    "mean_regret_ms",
    "inference_p50_us",
    "inference_p95_us",
    "inference_mean_us",
    "reported_inference_p95_us",
    "invalid_provider_outputs",
    "feedback_observations",
    "promotion_verdict",
]


def number(value):
    return "" if value is None else value


def rows(summary):
    output = []
    for policy in summary["policies"]:
        for scope, metrics in policy["metrics"].items():
            output.append(
                {
                    "policy": policy["id"],
                    "status": policy["status"],
                    "scope": scope,
                    "samples": metrics["samples"],
                    "initial_success_rate": metrics["initialSuccessRate"],
                    "served_success_rate": metrics["servedSuccessRate"],
                    "served_latency_p95_ms": metrics["servedLatencyP95Ms"],
                    "successful_latency_p95_ms": metrics["successfulLatencyP95Ms"],
                    "wrong_choice_rate": metrics["wrongChoiceRate"],
                    "fallback_rate": metrics["fallbackRate"],
                    "fallback_recovery_rate": metrics["fallbackRecoveryRate"],
                    "mean_regret_ms": metrics["meanRegretMs"],
                    "inference_p50_us": metrics["inferenceP50Nanos"] / 1000.0,
                    "inference_p95_us": metrics["inferenceP95Nanos"] / 1000.0,
                    "inference_mean_us": metrics["inferenceMeanNanos"] / 1000.0,
                    "reported_inference_p95_us": number(
                        None
                        if metrics["reportedInferenceP95Nanos"] is None
                        else metrics["reportedInferenceP95Nanos"] / 1000.0
                    ),
                    "invalid_provider_outputs": metrics["invalidProviderOutputs"],
                    "feedback_observations": metrics["feedbackObservations"],
                    "promotion_verdict": summary["promotionVerdict"],
                }
            )
    return output


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("artifact_dir", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    summary_path = args.artifact_dir / "summary.json"
    summary = json.loads(summary_path.read_text(encoding="utf-8"))
    required = {"policies", "promotionVerdict", "selectedFeedbackOnly", "counterfactualCostsBenchmarkOnly"}
    missing = sorted(required.difference(summary))
    if missing:
        raise SystemExit(f"missing summary fields: {', '.join(missing)}")
    if not summary["selectedFeedbackOnly"] or not summary["counterfactualCostsBenchmarkOnly"]:
        raise SystemExit("fairness metadata rejected")
    output = args.output or args.artifact_dir / "normalized-summary.csv"
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=FIELDS)
        writer.writeheader()
        writer.writerows(rows(summary))
    print(f"promotion_verdict={summary['promotionVerdict']}")
    print(f"d_adapter_status={summary.get('dAdapterStatus', '')}")
    print(f"rows={len(rows(summary))}")
    print(f"csv={output}")


if __name__ == "__main__":
    main()

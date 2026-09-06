# B4 D adapter contract

`AegisLocal2EvaluationTest.kt`의 기본 D adapter는 생산 core의 다음 API만 사용한다.

```kotlin
internal class AegisLocal2Engine(
    config: AegisLocal2Config = AegisLocal2Config(),
    nanoClock: AegisLocal2NanoClock = AegisLocal2NanoClock { System.nanoTime() }
)

fun decide(
    context: TurboContext,
    allowedCandidates: List<TurboStrategyId>,
    baselineDecision: TurboDecision,
    epoch: Long
): AegisLocal2Decision

fun observe(
    token: Long,
    outcome: TurboOutcome,
    epoch: Long
): AegisLocal2ObserveResult

fun reset(epoch: Long)
fun stats(): AegisLocal2Stats
```

The harness supplies `AegisLocal2Config(requestedMode = SHADOW)` by default. `ACTIVE` can be requested only for a coordinated benchmark run. The current core can downgrade that request to shadow, and the harness records the resulting mode instead of treating the request as active evidence.

The D decision path is deliberately narrow. The harness creates a deterministic baseline `TurboDecision`, calls `decide`, and uses only `AegisLocal2Decision.suggestion` as the candidate decision. `candidateScores` are not fed back into the policy and are not treated as a second oracle. The reported `inferenceNanos`, `confidence`, `reason`, mode, token, and statistics are retained in the raw decision record.

For every selected sample, the harness executes the controlled initial outcome and any bounded fallback. It then sends one `TurboOutcome` for the selected initial strategy. That outcome contains the final served success, accumulated latency, retry count, `fallbackUsed`, and the same `aegisToken` returned by `decide`. A fallback strategy's independent hidden outcome never becomes a separate training observation.

The adapter is reset once at the start of each policy run. The 144 training samples then continue into the 144 evaluation samples so the evaluation measures warm adaptation. A new D adapter is constructed for every `V4PolicyRun`, so no state crosses A, B, C, or D runs.

`SHADOW` means the runtime would keep using the current Turbo selection while recording AegisLocal2 suggestions. B4 additionally evaluates the suggestion as a counterfactual policy on the same controlled trace. This is a benchmark comparison and does not claim that production runtime has switched to D.

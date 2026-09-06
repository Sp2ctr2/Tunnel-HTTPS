# B4 AegisLocal 2 evaluation

이 문서는 `AegisLocal2EvaluationTest.kt`가 고정하는 B4 평가 계약이다. B3 DNS 측정 파일과 서로 다른 목적을 가지며, B4는 네트워크 resolver 측정이 아니라 네트워크 전략 선택 정책의 공정한 A/B/C/D 비교만 담당한다.

## 범위

비교 대상은 다음 네 정책이다.

| ID | 정책 | 상태 |
|---|---|---|
| A | 고정 baseline order | 항상 안전한 후보 순서의 첫 항목 |
| B | 최근 관측 EMA heuristic | 선택된 전략의 성공률과 latency만 갱신 |
| C | 현재 `TurboAiEngine` | 생산 코드의 현재 public `select`/`update` API를 그대로 사용 |
| D | `AegisLocal2Engine` | test-only adapter로 `decide`/`observe` 연결, 기본 실행은 SHADOW |

C의 상태를 바꾸거나 기존 엔진을 복제하지 않는다. D는 다음 실제 계약에 연결되어 있다.

```text
AegisLocal2Engine.decide(context, allowedCandidates, baselineDecision, epoch)
AegisLocal2Engine.observe(token, turboOutcome, epoch)
```

결정의 `aegisToken`은 관측의 `TurboOutcome.aegisToken`으로 그대로 전달한다. B4 스캐폴드의 epoch는 고정된 `1`이며, 실제 runtime generation을 대체하지 않는다. 부모 통합에서는 실제 network generation을 넣어야 한다. `candidateScores`는 분석용 raw score로만 취급하고, B4의 D 선택은 엔진이 안전 게이트를 거친 `suggestion`만 사용한다.

D adapter는 현재 실제 core를 직접 생성한다. 별도 D bridge가 필요한 경우에만 `aegis.v4.d.adapterClass`를 지정한다. 실행 시작 때 adapter의 `reset(epoch)`을 호출하므로 정책 run 사이의 상태가 새지 않고, 한 run 안에서는 train 상태가 eval로 이어진다. 매 decision과 observe 뒤 `stats()` 기반 상태를 읽어 `SHADOW`, `KILLED`, `OFF`, `SHADOW_DOWNGRADED`를 구분한다.

## 공정성 규칙

각 정책은 같은 trace를 같은 순서로 받는다. 정책마다 별도 상태를 시작하지만 train 뒤에 같은 정책의 eval 상태를 이어간다. A는 상태를 사용하지 않고, B/C/D는 실제 선택하여 시도한 전략들의 관측만 받는다. 실행하지 않은 후보의 정답은 전달하지 않는다.

정책 입력에는 다음 정보만 있다.

- destination의 opaque key
- destination-independent context family key
- transport, metered, roaming, validated, battery saver
- TLS ClientHello와 SNI의 안전성 관련 상태
- RTT, retry, success, handshake, resource-pressure bucket
- 허용된 전략 목록

정책 입력에는 전체 outcome map, hindsight best action, 다른 전략의 latency, oracle label이 들어가지 않는다. 모든 전략의 synthetic outcome은 선택이 끝난 뒤에만 benchmark 계산에 사용된다.

실패한 첫 선택은 모든 정책의 fallback order를 따라 controlled fixture에서 재시도할 수 있다. 지연 합계는 전송 전체 지표에 반영하고, 학습에는 각 시도의 전략·성공 여부·해당 시도 지연을 따로 전달한다. A 실패 후 B 성공이면 A에는 실패, B에는 성공을 기록한다. D에서는 동일 토큰의 서로 다른 전략 관측을 사용한다. 성공 뒤에는 남은 후보를 실행하거나 관측하지 않는다. 따라서 feedback 수는 연결 수보다 클 수 있다. 초기 구현의 잘못된 성공 귀속은 최종 평가 전에 수정했으며 고정 회귀 테스트로 검사한다.

## Trace 설계

기본 trace는 train 144개와 eval 144개, 총 288개다. `trainSeed`와 `evaluationSeed`는 서로 다르며 설정 JSON과 hash로 기록된다. destination revisit 그룹만 의도적으로 train/eval에 걸쳐 재사용되고, 나머지 unique key는 split seed의 영향을 받는다.

시나리오는 6종이다.

| 시나리오 | 검증하는 것 |
|---|---|
| `COLD_START` | 모델 evidence가 없는 첫 선택에서 안전한 baseline과 fallback |
| `WARM_RECURRING` | 같은 coarse context가 반복될 때 선택된 feedback으로 적응 |
| `DESTINATION_REVISIT` | 일부 destination의 재방문과 shared context 일반화 |
| `CHANGING_QUALITY` | 품질 phase가 바뀔 때 오래된 EMA와 최근 결과의 균형 |
| `MISLEADING_TRANSIENT` | 짧은 관측 신호가 실제 action 결과와 어긋나는 구간 |
| `STABLE_NO_ADVANTAGE` | 어떤 전략도 이득이 없는 control |

Synthetic outcome은 전략별 성공 여부와 latency를 deterministic hash로 만든다. 이 결과는 실제 인터넷이나 사용자 트래픽의 증거가 아니며, 특정 사이트의 성공률을 의미하지 않는다.

## 출력 metric

`summary.json`과 `summary.csv`는 train, eval, eval scenario별로 다음 값을 기록한다.

- `initialSuccessRate`: 첫 선택만 성공한 비율
- `servedSuccessRate`: fallback을 포함한 최종 성공 비율
- `servedLatencyP95Ms`: fallback 비용과 실패 비용을 포함한 p95 service cost
- `successfulLatencyP95Ms`: 최종 성공 샘플만의 p95 latency
- `wrongChoiceRate`: hidden benchmark optimum과 다른 첫 선택 비율
- `fallbackRate`: 첫 선택 실패 뒤 fallback을 시도한 비율
- `fallbackRecoveryRate`: fallback으로 회복한 비율
- `meanRegretMs`: 선택 비용과 counterfactual best cost의 평균 차이
- `inferenceP50Nanos`, `inferenceP95Nanos`, `inferenceMeanNanos`: host JVM decision wall-clock overhead
- `reportedInferenceP95Nanos`: C 또는 D가 내부에서 보고한 inference time이 있을 때의 값
- `invalidProviderOutputs`: D가 허용 목록 밖 전략을 반환한 횟수
- `feedbackObservations`: 정책이 받은 선택 feedback 수

Inference time은 네트워크 latency가 아니며, Android ART나 emulator의 실제 비용을 의미하지 않는다. B4의 결과는 `host-local-jvm-synthetic`으로 표시한다.

## Promotion gate

D는 다음을 모두 통과해야 active 후보가 된다.

- D가 `ACTIVE` 상태여야 한다.
- 허용 목록 밖 출력이 없어야 한다.
- eval의 각 시나리오에서 B보다 served success가 0.03 이상 낮아지지 않아야 한다.
- eval의 각 시나리오에서 B보다 served p95 cost가 15% 넘게 증가하지 않아야 한다.
- D의 eval inference p95가 1.5 ms 이하이어야 한다.
- 전체 eval에서 B보다 success 1%p, p95 5%, 또는 wrong-choice 2%p 중 하나 이상 의미 있게 좋아야 한다.
- 전체 eval의 success와 p95가 B보다 악화되지 않아야 한다.

D adapter가 로드되지 않거나 adapter 오류가 발생하면 `SHADOW_D_API_PENDING` 또는 오류 상태로 기록한다. 모델 kill switch가 동작하면 `SHADOW_D_MODEL_KILLED`, D가 한 번도 baseline 밖 suggestion을 만들지 않으면 `SHADOW_D_NO_SUGGESTION`으로 기록한다. API가 실제로 연결됐지만 mode가 `SHADOW`이면 모든 gate를 통과해도 `SHADOW_D_READY_NOT_ACTIVATED`로 남긴다. 이 경우 결과는 승격 근거가 아니라 다음 active 검증을 위한 근거다.

## 실행 준비

현재 단계에서는 Gradle과 emulator를 실행하지 않는다. 조정이 끝난 뒤 프로젝트 루트에서 다음 스크립트를 사용한다.

```text
tools/v4-aegis-local2-run.sh benchmark-results/aegis-local2/<run-id>
```

기본 D 연결은 `V4ProductionAegisLocal2Adapter`다. 외부 test adapter가 필요하면 다음 property를 전달할 수 있다.

```text
-Daegis.v4.d.adapterClass=com.tunnelvpn.app.YourAegisLocal2EvaluationAdapter
-Daegis.v4.d.mode=SHADOW
```

`AEGIS_V4_ARTIFACT_DIR` 환경 변수는 B3와 같은 방식으로 결과 위치를 지정한다. B4는 그 디렉터리 안에 `config.json`, `metadata.json`, `raw-trace.jsonl`, `raw-decisions.jsonl`, `summary.json`, `summary.csv`, `summary.md`를 생성한다. B3의 `b3-dns.csv`와 같은 artifact directory를 쓸 수 있지만, B4 파일은 정책 평가 결과만 담는다.

## 해석 규칙

`wrongChoiceRate`와 `meanRegretMs`는 hidden synthetic outcome을 사용하므로 실제 runtime 품질의 직접 측정값이 아니다. `servedSuccessRate`도 synthetic fixture의 성공률이다. 이 수치만으로 emulator, carrier, physical device, battery, public Internet 성능을 주장하지 않는다.

B가 D보다 좋거나 D가 의미 있는 우위를 입증하지 못하면 D는 SHADOW로 유지한다. Runtime에서 C는 현재 `TurboAiEngine`이 선택한 전략을 실제 경로에 사용하고 AegisLocal2는 shadow suggestion만 관측하는 구조다. B4의 D 평가는 비교를 위해 고정 baseline decision을 AegisLocal2에 공급하고, D suggestion을 가상으로 선택한 결과를 계산한다. 따라서 B4의 D 결과를 runtime의 현재 C 선택 결과로 오해하지 않는다. C가 B보다 나쁘더라도 C의 구현을 이 평가에서 수정하거나 재설계하지 않는다. C는 현재 제품 AI의 reference로만 기록한다.

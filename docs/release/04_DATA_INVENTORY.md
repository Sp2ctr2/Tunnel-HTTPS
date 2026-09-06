# Data inventory

| Data | Source/purpose | Off-device | Storage/retention | User control | Confidence |
|---|---|---|---|---|---|
| IP addresses and ports | TUN forwarding and DNS packet construction | Upstream connections necessarily expose source/destination metadata | memory per flow; bounded until close/timeout | disconnect or bypass apps | Confirmed |
| DNS queries/domain names | resolver, ad block, split mapping | sent to selected DoH providers; system resolver only when DoH disabled | bounded memory cache/mapping; cleared on network change/engine close; exact process-death behavior is platform memory lifetime | DoH/ad-block/split controls | Confirmed |
| TLS SNI | ClientHello classification/fragmentation | not separately transmitted by first-party code; naturally sent to destination TLS server | in-memory flow processing only | mode/disconnect | Confirmed |
| Installed app package names/labels | app bypass and browser-only discovery | no first-party transmission found | selected package names in SharedPreferences | selection/removal/app data clear | Confirmed |
| User settings | restore service behavior | no first-party transmission found | SharedPreferences; backup excluded | settings/app data clear | Confirmed |
| Aggregate diagnostics | local UI counters/status | no first-party telemetry found | process memory only | disconnect/process exit | Confirmed |
| Advertising ID/account/support data | none found | none found | none found | N/A | Confirmed from repository |
| Provider-side logs/retention | external DoH/reachability providers | external processing occurs | unknown | provider policy | REQUIRES OWNER INPUT |

No claim that “no data is collected” should be made until Play’s ephemeral-processing definitions, provider relationships, and owner-operated infrastructure declarations are reviewed in Play Console.

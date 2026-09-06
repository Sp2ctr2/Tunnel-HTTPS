# Dependencies and licenses

| Direct dependency | Version | Purpose | License / release note |
|---|---:|---|---|
| AndroidX Core | 1.16.0 | Android compatibility APIs/profile components | Apache-2.0 |
| AndroidX WebKit | 1.16.0 | WebView feature compatibility | Apache-2.0 |
| kotlinx-coroutines-android | 1.10.2 | lifecycle/network concurrency | Apache-2.0 |
| OkHttp | 4.12.0 | HTTPS/DoH transport | Apache-2.0 |
| JUnit | 4.13.2 | JVM tests only | EPL-1.0 |

Important transitive families include Kotlin standard library, Okio, AndroidX annotations/profile installer, and Hamcrest test components. No advertising, analytics, crash, social, identity, or billing SDK was found.

Lint reports newer versions for Core, coroutines, and OkHttp. They were not upgraded: no security advisory or API-36 build blocker was established, and the task prohibits unrelated version changes. Before release, verify advisories and licenses against official dependency metadata/SBOM tooling.

R8 and resource shrinking build successfully. Keep rules retain only JS-interface methods and manifest-instantiated app components; dependency consumer rules remain in effect.

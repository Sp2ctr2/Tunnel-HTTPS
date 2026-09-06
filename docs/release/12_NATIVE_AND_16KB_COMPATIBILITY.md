# Native code and 16 KB page-size compatibility

- C/C++/JNI/Rust/Go source: none found.
- NDK/CMake/ABI filters: none configured.
- Generated debug APK, release APK, and release AAB: zero `.so` entries.
- Result: code is Java/Kotlin-only and is not subject to ELF alignment remediation. A 16 KB device smoke test is still recommended.

Android’s official guidance says Java/Kotlin-only apps already support 16 KB page-size devices; Google Play requires support for apps targeting Android 15+ submitted from 2025-11-01. See [Support 16 KB page sizes](https://developer.android.com/guide/practices/page-sizes).

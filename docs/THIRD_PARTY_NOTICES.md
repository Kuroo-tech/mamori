# Mamori — Third-party notices

Mamori is a fork of [FCMGuard-HyperOS](https://github.com/ReedGAOOO/FCMGuard-HyperOS).

## Upstream

- **FCMGuard-HyperOS** — MIT License, Copyright (c) 2026 Yijie Gao.
  The full license text is in the repository root `LICENSE`. The fork keeps the app
  package, the write mechanism, the FCM reconnect approach and the on-device documentation
  attribution intact.

## Runtime dependencies

| Component | License | Used for |
| --- | --- | --- |
| Kotlin standard library (`org.jetbrains.kotlin`) | Apache License 2.0 | language runtime |
| KotlinX Coroutines (`org.jetbrains.kotlinx:kotlinx-coroutines-android`) | Apache License 2.0 | structured concurrency |
| AndroidX Core / Activity / Lifecycle | Apache License 2.0 | Compose host, lifecycle-aware state |
| Jetpack Compose UI, Foundation, Material 3 | Apache License 2.0 | application UI |
| AndroidX Navigation Compose | Apache License 2.0 | top-level navigation |

## Build and test dependencies

| Component | License | Used for |
| --- | --- | --- |
| Android Gradle Plugin, R8 | Apache License 2.0 | build, shrinking |
| JUnit 4 (`junit:junit`) | Eclipse Public License 1.0 | JVM unit tests |
| AndroidX Test (`androidx.test.ext:junit`, `androidx.test:runner`) | Apache License 2.0 | instrumentation runner |
| Compose UI Test (`ui-test-junit4`, `ui-test-manifest`) | Apache License 2.0 | instrumentation tests |

License texts:

- Apache License 2.0 — <https://www.apache.org/licenses/LICENSE-2.0>
- MIT — <https://opensource.org/license/mit>
- Eclipse Public License 1.0 — <https://www.eclipse.org/legal/epl-v10.html>

No dependency is bundled with a copyleft license, and no library is modified.

# D36–D40 Direct Dependency License Audit

**Status:** PARTIAL — this records local POM metadata for direct dependencies only. It is not a complete license or compliance review.

Declaration sources: [Android version catalog](../android-app/gradle/libs.versions.toml), [Android app build](../android-app/app/build.gradle.kts), [backend version catalog](../backend/gradle/libs.versions.toml), and [backend build](../backend/build.gradle.kts). POMs were read only from the local Gradle cache at `C:\Users\Xuan\.gradle\caches\modules-2\files-2.1\<group>\<artifact>\<version>\<hash>\*.pom`. No network or GitHub access was used. License names below are the names recorded in those POMs; linked license URLs in POMs were not followed.

Counts include unique directly declared library artifacts, including test/debug dependencies. Compose UI versions below come from the cached `androidx.compose:compose-bom:2026.02.01` POM; the BOM is listed once although it is declared for both `implementation` and `androidTestImplementation`.

## Android libraries

| Configuration | Artifact | Version | Cached POM license metadata |
|---|---|---:|---|
| `implementation`, `androidTestImplementation` | `androidx.compose:compose-bom` | `2026.02.01` | The Apache Software License, Version 2.0 |
| `implementation` | `androidx.activity:activity-compose` | `1.13.0` | The Apache Software License, Version 2.0 |
| `implementation` | `androidx.compose.material3:material3` | `1.4.0` | The Apache Software License, Version 2.0 |
| `implementation` | `androidx.compose.ui:ui` | `1.10.4` | The Apache Software License, Version 2.0 |
| `implementation` | `androidx.compose.ui:ui-graphics` | `1.10.4` | The Apache Software License, Version 2.0 |
| `implementation` | `androidx.compose.ui:ui-tooling-preview` | `1.10.4` | The Apache Software License, Version 2.0 |
| `implementation` | `androidx.core:core-ktx` | `1.19.1` | The Apache Software License, Version 2.0 |
| `implementation` | `androidx.lifecycle:lifecycle-runtime-ktx` | `2.11.0` | The Apache Software License, Version 2.0 |
| `implementation` | `org.jetbrains.kotlinx:kotlinx-coroutines-android` | `1.10.2` | Apache-2.0 |
| `implementation` | `androidx.datastore:datastore-preferences` | `1.1.7` | The Apache Software License, Version 2.0 |
| `testImplementation` | `junit:junit` | `4.13.2` | Eclipse Public License 1.0 |
| `testImplementation` | `org.jetbrains.kotlinx:kotlinx-coroutines-test` | `1.10.2` | Apache-2.0 |
| `testImplementation` | `org.json:json` | `20240303` | Public Domain |
| `androidTestImplementation` | `androidx.compose.ui:ui-test-junit4` | `1.10.4` | The Apache Software License, Version 2.0 |
| `androidTestImplementation` | `androidx.test.espresso:espresso-core` | `3.7.0` | The Apache Software License, Version 2.0 |
| `androidTestImplementation` | `androidx.test.ext:junit` | `1.3.0` | The Apache Software License, Version 2.0 |
| `debugImplementation` | `androidx.compose.ui:ui-test-manifest` | `1.10.4` | The Apache Software License, Version 2.0 |
| `debugImplementation` | `androidx.compose.ui:ui-tooling` | `1.10.4` | The Apache Software License, Version 2.0 |

**Android total:** 18 unique direct library artifacts. A cached POM with a license entry was found for each listed artifact.

## Backend libraries

| Configuration | Artifact | Version | Cached POM license metadata |
|---|---|---:|---|
| `implementation` | `io.ktor:ktor-server-core-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `implementation` | `io.ktor:ktor-server-netty-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `implementation` | `io.ktor:ktor-server-content-negotiation-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `implementation` | `io.ktor:ktor-serialization-kotlinx-json-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `implementation` | `io.ktor:ktor-server-auth-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `implementation` | `io.ktor:ktor-server-auth-jwt-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `implementation` | `io.ktor:ktor-server-websockets-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `implementation` | `io.ktor:ktor-server-status-pages-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `implementation` | `io.ktor:ktor-server-call-logging-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `implementation` | `ch.qos.logback:logback-classic` | `1.5.16` | Artifact POM has no `<licenses>` entry. Cached parent `ch.qos.logback:logback-parent:1.5.16` lists EPL 1.0 and LGPL 2.1; applicability from parent metadata is ambiguous without reviewing the artifact's license files. |
| `implementation` | `org.postgresql:postgresql` | `42.7.7` | BSD-2-Clause |
| `implementation` | `com.zaxxer:HikariCP` | `6.2.1` | The Apache Software License, Version 2.0 |
| `implementation` | `org.mindrot:jbcrypt` | `0.4` | ISC |
| `testImplementation` | `io.ktor:ktor-server-test-host-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `testImplementation` | `io.ktor:ktor-client-content-negotiation-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `testImplementation` | `io.ktor:ktor-client-websockets-jvm` | `3.4.3` | The Apache Software License, Version 2.0 |
| `testImplementation` | `org.jetbrains.kotlin:kotlin-test-junit` | `2.2.10` | The Apache License, Version 2.0 |

**Backend total:** 17 unique direct library artifacts. The Logback artifact POM is the only listed library POM without its own license entry; its cached parent metadata is recorded above but is not treated as definitive.

## Applied build plugins

These are build tooling, separate from the library totals above.

| Plugin declaration | Version | Cached POM license metadata / status |
|---|---:|---|
| Android `com.android.application` (`com.android.tools.build:gradle`) | `9.5.1` | Apache 2.0 in implementation and plugin marker POMs |
| Android `org.jetbrains.kotlin.plugin.compose` | `2.2.10` | Apache 2.0 in marker and `org.jetbrains.kotlin:compose-compiler-gradle-plugin` POMs |
| Backend `org.jetbrains.kotlin.jvm` | `2.2.10` | Marker POM has no license entry; implementation `org.jetbrains.kotlin:kotlin-gradle-plugin` POM says Apache 2.0 |
| Backend `org.jetbrains.kotlin.plugin.serialization` | `2.2.10` | Marker POM has no license entry; implementation `org.jetbrains.kotlin:kotlin-serialization` POM says Apache 2.0 |
| Backend catalog alias `io.ktor.plugin` | `3.4.3` | Not applied in `backend/build.gradle.kts`; marker and implementation POMs were not present in the local cache. Not counted as an active dependency. |
| Gradle `application` | Built into Gradle | No separate Maven artifact/POM |

## Limits

This audit does **not** inspect transitive dependencies, source `LICENSE`/`NOTICE` files, Android SDK/platform components, or full license obligations and compatibility. It does not establish full compliance. POM metadata is a starting point; missing or ambiguous entries need source-license review before making a definitive claim.

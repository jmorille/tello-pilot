// Security floor for the build tooling (Gradle plugin classpath, mostly AGP's transitive
// dependencies): never part of the APK, but they parse untrusted inputs during the build
// (signing, XML, archives). Drop an entry once the plugins ship the patched version themselves.
buildscript {
    dependencies {
        constraints {
            listOf(
                "org.bouncycastle:bcprov-jdk18on:1.85", // CVE-2026-8763, CVE-2026-13506, CVE-2026-0636
                "org.bouncycastle:bcpkix-jdk18on:1.85", // CVE-2026-5588
                "org.bouncycastle:bcutil-jdk18on:1.85", // kept aligned with bcprov/bcpkix
                "org.bitbucket.b_c:jose4j:0.9.6", // CVE-2024-29371
                "org.jdom:jdom2:2.0.6.1", // CVE-2021-33813
                "org.apache.commons:commons-lang3:3.18.0", // CVE-2025-48924
                "org.apache.httpcomponents:httpclient:4.5.14", // CVE-2020-13956
                "com.google.guava:guava:33.7.2-jre", // CVE-2026-102554
            ).forEach { classpath(it) { because("security fix") } }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    // Unit-test coverage, aggregated here from every module (README badge, see ci.yml).
    alias(libs.plugins.kover)
}

// `./gradlew koverXmlReportCoverage` / `koverHtmlReportCoverage` / `koverLogCoverage`: line coverage
// of the JVM unit tests of :core:protocol, :core:control and of the :app debug unit tests. Each module
// declares a "coverage" variant (jvm or debug) that is merged here.
dependencies {
    kover(project(":core:protocol"))
    kover(project(":core:control"))
    kover(project(":app"))
}

kover {
    currentProject {
        createVariant("coverage") {}
    }
    reports {
        filters {
            excludes {
                // Generated code (Hilt/Dagger, Compose singletons, BuildConfig): not hand-written logic.
                classes(
                    "*.BuildConfig",
                    "*_Factory*", "*_MembersInjector", "*_HiltModules*", "*_GeneratedInjector",
                    "Hilt_*", "*.Hilt_*", "hilt_aggregated_deps.*", "dagger.hilt.internal.*",
                    "*ComposableSingletons*",
                )
            }
        }
    }
}

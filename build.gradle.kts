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
                "org.freemarker:freemarker:2.3.35", // CVE-2026-84939 / GHSA-27j2-h3m2-8237 (Kover HTML reporter)
            ).forEach { classpath(it) { because("security fix") } }
        }
    }
}

// Same security floor for the tooling classpaths that Gradle resolves inside the projects and that
// the buildscript constraints above do not reach: the lint tool (`androidLintTool`: guava,
// bouncycastle, commons-lang3, httpclient) and the KSP / Hilt annotation processors (`*kspClasspath`,
// `ksp*KotlinProcessorClasspath`, `hiltAnnotationProcessor*`: guava via dagger-compiler). Several of
// them are AGP-internal, resolvable-only configurations on which constraints cannot be declared, hence
// a resolution rule. It only ever raises a version that is below the floor, and skips the
// compile/runtime classpaths (see below). Guava keeps its requested flavour (-jre / -android).
// Keep in sync with the buildscript list above; drop an entry once the tools ship the patched version.
allprojects {
    val securityFloor = mapOf(
        "org.bouncycastle:bcprov-jdk18on" to "1.85", // CVE-2026-8763, CVE-2026-13506, CVE-2026-0636
        "org.bouncycastle:bcpkix-jdk18on" to "1.85", // CVE-2026-5588
        "org.bouncycastle:bcutil-jdk18on" to "1.85", // kept aligned with bcprov/bcpkix
        "org.bitbucket.b_c:jose4j" to "0.9.6", // CVE-2024-29371
        "org.jdom:jdom2" to "2.0.6.1", // CVE-2021-33813
        "org.apache.commons:commons-lang3" to "3.18.0", // CVE-2025-48924
        "org.apache.httpcomponents:httpclient" to "4.5.14", // CVE-2020-13956
        "com.google.guava:guava" to "33.7.2", // CVE-2026-102554
        "org.freemarker:freemarker" to "2.3.35", // CVE-2026-84939 / GHSA-27j2-h3m2-8237
    )
    // Numeric comparison of the dotted part only ("33.6.0-jre" -> [33, 6, 0]).
    val isBelow = { version: String, floor: String ->
        val numbers = { v: String -> v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 } }
        val a = numbers(version)
        val b = numbers(floor)
        (0 until maxOf(a.size, b.size))
            .map { a.getOrElse(it) { 0 } - b.getOrElse(it) { 0 } }
            .firstOrNull { it != 0 }?.let { it < 0 } ?: false
    }
    configurations.configureEach {
        // Code we compile and ship (APK, test classpaths) is left alone: same filter as
        // .github/gradle/lock-all-configurations.init.gradle.kts, those graphs are scanned by OSV.
        if (name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath") ||
            name == "compileClasspath" || name == "runtimeClasspath"
        ) {
            return@configureEach
        }
        resolutionStrategy.eachDependency {
            val floor = securityFloor["${requested.group}:${requested.name}"] ?: return@eachDependency
            val version = requested.version
            if (version.isNullOrEmpty() || !version.first().isDigit() || !isBelow(version, floor)) {
                return@eachDependency
            }
            val flavour = if (requested.name == "guava") "-" + version.substringAfter('-', "jre") else ""
            useVersion(floor + flavour)
            because("security fix")
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

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kover)
}

// Merged into the root coverage report (README badge).
kover {
    currentProject {
        createVariant("coverage") { add("jvm") }
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

dependencies {
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// On-demand mutation testing: `./gradlew :core:protocol:pitest` (see gradle/pitest.gradle.kts).
apply(from = rootProject.file("gradle/pitest.gradle.kts"))

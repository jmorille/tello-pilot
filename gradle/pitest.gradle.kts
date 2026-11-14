// On-demand mutation testing (Pitest) of a pure JVM module: `./gradlew :core:protocol:pitest`
// (about 1 min) or `:core:control:pitest` (about 13 min: TelloController's virtual-time sessions
// dominate). Not wired to `check` nor to CI. HTML + XML report in build/reports/pitest. Applied
// with `apply(from = ...)` by the core modules. Narrower runs:
//   -Ppitest.targetClasses=com.drone.tello.control.Geofence*,com.drone.tello.control.ImpactDetector*
//   -Ppitest.excludedClasses=com.drone.tello.control.TelloController*
//
// Pitest runs through its command line entry point rather than the Gradle plugin: no extra plugin
// on the build classpath, and the task stays configuration-cache friendly (everything the argument
// provider needs is a local value, never a reference to this script).

configurations.create("pitest") {
    isCanBeConsumed = false
    isCanBeResolved = true
    description = "Pitest and its JUnit 5 plugin (mutation testing, on demand)."
}

dependencies {
    "pitest"("org.pitest:pitest-command-line:1.30.1")
    "pitest"("org.pitest:pitest-junit5-plugin:1.2.3")
}

tasks.register<JavaExec>("pitest") {
    group = "verification"
    description = "Mutation testing of the main classes against the unit tests (Pitest, on demand)."
    val sourceSets = project.the<SourceSetContainer>()
    // The module's test runtime classpath brings the main classes, the tests, JUnit and its launcher.
    val testRuntime = sourceSets["test"].runtimeClasspath
    // Only the classes of this module are mutated (not :core:protocol from :core:control).
    val mainClassDirs = sourceSets["main"].output.classesDirs
    val sourceDirs = sourceSets["main"].allSource.sourceDirectories
    val reportDir = project.layout.buildDirectory.dir("reports/pitest")
    val targetClasses = project.providers.gradleProperty("pitest.targetClasses")
    val excludedClasses = project.providers.gradleProperty("pitest.excludedClasses")
    val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)

    dependsOn("testClasses")
    classpath = project.configurations["pitest"] + testRuntime
    mainClass = "org.pitest.mutationtest.commandline.MutationCoverageReport"
    inputs.files(testRuntime)
    outputs.dir(reportDir)
    // On-demand task: always rerun (the -Ppitest.* filters are not tracked as inputs).
    outputs.upToDateWhen { false }
    argumentProviders += CommandLineArgumentProvider {
        // Every package of the module (com.drone.tello.control.*, …).
        val packages = mainClassDirs.files
            .flatMap { dir ->
                dir.walkTopDown().filter { it.extension == "class" }
                    .map { it.parentFile.relativeTo(dir).invariantSeparatorsPath.replace('/', '.') + ".*" }
                    .toList()
            }
            .distinct()
            .joinToString(",")
        listOf(
            "--targetClasses", targetClasses.orNull ?: packages,
            "--targetTests", packages,
            "--classPath", testRuntime.files.joinToString(","),
            "--mutableCodePaths", mainClassDirs.files.joinToString(","),
            "--sourceDirs", sourceDirs.files.joinToString(","),
            "--reportDir", reportDir.get().asFile.absolutePath,
            "--outputFormats", "HTML,XML",
            "--timestampedReports", "false",
            "--threads", threads.toString(),
            // Kotlin data class boilerplate is not behaviour.
            "--excludedMethods", "toString,hashCode,equals,component*,copy,copy\$default",
            // A mutant that loops forever in virtual time is a kill: fail fast rather than wait.
            "--timeoutConst", "4000",
        ) + (excludedClasses.orNull?.let { listOf("--excludedClasses", it) } ?: emptyList())
    }
}

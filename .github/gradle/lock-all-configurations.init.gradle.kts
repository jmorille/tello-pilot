// CI-only init script: enables dependency locking for the compile/runtime classpaths (main and test,
// every Android variant) so that `--write-locks` produces gradle.lockfile files describing the fully
// resolved (transitive) dependency graph of the code we build and ship. OSV-Scanner then scans those
// lockfiles. Build tooling classpaths (AGP, lint, R8, Kotlin compiler, KSP processors) are out of scope:
// they never end up in the APK and are covered by Dependabot alerts via the dependency submission job.
// Nothing is committed.
allprojects {
    configurations.configureEach {
        if (name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath") ||
            name == "compileClasspath" || name == "runtimeClasspath"
        ) {
            resolutionStrategy.activateDependencyLocking()
        }
    }
}

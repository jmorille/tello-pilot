// CI-only init script: enables dependency locking for every configuration so that
// `--write-locks` produces gradle.lockfile files describing the fully resolved (transitive)
// dependency graph. OSV-Scanner then scans those lockfiles. Nothing is committed.
allprojects {
    dependencyLocking {
        lockAllConfigurations()
    }
}

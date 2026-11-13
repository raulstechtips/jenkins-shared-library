/**
 * Runs the declarative pipeline for a java repository.
 *
 * It runs languageConfigurablePipeline as type java. Build and unit test run build-command and
 * test-command in the jdk container. The image context stash leaves out .git, .pipeline and the root
 * .gradle cache. It never writes gradle.properties outside a release run.
 *
 * @throws error (fails the build) when RELEASE_VERSION is set on a branch other than main or on a
 *     pull request. Each step raises its own errors.
 */
def call() {
    languageConfigurablePipeline(type: 'java', container: 'jdk',
        imageContextExcludes: '.git,.git/**,.pipeline/**,.gradle/**')
}

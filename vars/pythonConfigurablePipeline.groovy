/**
 * Runs the declarative pipeline for a python repository.
 *
 * It runs languageConfigurablePipeline as type python. Build and unit test run build-command and
 * test-command in the python container, which has uv, Python 3.13 and git. Both commands get the
 * github-app token for git, so dependencies from private GitHub repos resolve over HTTPS. The image
 * context stash leaves out .git, .pipeline and the root .venv.
 *
 * @throws error (fails the build) when RELEASE_VERSION is set on a branch other than main or on a
 *     pull request. Each step raises its own errors.
 */
def call() {
    languageConfigurablePipeline(type: 'python', container: 'python',
        imageContextExcludes: '.git,.git/**,.pipeline/**,.venv/**')
}

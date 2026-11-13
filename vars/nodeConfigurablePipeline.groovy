/**
 * Runs the declarative pipeline for a node repository.
 *
 * It runs languageConfigurablePipeline as type node. Build and unit test run build-command and
 * test-command in the node container, where pnpm comes from Corepack at the version in the
 * packageManager field of package.json. The image context stash leaves out .git, .pipeline, the root
 * node_modules and .next/cache, and keeps .next/standalone and .next/static.
 *
 * @throws error (fails the build) when RELEASE_VERSION is set on a branch other than main or on a
 *     pull request. Each step raises its own errors.
 */
def call() {
    languageConfigurablePipeline(type: 'node', container: 'node',
        imageContextExcludes: '.git,.git/**,.pipeline/**,node_modules/**,.next/cache/**')
}

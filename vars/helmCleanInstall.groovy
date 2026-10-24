import dev.raulstechtips.jenkins.helpers.HelmInstallCommands

/**
 * Uninstalls the release and installs the chart again, so the branch build starts from a clean
 * release.
 *
 * It checks out the repo, reads charts/Chart.yaml and runs the HelmInstallCommands.cleanInstall
 * commands one by one in the kubectl container, so the pipelines call it on the deployer agent.
 *
 * @param cfg the loaded config. Reads project-name, project-namespace, version, helm-image-tag-path
 *     and helm-overrides.
 * @param digest the built image digest, or null when no image was built
 * @throws IllegalArgumentException when project-name or project-namespace is missing, or when the
 *     digest is used and is invalid
 * @throws error (fails the build) when a helm or yq command fails
 */
def call(Map cfg, String digest = null) {
    checkout scm
    container('kubectl') {
        Map chart = readYaml(file: 'charts/Chart.yaml')
        List commands = new HelmInstallCommands().cleanInstall(cfg, chart, digest)
        for (int i = 0; i < commands.size(); i++) {
            sh commands[i]
        }
    }
}

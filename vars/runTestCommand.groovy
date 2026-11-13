import dev.raulstechtips.jenkins.helpers.ToolchainCommands

/**
 * Runs the project's test-command with sh in the given container.
 *
 * The script comes from ToolchainCommands for cfg.type. For python it runs inside withCredentials
 * that binds the github-app credentials as GH_USER and GH_TOKEN; the token stays in the environment
 * of that one process and is not written to any file. For other types the command runs as given,
 * with no credentials.
 *
 * @param cfg the loaded config. Reads test-command and type.
 * @param containerName the container to run in, for example shell, jdk, python or node
 * @throws error (fails the build) when the command fails
 */
def call(Map cfg, String containerName) {
    String script = ToolchainCommands.script(cfg.type as String, cfg['test-command'] as String)
    container(containerName) {
        if (ToolchainCommands.usesGitHubToken(cfg.type as String)) {
            withCredentials([usernamePassword(credentialsId: 'github-app',
                usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                sh script
            }
        } else {
            sh script
        }
    }
}

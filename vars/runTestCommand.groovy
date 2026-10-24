/**
 * Runs the project's test-command with sh in the given container.
 *
 * @param cfg the loaded config. Reads test-command.
 * @param containerName the container to run in, for example shell or jdk
 * @throws error (fails the build) when the command fails
 */
def call(Map cfg, String containerName) {
    container(containerName) {
        sh cfg['test-command']
    }
}

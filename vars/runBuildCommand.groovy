/**
 * Runs the project's build-command with sh in the given container.
 *
 * @param cfg the loaded config. Reads build-command.
 * @param containerName the container to run in, for example jdk
 * @throws error (fails the build) when the command fails
 */
def call(Map cfg, String containerName) {
    container(containerName) {
        sh cfg['build-command']
    }
}

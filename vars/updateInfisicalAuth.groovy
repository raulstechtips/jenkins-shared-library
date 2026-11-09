import dev.raulstechtips.jenkins.helpers.InfisicalAuthCommands

/**
 * Patches only the Infisical fields supplied as build parameters.
 *
 * InfisicalAuthCommands validates the build inputs and creates the ordered commands.
 * Each command reads its values from the environment, not Groovy command strings.
 * Empty or whitespace-only parameters leave the corresponding field unchanged.
 * The resources must already exist. This step does not modify Git or other consumers.
 */
def call() {
    List<String> commands = new InfisicalAuthCommands().update([
        clientId: env.INFISICAL_CLIENT_ID,
        clientSecret: env.INFISICAL_CLIENT_SECRET,
        url: env.INFISICAL_URL,
        connectionName: env.INFISICAL_CONNECTION_NAME
    ])
    container('kubectl') {
        for (int i = 0; i < commands.size(); i++) {
            sh commands[i]
        }
    }
}

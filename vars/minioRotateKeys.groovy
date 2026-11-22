import dev.raulstechtips.jenkins.helpers.MinioCommands
import dev.raulstechtips.jenkins.helpers.MinioNames

/**
 * Replaces the access key of user NAME-ENV-L for each level.
 *
 * Every user must exist; the step checks all of them before it changes anything. Per level it
 * creates a new access key, writes it to Infisical /minio/users/NAME/L (the folders are created
 * when missing), and then removes every other access key of the user.
 *
 * @param args name, environment, access (map with one checkbox per level, for example [RO: true])
 * @throws IllegalArgumentException for invalid input, before any shell command
 * @throws error (fails the build) when a user does not exist
 */
def call(Map args) {
    MinioNames names = new MinioNames()
    String name = names.validateName(args.name as String)
    String environment = names.validateEnvironment(args.environment as String)
    List<String> levels = names.levels(args.access as Map, knownLevels())
    List<String> accounts = []
    for (String level : levels) {
        accounts.add(names.accountName(name, environment, level))
    }

    minioSession { MinioCommands c ->
        for (String account : accounts) {
            if (c.missing(sh(script: c.userInfo(account), returnStdout: true))) {
                error("user ${account} does not exist".toString())
            }
        }
        for (int i = 0; i < levels.size(); i++) {
            String account = accounts[i]
            List<String> current = c.lines(sh(script: c.listAccessKeys(account), returnStdout: true))
            String created = c.newKeyId(sh(script: c.createKeys(account, environment, name, levels[i]), returnStdout: true))
            echo "Created access key ${created} for user ${account}.".toString()
            for (String old : current) {
                if (old != created) {
                    sh c.removeAccessKey(account, old)
                }
            }
        }
    }
}

private List<String> knownLevels() {
    Map doc = readYaml(text: libraryResource('dev/raulstechtips/jenkins/minio/levels.yaml'))
    List<String> levels = []
    for (Object level : (doc.levels as List)) {
        levels.add(level.toString())
    }
    return levels
}

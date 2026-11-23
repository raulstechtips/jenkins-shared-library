import dev.raulstechtips.jenkins.helpers.MinioCommands
import dev.raulstechtips.jenkins.helpers.MinioNames

/**
 * Removes the access of NAME in ENV for each level. The bucket is never removed.
 *
 * confirmName must equal NAME, else the step fails before any change. Per level it removes the
 * access keys of user NAME-ENV-L, detaches the policy, removes the user, removes the policy, and
 * deletes the Infisical folder /minio/users/NAME/L. Missing items are skipped. Then it deletes
 * /minio/users/NAME when that folder has no subfolders and no secrets.
 *
 * @param args name, environment, access (map with one checkbox per level, for example [RO: true]), confirmName
 * @throws IllegalArgumentException for invalid input, before any shell command
 * @throws error (fails the build) when confirmName is not equal to name
 */
def call(Map args) {
    MinioNames names = new MinioNames()
    String name = names.validateName(args.name as String)
    String environment = names.validateEnvironment(args.environment as String)
    List<String> levels = names.levels(args.access as Map, knownLevels())
    String confirm = args.confirmName == null ? '' : args.confirmName.toString().trim()
    if (confirm != name) {
        error("CONFIRM_NAME '${confirm}' is not equal to NAME '${name}'. Nothing is changed.".toString())
    }
    List<String> accounts = []
    for (String level : levels) {
        accounts.add(names.accountName(name, environment, level))
    }

    minioSession { MinioCommands c ->
        for (int i = 0; i < levels.size(); i++) {
            String account = accounts[i]
            String user = sh(script: c.userInfo(account), returnStdout: true)
            if (c.missing(user)) {
                echo "User ${account} does not exist. Skipped.".toString()
            } else {
                List<String> current = c.lines(sh(script: c.listAccessKeys(account), returnStdout: true))
                for (String key : current) {
                    sh c.removeAccessKey(account, key)
                }
                if (c.attachedPolicies(user).contains(account)) {
                    sh c.detachPolicy(account, account)
                }
                sh c.removeUser(account)
            }
            if (c.missing(sh(script: c.policyInfo(account), returnStdout: true))) {
                echo "Policy ${account} does not exist. Skipped.".toString()
            } else {
                sh c.removePolicy(account)
            }
            sh c.deleteKeysFolder(environment, name, levels[i])
        }
        sh c.deleteNameFolderIfEmpty(environment, name)
    }
    echo 'Bucket is not removed by this job.'
}

private List<String> knownLevels() {
    Map doc = readYaml(text: libraryResource('dev/raulstechtips/jenkins/minio/levels.yaml'))
    List<String> levels = []
    for (Object level : (doc.levels as List)) {
        levels.add(level.toString())
    }
    return levels
}

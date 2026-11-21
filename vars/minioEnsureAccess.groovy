import dev.raulstechtips.jenkins.helpers.MinioCommands
import dev.raulstechtips.jenkins.helpers.MinioNames
import dev.raulstechtips.jenkins.helpers.MinioPolicy

/**
 * Makes sure that a policy, a user and an access key in Infisical exist for each access level.
 *
 * All input is validated, and every policy is rendered, before the first MinIO or Infisical call.
 * Per level L: policy NAME-ENV-L is created when missing; when it exists for other buckets the
 * step fails. User NAME-ENV-L is created when missing, with a random password that nobody keeps.
 * The policy is attached when it is not attached. When the AWS_ACCESS_KEY_ID in Infisical
 * (/minio/users/NAME/L in environment ENV) is still a key of the user, the keys are kept.
 * Otherwise a new key is created, written to Infisical, and every other key of the user is removed.
 * A second run with the same input changes nothing.
 *
 * @param args name, environment, access (RO, RW or BOTH, from levels.yaml), buckets (text or list)
 * @throws IllegalArgumentException for invalid input, before any shell command
 * @throws error (fails the build) when a policy exists for other buckets
 */
def call(Map args) {
    MinioNames names = new MinioNames()
    String name = names.validateName(args.name as String)
    String environment = names.validateEnvironment(args.environment as String)
    List<String> levels = names.levels(args.access as String, knownLevels())
    Object rawBuckets = args.buckets
    List<String> buckets = names.parseBuckets(rawBuckets instanceof List ? ((List) rawBuckets).join(',') : rawBuckets as String)
    MinioPolicy policy = new MinioPolicy()
    List<Map> targets = []
    for (String level : levels) {
        String template = libraryResource("dev/raulstechtips/jenkins/minio/policies/${level}.json".toString())
        targets.add([level: level, account: names.accountName(name, environment, level),
            policy: policy.render(template, buckets)])
    }

    minioSession { MinioCommands c ->
        List<Map> missingPolicies = []
        for (Map t : targets) {
            String info = sh(script: c.policyInfo(t.account), returnStdout: true)
            if (c.missing(info)) {
                missingPolicies.add(t)
            } else {
                List<String> existing = policy.bucketsOf(info)
                if (existing != buckets) {
                    error("policy ${t.account} exists for other buckets: ${existing.join(', ')}, use another name".toString())
                }
                echo "Policy ${t.account} exists for the same buckets. Skipped.".toString()
            }
        }
        for (Map t : targets) {
            if (missingPolicies.contains(t)) {
                sh c.createPolicy(t.account, t.policy)
            }
            ensureUser(c, t.account)
            ensureKeys(c, t.account, environment, name, t.level)
        }
    }
}

private void ensureUser(MinioCommands c, String account) {
    String info = sh(script: c.userInfo(account), returnStdout: true)
    List<String> attached = []
    if (c.missing(info)) {
        sh c.addUser(account)
    } else {
        attached = c.attachedPolicies(info)
        echo "User ${account} exists. Skipped.".toString()
    }
    if (attached.contains(account)) {
        echo "Policy ${account} is attached to user ${account}. Skipped.".toString()
    } else {
        sh c.attachPolicy(account, account)
    }
}

private void ensureKeys(MinioCommands c, String account, String environment, String name, String level) {
    List<String> stored = c.lines(sh(script: c.readKeyId(environment, name, level), returnStdout: true))
    List<String> current = c.lines(sh(script: c.listAccessKeys(account), returnStdout: true))
    if (!stored.isEmpty() && current.contains(stored[0])) {
        echo "Access key ${stored[0]} in Infisical is a key of user ${account}. Skipped.".toString()
        return
    }
    String created = c.newKeyId(sh(script: c.createKeys(account, environment, name, level), returnStdout: true))
    echo "Created access key ${created} for user ${account}.".toString()
    for (String old : current) {
        if (old != created) {
            sh c.removeAccessKey(account, old)
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

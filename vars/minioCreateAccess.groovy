import dev.raulstechtips.jenkins.helpers.MinioCommands
import dev.raulstechtips.jenkins.helpers.MinioNames
import dev.raulstechtips.jenkins.helpers.MinioPolicy

/**
 * Creates a policy, a user and an access key in Infisical for each access level, and optionally
 * a new bucket first.
 *
 * All input is validated, and every policy is rendered, before the first MinIO or Infisical call.
 * Then every MinIO resource that the step creates is checked: the bucket (when args.bucket is set)
 * and policy and user NAME-ENV-L for each level L. When one or more of them exist, the build fails
 * with the list of all existing resources, and nothing is created.
 * Otherwise the step creates the bucket, and per level the policy, the user with a random password
 * that nobody keeps, the policy attachment and a new access key. The key is written to Infisical
 * (/minio/users/NAME/L in environment ENV); an existing Infisical secret there is overwritten.
 *
 * @param args name, environment, access (map with one checkbox per level from levels.yaml, for example [RO: true]), buckets (text or list),
 *        bucket (optional, a validated bucket name to create)
 * @throws IllegalArgumentException for invalid input, before any shell command
 */
def call(Map args) {
    MinioNames names = new MinioNames()
    String name = names.validateName(args.name as String)
    String environment = names.validateEnvironment(args.environment as String)
    List<String> levels = names.levels(args.access as Map, knownLevels())
    Object rawBuckets = args.buckets
    List<String> buckets = names.parseBuckets(rawBuckets instanceof List ? ((List) rawBuckets).join(',') : rawBuckets as String)
    String newBucket = args.bucket as String
    MinioPolicy policy = new MinioPolicy()
    List<Map> targets = []
    for (String level : levels) {
        String template = libraryResource("dev/raulstechtips/jenkins/minio/policies/${level}.json".toString())
        targets.add([level: level, account: names.accountName(name, environment, level),
            policy: policy.render(template, buckets)])
    }

    minioSession { MinioCommands c ->
        List<String> existing = []
        if (newBucket && c.lines(sh(script: c.listBuckets(), returnStdout: true)).contains(newBucket)) {
            existing.add("bucket ${newBucket}".toString())
        }
        for (Map t : targets) {
            if (!c.missing(sh(script: c.policyInfo(t.account), returnStdout: true))) {
                existing.add("policy ${t.account}".toString())
            }
            if (!c.missing(sh(script: c.userInfo(t.account), returnStdout: true))) {
                existing.add("user ${t.account}".toString())
            }
        }
        if (!existing.isEmpty()) {
            error("These MinIO resources already exist: ${existing.join(', ')}. Nothing was created.".toString())
        }

        if (newBucket) {
            sh c.createBucket(newBucket)
        }
        for (Map t : targets) {
            sh c.createPolicy(t.account, t.policy)
            sh c.addUser(t.account)
            sh c.attachPolicy(t.account, t.account)
            String created = c.newKeyId(sh(script: c.createKeys(t.account, environment, name, t.level), returnStdout: true))
            echo "Created access key ${created} for user ${t.account}.".toString()
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

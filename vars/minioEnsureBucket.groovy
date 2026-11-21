import dev.raulstechtips.jenkins.helpers.MinioCommands
import dev.raulstechtips.jenkins.helpers.MinioNames

/**
 * Creates bucket NAME-ENV when it is not in the bucket list, then runs minioEnsureAccess for
 * the ro and rw levels on that bucket. The existence check uses the bucket list, because the
 * Jenkins MinIO policy has no s3:ListBucket. A second run changes nothing.
 *
 * @param args name and environment
 * @throws IllegalArgumentException for invalid input, before any shell command
 */
def call(Map args) {
    MinioNames names = new MinioNames()
    String name = names.validateName(args.name as String)
    String environment = names.validateEnvironment(args.environment as String)
    String bucket = names.bucketName(name, environment)

    minioSession { MinioCommands c ->
        List<String> existing = c.lines(sh(script: c.listBuckets(), returnStdout: true))
        if (existing.contains(bucket)) {
            echo "Bucket ${bucket} exists. Skipped.".toString()
        } else {
            sh c.createBucket(bucket)
        }
        minioEnsureAccess(name: name, environment: environment, access: 'BOTH', buckets: [bucket])
    }
}

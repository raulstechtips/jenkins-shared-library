import dev.raulstechtips.jenkins.helpers.MinioNames

/**
 * Creates bucket NAME-ENV with the checked access levels through minioCreateAccess. Before it creates
 * anything, minioCreateAccess checks the bucket, the policies and the users, and fails the build
 * when one of them exists. The bucket check uses the bucket list, because the Jenkins MinIO
 * policy has no s3:ListBucket.
 *
 * @param args name, environment and access (map with one checkbox per level, for example [RO: true])
 * @throws IllegalArgumentException for invalid input, before any shell command
 */
def call(Map args) {
    MinioNames names = new MinioNames()
    String name = names.validateName(args.name as String)
    String environment = names.validateEnvironment(args.environment as String)
    String bucket = names.bucketName(name, environment)

    minioCreateAccess(name: name, environment: environment, access: args.access, buckets: [bucket], bucket: bucket)
}

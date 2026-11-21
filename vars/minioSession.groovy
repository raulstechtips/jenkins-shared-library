import dev.raulstechtips.jenkins.helpers.MinioCommands

/**
 * Runs a closure with MinIO and Infisical access in the tools container.
 *
 * It reads the minio/settings.yaml resource and builds MinioCommands from it. It installs the
 * pinned mc and infisical binaries, binds the jenkins-minio credential to MINIO_ACCESS_KEY and
 * MINIO_SECRET_KEY and the jenkins-infisical credential to INFISICAL_CLIENT_ID and
 * INFISICAL_CLIENT_SECRET, and sets MINIO_TOOLS_BIN. The MinioCommands scripts read only these
 * variables. A nested call (MINIO_TOOLS_BIN is already set) runs the closure in the open session.
 *
 * @param body receives the MinioCommands instance; runs inside container('tools')
 * @return the result of body
 * @throws error (fails the build) when settings.yaml has no credential IDs
 */
def call(Closure body) {
    Map settings = readYaml(text: libraryResource('dev/raulstechtips/jenkins/minio/settings.yaml'))
    MinioCommands commands = new MinioCommands(settings)
    if (env.MINIO_TOOLS_BIN) {
        return body(commands)
    }
    String minioId = settings.minioCredentialsId?.toString()
    String infisicalId = settings.infisicalCredentialsId?.toString()
    if (!minioId || !infisicalId) {
        error('settings.yaml must define minioCredentialsId and infisicalCredentialsId')
    }
    String bin = installMinioTools()
    def result = null
    container('tools') {
        withCredentials([
            usernamePassword(credentialsId: minioId,
                usernameVariable: 'MINIO_ACCESS_KEY', passwordVariable: 'MINIO_SECRET_KEY'),
            usernamePassword(credentialsId: infisicalId,
                usernameVariable: 'INFISICAL_CLIENT_ID', passwordVariable: 'INFISICAL_CLIENT_SECRET')
        ]) {
            withEnv(["MINIO_TOOLS_BIN=${bin}".toString()]) {
                result = body(commands)
            }
        }
    }
    return result
}

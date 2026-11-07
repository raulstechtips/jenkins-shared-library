import dev.raulstechtips.jenkins.helpers.VersionCalculator

/**
 * Returns the version for this build.
 *
 * A release run returns cfg.releaseVersion. Otherwise it reads the latest release tag with
 * latestRelease (0.0.0 when there is no release) and passes it, with the branch and build number,
 * to VersionCalculator, which increases the minor number. The version files (charts/Chart.yaml and
 * gradle.properties) are not read. For a pull request the head and base commits come from
 * scmRevision. For a push it runs git rev-parse HEAD. It has no container block, so it runs in the
 * default container of the current agent.
 *
 * @param cfg the loaded config. Reads releaseVersion.
 * @return the version string
 * @throws IllegalArgumentException when the branch name is invalid or the pull request commits
 *     cannot be read
 */
String call(Map cfg) {
    if (cfg.releaseVersion) {
        return cfg.releaseVersion
    }
    String release = latestRelease() ?: '0.0.0'
    Map environment = [
        BRANCH_NAME: env.BRANCH_NAME,
        CHANGE_ID: env.CHANGE_ID,
        CHANGE_BRANCH: env.CHANGE_BRANCH,
        BUILD_NUMBER: env.BUILD_NUMBER
    ]
    Map revision = environment.CHANGE_ID ? scmRevision() :
        [head: sh(script: 'git rev-parse HEAD', returnStdout: true).trim()]
    new VersionCalculator().calculate(release, environment, revision)
}

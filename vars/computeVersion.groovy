import dev.raulstechtips.jenkins.helpers.VersionCalculator

/**
 * Returns the version for this build.
 *
 * A release run returns cfg.releaseVersion. Otherwise it reads the version from charts/Chart.yaml
 * (cluster) or gradle.properties (java) and passes it, with the branch and build number, to
 * VersionCalculator. For a pull request the head and base commits come from scmRevision. For a push
 * it runs git rev-parse HEAD. It has no container block, so it runs in the default container of the
 * current agent.
 *
 * @param cfg the loaded config. Reads type and releaseVersion.
 * @return the version string
 * @throws IllegalArgumentException when the version file has no X.Y.Z version, the branch name is
 *     invalid, or the pull request commits cannot be read
 */
String call(Map cfg) {
    if (cfg.releaseVersion) {
        return cfg.releaseVersion
    }
    String path
    String version
    if (cfg.type == 'cluster') {
        path = 'charts/Chart.yaml'
        version = readYaml(text: readFile(path))?.version as String
    } else {
        path = 'gradle.properties'
        version = VersionCalculator.gradleVersion(readFile(path))
    }
    Map environment = [
        BRANCH_NAME: env.BRANCH_NAME,
        CHANGE_ID: env.CHANGE_ID,
        CHANGE_BRANCH: env.CHANGE_BRANCH,
        BUILD_NUMBER: env.BUILD_NUMBER
    ]
    Map revision = environment.CHANGE_ID ? scmRevision() :
        [head: sh(script: 'git rev-parse HEAD', returnStdout: true).trim()]
    new VersionCalculator().calculate(version, path, environment, revision)
}

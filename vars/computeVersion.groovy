import dev.raulstechtips.jenkins.helpers.VersionCalculator

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

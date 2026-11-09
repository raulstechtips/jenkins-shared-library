import dev.raulstechtips.jenkins.helpers.JobDiscovery

/**
 * Refreshes manual SCM jobs from the current main checkout.
 * Called by discoverJobsPipeline only on main, never on pull requests or tags.
 * Missing files disable previously generated jobs; the Jobs folder is retained.
 */
def call() {
    deleteDir()
    checkout scm
    def remote = scm.userRemoteConfigs[0]
    List<String> paths = findFiles(glob: 'pipelines/*.Jenkinsfile')
        .findAll { !it.directory }
        .collect { it.path }

    jobDsl(
        scriptText: JobDiscovery.SCRIPT,
        additionalParameters: [
            jobDefinitions: JobDiscovery.jobs(paths),
            repositoryUrl: remote.url,
            scmCredentialsId: remote.credentialsId
        ],
        lookupStrategy: 'JENKINS_ROOT',
        removedJobAction: 'DISABLE',
        failOnSeedCollision: true,
        sandbox: false
    )
}

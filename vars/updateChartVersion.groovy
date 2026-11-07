import dev.raulstechtips.jenkins.helpers.JenkinsBot

/**
 * Writes cfg.version into charts/Chart.yaml and pushes the commit to main as the Jenkins bot.
 *
 * In the tools container it sets the version with yq, commits only charts/Chart.yaml with the
 * message "chore: set chart version to VERSION", and pushes HEAD to main with the github-app
 * credentials, through the environment of that one process. The commit author is the bot, so the
 * main build of this commit is skipped. A failed commit or push, for example when main has moved,
 * does not fail the build: it logs a warning, because the next main build writes a newer version.
 *
 * @param cfg the loaded config. Reads version.
 * @throws error (fails the build) when yq fails
 */
void call(Map cfg) {
    container('tools') {
        withEnv(["V=${cfg.version}".toString(),
            "GIT_COMMITTER_NAME=${JenkinsBot.NAME}".toString(),
            "GIT_COMMITTER_EMAIL=${JenkinsBot.EMAIL}".toString()]) {
            sh 'yq -i \'.version = strenv(V)\' charts/Chart.yaml'
            String commit = "git add charts/Chart.yaml && git -c user.name='${JenkinsBot.NAME}' " +
                "-c user.email='${JenkinsBot.EMAIL}' commit -m \"chore: set chart version to \$V\""
            int status = sh(script: commit, returnStatus: true)
            if (status == 0) {
                withCredentials([usernamePassword(credentialsId: 'github-app',
                    usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                    status = sh(script: JenkinsBot.GIT_AUTH + 'git push origin HEAD:refs/heads/main',
                        returnStatus: true)
                }
            }
            if (status != 0) {
                echo("WARNING: Could not push chart version ${cfg.version} to main (exit code ${status}). " +
                    'The next main build writes a newer version.')
            }
        }
    }
}

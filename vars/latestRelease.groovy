import dev.raulstechtips.jenkins.helpers.JenkinsBot
import dev.raulstechtips.jenkins.helpers.SemVer

/**
 * Returns the highest X.Y.Z release tag on origin.
 *
 * It runs git ls-remote --tags origin with the github-app credentials, through the environment of
 * that one process, so the token is never stored. Tags with a prefix or a pre-release part are
 * ignored. It has no container block, so it runs in the container that calls it.
 *
 * @return the highest release tag, or null when the repository has no release
 * @throws error (fails the build) when git ls-remote fails
 */
String call() {
    String tags
    withCredentials([usernamePassword(credentialsId: 'github-app',
        usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        tags = sh(script: JenkinsBot.GIT_AUTH + 'git ls-remote --tags origin', returnStdout: true)
    }
    SemVer.highestReleaseTag(tags)
}

import dev.raulstechtips.jenkins.helpers.InfisicalReleases
import dev.raulstechtips.jenkins.helpers.JenkinsBot

/**
 * Creates the missing release/vX.Y.Z branches in raulstechtips/secrets-manager.
 *
 * It fetches the upstream Infisical v* tags and the fork release branches into a treeless clone,
 * and plans the work with InfisicalReleases.plan: the upstream vX.Y.Z tags newer than the newest
 * release branch, oldest first (mode next takes only the first). For each tag it runs
 * InfisicalReleases.BRANCH_SCRIPT, which cuts the branch from the tag, cherry-picks the commits of
 * the previous release branch after its tag, and pushes. The next tag starts from the branch just
 * created. Each push starts the build of that branch; this step does not wait for builds.
 *
 * It runs in the tools container. Only github.com/raulstechtips/ URLs get the github-app token,
 * through the environment of each git process; upstream is fetched without credentials.
 *
 * @param mode 'next' for one branch, or 'all' for every missing branch
 * @return the tags whose branches were created, oldest first
 * @throws error (fails the build) when no release branch exists, mode is unknown, a source branch
 *     has a merge commit, a cherry-pick does not apply, or a push fails. Branches created before the
 *     failure stay.
 */
List<String> call(String mode) {
    List<String> created = []
    container('tools') {
        withCredentials([usernamePassword(credentialsId: 'github-app',
            usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
            withEnv(['WORKDIR=.pipeline/secrets-manager',
                'REPO_URL=https://github.com/raulstechtips/secrets-manager.git',
                'UPSTREAM_URL=https://github.com/Infisical/infisical.git',
                'COMMITTER_NAME=' + JenkinsBot.INFISICAL_RELEASE_NAME,
                'COMMITTER_EMAIL=' + JenkinsBot.INFISICAL_RELEASE_EMAIL]) {
                sh(InfisicalReleases.FORK_AUTH + InfisicalReleases.FETCH_SCRIPT)
                List<String> tags = sh(script: 'git -C "$WORKDIR" tag -l "v*"', returnStdout: true).readLines()*.trim()
                List<String> branches = sh(script: 'git -C "$WORKDIR" for-each-ref ' +
                    '--format="%(refname:lstrip=3)" refs/remotes/origin/release/', returnStdout: true).readLines()*.trim()
                Map plan
                try {
                    plan = InfisicalReleases.plan(tags, branches, mode)
                } catch (IllegalArgumentException invalid) {
                    error(invalid.message)
                }
                if (plan.tags.isEmpty()) {
                    echo("No upstream release newer than ${plan.base}.")
                }
                String previous = plan.base
                plan.tags.each { String tag ->
                    echo("Creating release/${tag} from ${tag} with the commits of release/${previous}")
                    withEnv(['PREV=' + previous, 'TAG=' + tag,
                        'SOURCE=refs/remotes/origin/release/' + previous]) {
                        sh(InfisicalReleases.FORK_AUTH + InfisicalReleases.BRANCH_SCRIPT)
                    }
                    created << tag
                    previous = tag
                }
            }
        }
    }
    return created
}

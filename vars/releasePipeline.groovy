import com.cloudbees.groovy.cps.NonCPS
import dev.raulstechtips.jenkins.helpers.ChangelogUpdater
import dev.raulstechtips.jenkins.helpers.JenkinsBot
import dev.raulstechtips.jenkins.helpers.SemVer

/**
 * Creates the release commit and tag for cfg.releaseVersion, without pushing them.
 *
 * In the tools container it checks that the version is X.Y.Z, that CHANGELOG.md has entries in
 * [Unreleased], that the tag does not exist on origin, and that the version is higher than the
 * highest X.Y.Z tag on origin (0.0.0 when there is no release). The version file is not compared,
 * because on main it holds the last main build version. It then releases the changelog with
 * ChangelogUpdater, linking it to the highest X.Y.Z tag on origin, sets the new version in the
 * version file (charts/Chart.yaml or gradle.properties), commits
 * "release: VERSION" as the Jenkins bot, and creates an annotated tag. The github-app credentials
 * are used only for git ls-remote, through the environment of that one process, and are never
 * stored. finish pushes the result.
 *
 * @param cfg the loaded config. Reads releaseVersion and type, and sets version.
 * @throws error (fails the build) when a check fails or a git command fails
 * @throws IllegalArgumentException when origin is not a GitHub HTTPS or SSH URL
 */
void prepare(Map cfg) {
    container('tools') {
        String version = cfg.releaseVersion as String
        SemVer requested
        try {
            requested = SemVer.parse(version)
        } catch (IllegalArgumentException ignored) {
            error('RELEASE_VERSION must be X.Y.Z')
        }
        String path = cfg.type == 'cluster' ? 'charts/Chart.yaml' : 'gradle.properties'
        if (!fileExists('CHANGELOG.md')) {
            error('CHANGELOG.md is missing')
        }
        String changelog = readFile('CHANGELOG.md')
        ChangelogUpdater updater = new ChangelogUpdater()
        if (!updater.hasUnreleasedEntries(changelog)) {
            error('CHANGELOG.md must have entries in [Unreleased]')
        }
        String slug = repoSlug(sh(script: 'git remote get-url origin', returnStdout: true).trim())
        String tags
        withEnv(["V=${version}".toString()]) {
            withCredentials([usernamePassword(credentialsId: 'github-app',
                usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                // Rewrite HTTPS and SSH origins in this process only; never persist credentials.
                String auth = JenkinsBot.GIT_AUTH
                String existing = sh(script: auth + 'git ls-remote --tags origin "refs/tags/$V"',
                    returnStdout: true).trim()
                if (existing) {
                    error("Tag ${version} already exists".toString())
                }
                tags = sh(script: auth + 'git ls-remote --tags origin', returnStdout: true)
            }
        }
        String latest = SemVer.highestReleaseTag(tags)
        if (requested.compareTo(SemVer.parse(latest ?: '0.0.0')) <= 0) {
            error("RELEASE_VERSION must be greater than the latest release ${latest ?: '0.0.0'}".toString())
        }
        String date = sh(script: 'date -u +%Y-%m-%d', returnStdout: true).trim()
        writeFile(file: 'CHANGELOG.md',
            text: updater.release(changelog, version, date, slug, latest))
        withEnv(["V=${version}".toString(),
            'GIT_COMMITTER_NAME=raulstechtips-jenkins[bot]',
            'GIT_COMMITTER_EMAIL=raulstechtips-jenkins[bot]@users.noreply.github.com']) {
            if (cfg.type == 'cluster') {
                sh 'yq -i \'.version = strenv(V)\' charts/Chart.yaml'
            } else {
                writeFile(file: path, text: replaceGradleVersion(readFile(path), version))
            }
            sh "git add CHANGELOG.md ${path}".toString()
            sh '''git -c user.name='raulstechtips-jenkins[bot]' -c user.email='raulstechtips-jenkins[bot]@users.noreply.github.com' commit -m "release: $V"
git tag -a "$V" -m "$V"'''
        }
        cfg.version = version
    }
}

/**
 * Pushes the release commit to main and the release tag in one atomic push.
 *
 * It runs git push --atomic in the tools container with the github-app credentials, so main and the
 * tag move together or not at all.
 *
 * @param cfg the loaded config. Reads releaseVersion.
 * @throws error (fails the build) when the push fails, for example when main has moved
 * @throws IllegalArgumentException when origin is not a GitHub HTTPS or SSH URL
 */
void finish(Map cfg) {
    container('tools') {
        String slug = repoSlug(sh(script: 'git remote get-url origin', returnStdout: true).trim())
        withEnv(["REPO_SLUG=${slug}".toString(), "V=${cfg.releaseVersion}".toString()]) {
            withCredentials([usernamePassword(credentialsId: 'github-app',
                usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                sh '''set +x
git push --atomic "https://x-access-token:${GH_TOKEN}@github.com/${REPO_SLUG}.git" "HEAD:refs/heads/main" "refs/tags/${V}"'''
            }
        }
    }
}

@NonCPS
private String repoSlug(String remote) {
    def match = remote =~ /^(?:https:\/\/github[.]com\/|git@github[.]com:|ssh:\/\/git@github[.]com\/)([A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+?)(?:[.]git)?\/?$/
    if (!match.matches()) {
        throw new IllegalArgumentException('Origin must be a GitHub HTTPS or SSH repository URL')
    }
    match.group(1)
}

@NonCPS
private String replaceGradleVersion(String contents, String version) {
    contents.replaceFirst(/(?m)^version=[^\r\n]*/, 'version=' + version)
}

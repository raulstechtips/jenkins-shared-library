import com.cloudbees.groovy.cps.NonCPS
import dev.raulstechtips.jenkins.helpers.ChangelogUpdater
import dev.raulstechtips.jenkins.helpers.JenkinsBot
import dev.raulstechtips.jenkins.helpers.SemVer
import dev.raulstechtips.jenkins.helpers.ShellQuote

/**
 * Prepares the release files for cfg.releaseVersion without committing or tagging.
 *
 * In the tools container it checks that the version is X.Y.Z, that CHANGELOG.md has entries in
 * [Unreleased] when it exists, that the tag does not exist on origin, and that the version is higher than the
 * highest X.Y.Z tag on origin (0.0.0 when there is no release). The version file is not compared,
 * because on main it holds the last main build version. It then releases the changelog with
 * ChangelogUpdater, linking it to the highest X.Y.Z tag on origin, sets the new version in the
 * applicable version files (charts/Chart.yaml and, for Java, gradle.properties), and the configured
 * chart image tag. A missing changelog is bootstrapped with an empty dated release.
 * The github-app credentials
 * are used only for git ls-remote, through the environment of that one process, and are never
 * stored. publish commits, tags and pushes the result after the artifact checks.
 *
 * @param cfg the loaded config. Reads releaseVersion, type, chartPresent and helm-image-tag-path,
 *     and sets version and releaseFiles.
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
        boolean hasChangelog = fileExists('CHANGELOG.md')
        String changelog = hasChangelog ? readFile('CHANGELOG.md') : ChangelogUpdater.TEMPLATE
        ChangelogUpdater updater = new ChangelogUpdater()
        if (hasChangelog && !updater.hasUnreleasedEntries(changelog)) {
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
        List<String> releaseFiles = ['CHANGELOG.md']
        withEnv(["V=${version}".toString()]) {
            if (cfg.chartPresent) {
                sh 'yq -i \'.version = strenv(V)\' charts/Chart.yaml'
                releaseFiles.add('charts/Chart.yaml')
            }
            if (cfg.type == 'java') {
                writeFile(file: 'gradle.properties',
                    text: replaceGradleVersion(readFile('gradle.properties'), version))
                releaseFiles.add('gradle.properties')
            }
            if (cfg['helm-image-tag-path']) {
                withEnv(['YQ_EXPRESSION=' + cfg['helm-image-tag-path'] + ' = strenv(V)']) {
                    sh 'yq -i "$YQ_EXPRESSION" charts/values.yaml'
                }
                releaseFiles.add('charts/values.yaml')
            }
        }
        cfg.releaseFiles = releaseFiles
        cfg.version = version
    }
}

/**
 * Commits the prepared release files, creates an annotated tag and pushes both atomically.
 *
 * It runs git push --atomic in the tools container with the github-app credentials, so main and the
 * tag move together or not at all.
 *
 * @param cfg the loaded config. Reads releaseVersion and releaseFiles from prepare.
 * @throws error (fails the build) when the push fails, for example when main has moved
 * @throws IllegalArgumentException when origin is not a GitHub HTTPS or SSH URL
 */
void publish(Map cfg) {
    container('tools') {
        String slug = repoSlug(sh(script: 'git remote get-url origin', returnStdout: true).trim())
        withEnv(["REPO_SLUG=${slug}".toString(), "V=${cfg.releaseVersion}".toString(),
            "GIT_COMMITTER_NAME=${JenkinsBot.NAME}".toString(),
            "GIT_COMMITTER_EMAIL=${JenkinsBot.EMAIL}".toString()]) {
            sh 'git add -- ' + cfg.releaseFiles.collect { ShellQuote.quote(it) }.join(' ')
            sh '''git -c user.name='raulstechtips-jenkins[bot]' -c user.email='raulstechtips-jenkins[bot]@users.noreply.github.com' commit -m "release: $V"
git tag -a "$V" -m "$V"'''
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

import com.cloudbees.groovy.cps.NonCPS
import dev.raulstechtips.jenkins.release.ChangelogUpdater
import dev.raulstechtips.jenkins.version.SemVer
import dev.raulstechtips.jenkins.version.VersionCalculator

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
        String contents = readFile(path)
        String current = cfg.type == 'cluster' ? readYaml(text: contents)?.version as String :
            VersionCalculator.gradleVersion(contents)
        SemVer previous
        try {
            previous = SemVer.parse(current)
        } catch (IllegalArgumentException ignored) {
            error("Version file ${path} must contain X.Y.Z".toString())
        }
        if (requested.compareTo(previous) <= 0) {
            error('RELEASE_VERSION must be greater than the version file')
        }
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
                String auth = '''set +x
export GIT_CONFIG_COUNT=3
export GIT_CONFIG_KEY_0="url.https://x-access-token:${GH_TOKEN}@github.com/.insteadOf"
export GIT_CONFIG_VALUE_0=https://github.com/
export GIT_CONFIG_KEY_1="$GIT_CONFIG_KEY_0"
export GIT_CONFIG_VALUE_1=git@github.com:
export GIT_CONFIG_KEY_2="$GIT_CONFIG_KEY_0"
export GIT_CONFIG_VALUE_2=ssh://git@github.com/
'''
                String existing = sh(script: auth + 'git ls-remote --tags origin "refs/tags/$V"',
                    returnStdout: true).trim()
                if (existing) {
                    error("Tag ${version} already exists".toString())
                }
                tags = sh(script: auth + 'git ls-remote --tags origin', returnStdout: true)
            }
        }
        String date = sh(script: 'date -u +%Y-%m-%d', returnStdout: true).trim()
        writeFile(file: 'CHANGELOG.md',
            text: updater.release(changelog, version, date, slug, highestReleaseTag(tags)))
        withEnv(["V=${version}".toString(),
            'GIT_COMMITTER_NAME=raulstechtips-jenkins[bot]',
            'GIT_COMMITTER_EMAIL=raulstechtips-jenkins[bot]@users.noreply.github.com']) {
            if (cfg.type == 'cluster') {
                sh 'yq -i \'.version = strenv(V)\' charts/Chart.yaml'
            } else {
                writeFile(file: path, text: replaceGradleVersion(contents, version))
            }
            sh "git add CHANGELOG.md ${path}".toString()
            sh '''git -c user.name='raulstechtips-jenkins[bot]' -c user.email='raulstechtips-jenkins[bot]@users.noreply.github.com' commit -m "release: $V"
git tag -a "$V" -m "$V"'''
        }
        cfg.version = version
    }
}

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
private String highestReleaseTag(String output) {
    String highest = null
    for (String line : output.readLines()) {
        def match = line =~ /^\S+\s+refs\/tags\/([0-9]+[.][0-9]+[.][0-9]+)$/
        if (match.matches()) {
            String tag = match.group(1)
            if (highest == null || SemVer.parse(tag).compareTo(SemVer.parse(highest)) > 0) {
                highest = tag
            }
        }
    }
    highest
}

@NonCPS
private String replaceGradleVersion(String contents, String version) {
    contents.replaceFirst(/(?m)^version=[^\r\n]*/, 'version=' + version)
}

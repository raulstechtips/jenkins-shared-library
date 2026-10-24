package dev.raulstechtips.jenkins.version

import com.cloudbees.groovy.cps.NonCPS

class VersionCalculator implements Serializable {
    @NonCPS
    String calculate(String version, String path, Map environment, Map revision, String releaseVersion = null) {
        if (releaseVersion) {
            return releaseVersion
        }
        SemVer parsed
        try {
            parsed = SemVer.parse(version)
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("Version file ${path} must contain X.Y.Z".toString())
        }
        String base = parsed.bumpPatch().toString()
        boolean pullRequest = !!environment.CHANGE_ID
        String name = (pullRequest ? environment.CHANGE_BRANCH : environment.BRANCH_NAME) as String
        if (!pullRequest && name == 'main') {
            return "${base}-main-${revision.head.take(7)}-${environment.BUILD_NUMBER}".toString()
        }
        def branch = name =~ /^(feature|bugfix|hotfix|release)\/([A-Z][A-Z0-9]*-[0-9]+)(-.*)?$/
        if (!branch.matches()) {
            throw new IllegalArgumentException(
                "Branch \"${name}\" must be <type>/<KEY>-<number>[-text]. The type must be feature, bugfix, hotfix or release. Example: feature/DEVSECOPS-54-argocd-chart.".toString())
        }
        String suffix = "${branch.group(1)}-${branch.group(2).toLowerCase(Locale.ROOT)}".toString()
        if (pullRequest) {
            if (!revision.head || !revision.base) {
                throw new IllegalArgumentException('Cannot read the PR head and base commits from the build.')
            }
            return "${base}-${suffix}-${revision.head.take(7)}-${revision.base.take(7)}-${environment.BUILD_NUMBER}".toString()
        }
        "${base}-${suffix}-${revision.head.take(7)}-${environment.BUILD_NUMBER}".toString()
    }

    @NonCPS
    static String gradleVersion(String contents) {
        def line = contents =~ /(?m)^version=([^\r\n]*)\r?$/
        line.find() ? line.group(1) : null
    }
}

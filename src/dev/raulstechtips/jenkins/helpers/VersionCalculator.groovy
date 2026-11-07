package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

class VersionCalculator implements Serializable {
    /**
     * Calculates the build version from the latest release.
     *
     * The base is the latest release with the minor number increased by one and the patch number
     * set to 0, for example 0.0.0 becomes 0.1.0 and 1.2.3 becomes 1.3.0. A main push returns
     * BASE-main-HEAD7-BUILD, a branch push BASE-TYPE-key-HEAD7-BUILD and a pull request
     * BASE-TYPE-key-HEAD7-BASE7-BUILD.
     *
     * @param latestRelease the highest X.Y.Z release tag, or 0.0.0 when there is no release
     * @param environment BRANCH_NAME, CHANGE_ID, CHANGE_BRANCH and BUILD_NUMBER
     * @param revision head and, for a pull request, base commits
     * @param releaseVersion the requested release version, returned unchanged when set
     * @return the version string
     * @throws IllegalArgumentException when latestRelease is not X.Y.Z, the branch name is invalid,
     *     or the pull request commits are missing
     */
    @NonCPS
    String calculate(String latestRelease, Map environment, Map revision, String releaseVersion = null) {
        if (releaseVersion) {
            return releaseVersion
        }
        SemVer parsed
        try {
            parsed = SemVer.parse(latestRelease)
        } catch (IllegalArgumentException ignored) {
            throw new IllegalArgumentException("Latest release ${latestRelease} must be X.Y.Z".toString())
        }
        String base = parsed.bumpMinor().toString()
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
}

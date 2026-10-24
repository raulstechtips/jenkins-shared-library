package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/**
 * Computes the build version from the version file, the branch and the commits.
 */
class VersionCalculator implements Serializable {
    /**
     * Returns the version for this build.
     *
     * A release run returns releaseVersion unchanged. Otherwise the base is the version file's
     * X.Y.Z with the patch number increased by one. A push to main gives base-main-HEAD-BUILD. A
     * pull request gives base-TYPE-KEY-HEAD-BASE-BUILD, using CHANGE_BRANCH. A push to another
     * branch gives base-TYPE-KEY-HEAD-BUILD. TYPE and KEY come from the branch name, KEY in lower
     * case. HEAD and BASE are the first 7 characters of the commits, and BUILD is the build number.
     *
     * @param version the version read from the version file
     * @param path the version file path, used in the error message
     * @param environment the build values BRANCH_NAME, CHANGE_ID, CHANGE_BRANCH and BUILD_NUMBER
     * @param revision the head commit, plus the base commit for a pull request
     * @param releaseVersion the release version, or null for a normal build
     * @return the version string
     * @throws IllegalArgumentException when version is not X.Y.Z, when the branch name is not
     *     TYPE/KEY-NUMBER[-text] with TYPE feature, bugfix, hotfix or release, or when a pull request
     *     has no head or base commit
     */
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

    /**
     * Reads the version from gradle.properties text.
     *
     * @param contents the gradle.properties content
     * @return the value of the first version= line, or null when there is none
     */
    @NonCPS
    static String gradleVersion(String contents) {
        def line = contents =~ /(?m)^version=([^\r\n]*)\r?$/
        line.find() ? line.group(1) : null
    }
}

package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/**
 * Decides which declared stages run from one immutable table shared by all pipeline types.
 * Preconditions read the current config, so image scans use the digest and upstream images
 * available when the stage is reached.
 */
class PipelineSteps implements Serializable {
    static final Map<String, Map> RULES = Collections.unmodifiableMap([
        'render': Collections.unmodifiableMap([needs: 'chartPresent']),
        'early-checks': Collections.unmodifiableMap([:]),
        'trivy-secret-scan': Collections.unmodifiableMap([flag: 'trivy-scan']),
        'trivy-dockerfile-scan': Collections.unmodifiableMap([flag: 'trivy-scan']),
        'trivy-scan': Collections.unmodifiableMap([flag: 'trivy-scan']),
        'opengrep-scan': Collections.unmodifiableMap([flag: 'opengrep-scan']),
        'build': Collections.unmodifiableMap([flag: 'build']),
        'unit-test': Collections.unmodifiableMap([flag: 'unit-test']),
        'helm-test': Collections.unmodifiableMap([flag: 'helm-test']),
        'build-docker-image': Collections.unmodifiableMap([flag: 'build-docker-image']),
        'trivy-image-scan': Collections.unmodifiableMap([
            flag: 'trivy-scan', needs: 'imageOrUpstreamImages']),
        'helm-package-upload': Collections.unmodifiableMap([flag: 'helm-package-upload']),
        'trivy-chart-scan': Collections.unmodifiableMap([
            flag: 'trivy-scan', alsoFlag: 'helm-package-upload']),
        'helm-clean-install': Collections.unmodifiableMap([
            flag: 'helm-clean-install', on: Collections.unmodifiableList(['branch', 'pr']),
            needs: 'nonMainBranch']),
        'helm-install': Collections.unmodifiableMap([
            flag: 'helm-install', unlessFlag: 'helm-clean-install',
            on: Collections.unmodifiableList(['branch', 'pr']), needs: 'nonMainBranch']),
        'argo-deploy': Collections.unmodifiableMap([
            flag: 'argo-deploy', on: Collections.unmodifiableList(['main', 'release'])]),
        'release': Collections.unmodifiableMap([on: Collections.unmodifiableList(['release'])])
    ])

    /**
     * Classifies a build, with release taking precedence over PR, main and other branches.
     *
     * @param branchName Jenkins BRANCH_NAME
     * @param changeId Jenkins CHANGE_ID, empty or null when this is not a PR
     * @param releaseVersion requested release version, empty or null for a normal build
     * @return release, pr, main or branch
     */
    @NonCPS
    static String buildKind(String branchName, String changeId, String releaseVersion) {
        if (releaseVersion) {
            return 'release'
        }
        if (changeId) {
            return 'pr'
        }
        return branchName == 'main' ? 'main' : 'branch'
    }

    /**
     * Applies a step's flags, build kinds and named precondition to the current config.
     * skipAll disables every known step. nonMainBranch retains the original BRANCH_NAME
     * check even when a PR build reports main as its branch name.
     *
     * @param cfg loaded config with buildKind and nonMainBranch set by the pipeline
     * @param step id in RULES
     * @return whether the step can run with the values currently in cfg
     * @throws IllegalArgumentException when step is not in RULES
     */
    @NonCPS
    static boolean shouldRun(Map cfg, String step) {
        Map rule = RULES[step]
        if (rule == null) {
            throw new IllegalArgumentException("Unknown pipeline step '${step}'")
        }
        if (cfg.skipAll) {
            return false
        }
        Map flags = cfg['pipeline-steps']
        if (rule.flag && !flags[rule.flag]) {
            return false
        }
        if (rule.alsoFlag && !flags[rule.alsoFlag]) {
            return false
        }
        if (rule.unlessFlag && flags[rule.unlessFlag]) {
            return false
        }
        if (rule.on && !rule.on.contains(cfg.buildKind)) {
            return false
        }
        switch (rule.needs) {
            case 'imageOrUpstreamImages':
                return (cfg.digest || cfg.upstreamImages) as boolean
            case 'chartPresent':
                return cfg.chartPresent as boolean
            case 'nonMainBranch':
                return cfg.nonMainBranch as boolean
            default:
                return true
        }
    }

    /**
     * Filters the supplied step ids without changing their order or the config.
     * This is a snapshot: later stages can produce inputs that enable another step.
     *
     * @param cfg config to evaluate now
     * @param steps declared step ids in pipeline order
     * @return enabled ids in the supplied order
     * @throws IllegalArgumentException when an id is not in RULES
     */
    @NonCPS
    static List<String> plan(Map cfg, List<String> steps) {
        List<String> result = []
        for (String step : steps) {
            if (shouldRun(cfg, step)) {
                result.add(step)
            }
        }
        return result
    }
}

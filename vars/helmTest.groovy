/**
 * Runs the chart checks: helmDependencyBuild, helmLint, helmUnitTest and kubeconformCheck, in that
 * order.
 *
 * Each step uses its own container. It does nothing when the repo has no chart.
 *
 * @param cfg the loaded config. Reads chartPresent.
 * @throws error (fails the build) when one of the checks fails
 */
def call(Map cfg) {
    if (!cfg.chartPresent) {
        return
    }
    helmDependencyBuild(cfg)
    helmLint(cfg)
    helmUnitTest(cfg)
    kubeconformCheck(cfg)
}

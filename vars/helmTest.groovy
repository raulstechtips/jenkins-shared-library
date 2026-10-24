def call(Map cfg) {
    if (!cfg.chartPresent) {
        return
    }
    helmDependencyBuild(cfg)
    helmLint(cfg)
    helmUnitTest(cfg)
    kubeconformCheck(cfg)
}

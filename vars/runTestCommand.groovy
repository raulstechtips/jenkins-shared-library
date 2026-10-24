def call(Map cfg, String containerName) {
    container(containerName) {
        sh cfg['test-command']
    }
}

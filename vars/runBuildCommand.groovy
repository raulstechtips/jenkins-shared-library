def call(Map cfg, String containerName) {
    container(containerName) {
        sh cfg['build-command']
    }
}

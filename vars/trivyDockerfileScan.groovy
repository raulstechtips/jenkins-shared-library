def call(Map cfg) {
    List paths = []
    for (String glob : ['**/Dockerfile', '**/*.Dockerfile', '**/Dockerfile.*']) {
        findFiles(glob: glob, excludes: '.pipeline/**,.git/**').each { file ->
            String path = file.path
            if (!path.startsWith('.pipeline/') && !path.startsWith('.git/') && !paths.contains(path)) {
                paths.add(path)
            }
        }
    }
    if (paths.isEmpty()) {
        echo 'No Dockerfile found'
        return
    }
    paths.each { String path -> trivyConfigScan(path, path.replace('/', '-'), cfg) }
}

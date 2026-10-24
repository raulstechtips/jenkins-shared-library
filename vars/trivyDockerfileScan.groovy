/**
 * Runs trivyConfigScan on every Dockerfile in the repo.
 *
 * It finds files named Dockerfile, NAME.Dockerfile or Dockerfile.NAME outside .git and .pipeline,
 * and uses each path, with / replaced by -, as the label. When there is none it logs that and
 * returns.
 *
 * @param cfg the loaded config. Reads trivy-fail-on.
 * @throws error (fails the build) when a scan fails or a finding matches trivy-fail-on
 */
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

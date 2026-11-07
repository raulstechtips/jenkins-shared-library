/**
 * Commits the project's dev Argo CD apps at this build's chart version straight to cluster-config
 * main.
 *
 * The targets are the names in argo-deploy-targets. When that list is empty, the only target is
 * project-name. A target NAME is the file argo/dev/NAME.yaml.
 *
 * In the tools container, with the github-app credentials, it clones cluster-config main into
 * .pipeline/cluster-config. First it checks every target file: the file must exist and have a
 * source whose chart is the name in charts/Chart.yaml. When one file fails, the build fails before
 * any commit. A target whose file already has cfg.version is skipped. For each other target, in
 * list order, it sets that source's targetRevision to cfg.version and commits only that file as the
 * Jenkins bot, with the message "deploy(NAME): dev VERSION". Then it pushes all commits to main in
 * one push. When main moved and the push is rejected, it fetches main again, makes the commits
 * again and pushes, up to 3 attempts. No pull request is opened.
 *
 * @param cfg the loaded config. Reads project-name, argo-deploy-targets and version.
 * @throws error (fails the build) when a target file is missing, when no source in a target file
 *     uses the chart, when an edit is not exactly one changed line, when the push fails 3 times, or
 *     when a git or yq command fails
 */
def call(Map cfg) {
    String chart = readYaml(file: 'charts/Chart.yaml').name
    String version = cfg.version
    List<String> targets = cfg['argo-deploy-targets'] ?: [cfg['project-name']]
    container('tools') {
        withEnv(["CHART=${chart}", "VERSION=${version}"]) {
            sh 'mkdir -p .pipeline\nrm -rf .pipeline/cluster-config'
            withCredentials([usernamePassword(credentialsId: 'github-app',
                usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                sh '''set +x
set -eu
git clone --depth 1 --branch main "https://x-access-token:$GH_TOKEN@github.com/raulstechtips/cluster-config.git" .pipeline/cluster-config
git -C .pipeline/cluster-config remote set-url origin https://github.com/raulstechtips/cluster-config.git
'''
            }
            dir('.pipeline/cluster-config') {
                Map<String, String> current = [:]
                for (String target : targets) {
                    String file = "argo/dev/${target}.yaml"
                    if (!fileExists(file)) {
                        error("${file} is missing")
                    }
                    String revision
                    withEnv(["FILE=${file}"]) {
                        revision = sh(script: '''yq '(.spec.sources[] | select(.chart == strenv(CHART))).targetRevision' "$FILE"''',
                            returnStdout: true).trim()
                    }
                    if (!revision) {
                        error("No source with chart ${chart} in ${file}")
                    }
                    current[target] = revision
                }
                List<String> pending = []
                for (String target : targets) {
                    if (current[target] == version) {
                        echo "argo/dev/${target}.yaml is already at ${version}. No commit."
                    } else {
                        pending.add(target)
                    }
                }
                if (pending.empty) {
                    return
                }
                sh '''git config user.name 'raulstechtips-jenkins[bot]'
git config user.email 'raulstechtips-jenkins[bot]@users.noreply.github.com'
'''
                for (int attempt = 1; attempt <= 3; attempt++) {
                    if (attempt > 1) {
                        echo "cluster-config main moved. Attempt ${attempt} of 3."
                        withCredentials([usernamePassword(credentialsId: 'github-app',
                            usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                            sh '''set +x
set -eu
git fetch --depth 1 "https://x-access-token:$GH_TOKEN@github.com/raulstechtips/cluster-config.git" main
git reset --hard FETCH_HEAD
'''
                        }
                    }
                    for (String target : pending) {
                        String file = "argo/dev/${target}.yaml"
                        withEnv(["FILE=${file}", "TITLE=deploy(${target}): dev ${version}"]) {
                            commitTarget(file)
                        }
                    }
                    int status
                    withCredentials([usernamePassword(credentialsId: 'github-app',
                        usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
                        status = sh(script: '''set +x
git push "https://x-access-token:$GH_TOKEN@github.com/raulstechtips/cluster-config.git" HEAD:refs/heads/main
''', returnStatus: true)
                    }
                    if (status == 0) {
                        echo "Pushed ${pending.size()} deploy commit(s) for ${version} to cluster-config main."
                        return
                    }
                }
                error("Could not push the deploy commits for ${version} to cluster-config main after 3 attempts.")
            }
        }
    }
}

/**
 * Sets the chart source's targetRevision in one target file and commits only that file.
 *
 * Runs in the cluster-config clone with FILE, TITLE, CHART and VERSION in the environment.
 *
 * @param file the target file, the same value as FILE
 * @throws error (fails the build) when the edit is not exactly one changed line in file
 */
void commitTarget(String file) {
    sh '''yq -i '(.spec.sources[] | select(.chart == strenv(CHART))).targetRevision = strenv(VERSION)' "$FILE"'''
    String numstat = sh(script: 'git diff --numstat', returnStdout: true)
    String expected = "1\t1\t${file}"
    if (numstat != expected && numstat != expected + '\n') {
        error("Expected exactly one targetRevision change in ${file}; got: ${numstat}")
    }
    sh 'git commit -m "$TITLE" -m "Jenkins build: $BUILD_URL" -- "$FILE"'
}

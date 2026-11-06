/**
 * Opens pull requests in cluster-config that move the project's dev Argo CD apps to this build's
 * chart version.
 *
 * The targets are the names in argo-deploy-targets. When that list is empty, the only target is
 * project-name. A target NAME is the file argo/dev/NAME.yaml.
 *
 * In the tools container, with the github-app credentials, it clones cluster-config main into
 * .pipeline/cluster-config. First it checks every target file: the file must exist and have a
 * source whose chart is the name in charts/Chart.yaml. When one file fails, the build fails before
 * any PR is opened. Then, for each target in list order, it sets that source's targetRevision to
 * cfg.version on the branch argo-deploy/NAME-dev-VERSION, made from main, and opens one PR. It
 * opens no PR for a target when its file already has that version or an open PR exists for its
 * branch, and it reuses the branch when it already exists on the remote. Each PR is merged by hand.
 *
 * @param cfg the loaded config. Reads project-name, argo-deploy-targets and version.
 * @throws error (fails the build) when a target file is missing, when no source in a target file
 *     uses the chart, when an edit is not exactly one changed line, or when a git, yq, curl or jq
 *     command fails
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
                for (String target : targets) {
                    String file = "argo/dev/${target}.yaml"
                    if (current[target] == version) {
                        echo "${file} is already at ${version}. No PR."
                        continue
                    }
                    withEnv(["FILE=${file}", "BRANCH=argo-deploy/${target}-dev-${version}",
                        "TITLE=deploy(${target}): dev ${version}"]) {
                        deployTarget(file)
                    }
                }
            }
        }
    }
}

/**
 * Opens the PR for one target file, unless an open PR for its branch exists.
 *
 * Runs in the cluster-config clone with FILE, BRANCH, TITLE, CHART and VERSION in the environment.
 * A new branch is made from main, so each PR changes only its own file.
 *
 * @param file the target file, the same value as FILE
 */
void deployTarget(String file) {
    String existingPr
    withCredentials([usernamePassword(credentialsId: 'github-app',
        usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        existingPr = sh(script: '''set +x
set -eu
response=$(curl -fsS -H "Authorization: Bearer $GH_TOKEN" "https://api.github.com/repos/raulstechtips/cluster-config/pulls?head=raulstechtips:$BRANCH&state=open")
printf '%s' "$response" | jq -r 'if type == "array" then (.[0].html_url // empty) else error("Expected PR array") end'
''', returnStdout: true).trim()
    }
    if (existingPr) {
        echo existingPr
        return
    }
    String remoteBranch = sh(script: 'git ls-remote --heads origin "$BRANCH"',
        returnStdout: true).trim()
    if (remoteBranch) {
        sh '''set -eu
git fetch --depth 1 origin "$BRANCH"
git checkout -B "$BRANCH" FETCH_HEAD
'''
    } else {
        sh 'git checkout -b "$BRANCH" main'
        sh '''yq -i '(.spec.sources[] | select(.chart == strenv(CHART))).targetRevision = strenv(VERSION)' "$FILE"'''
        String numstat = sh(script: 'git diff --numstat', returnStdout: true)
        String expected = "1\t1\t${file}"
        if (numstat != expected && numstat != expected + '\n') {
            error("Expected exactly one targetRevision change in ${file}; got: ${numstat}")
        }
        sh '''set -eu
git config user.name 'raulstechtips-jenkins[bot]'
git config user.email 'raulstechtips-jenkins[bot]@users.noreply.github.com'
git commit -m "$TITLE" -- "$FILE"
'''
        withCredentials([usernamePassword(credentialsId: 'github-app',
            usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
            sh '''set +x
set -eu
git push "https://x-access-token:$GH_TOKEN@github.com/raulstechtips/cluster-config.git" "HEAD:refs/heads/$BRANCH"
'''
        }
    }
    String prUrl
    withCredentials([usernamePassword(credentialsId: 'github-app',
        usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN')]) {
        prUrl = sh(script: '''set +x
set -eu
payload=$(jq -n --arg title "$TITLE" --arg head "$BRANCH" --arg body "Opened by Jenkins build $BUILD_URL. Merge by hand." '{title: $title, head: $head, base: "main", body: $body}')
response=$(curl -fsS -X POST https://api.github.com/repos/raulstechtips/cluster-config/pulls -H "Authorization: Bearer $GH_TOKEN" -H 'Content-Type: application/json' --data "$payload")
printf '%s' "$response" | jq -er '.html_url | select(type == "string" and length > 0)'
''', returnStdout: true).trim()
    }
    echo prUrl
}

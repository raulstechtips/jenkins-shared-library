/**
 * Opens a pull request in cluster-config that moves the project's dev Argo CD app to this build's
 * chart version.
 *
 * In the tools container, with the github-app credentials, it clones cluster-config main into
 * .pipeline/cluster-config. In argo/dev/PROJECT.yaml it finds the source whose chart is the name in
 * charts/Chart.yaml and sets its targetRevision to cfg.version on the branch
 * argo-deploy/PROJECT-dev-VERSION. It opens no PR when the file already has that version or an open
 * PR exists for the branch, and it reuses the branch when it already exists on the remote. The PR
 * is merged by hand.
 *
 * @param cfg the loaded config. Reads project-name and version.
 * @throws error (fails the build) when the app file is missing, when no source uses the chart, when
 *     the edit is not exactly one changed line, or when a git, yq, curl or jq command fails
 */
def call(Map cfg) {
    String chart = readYaml(file: 'charts/Chart.yaml').name
    String version = cfg.version
    String project = cfg['project-name']
    String file = "argo/dev/${project}.yaml"
    String branch = "argo-deploy/${project}-dev-${version}"
    container('tools') {
        withEnv(["CHART=${chart}", "VERSION=${version}", "FILE=${file}",
            "BRANCH=${branch}", "TITLE=deploy(${project}): dev ${version}"]) {
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
                if (!fileExists(file)) {
                    error("${file} is missing")
                }
                String current = sh(script: '''yq '(.spec.sources[] | select(.chart == strenv(CHART))).targetRevision' "$FILE"''',
                    returnStdout: true).trim()
                if (!current) {
                    error("No source with chart ${chart} in ${file}")
                }
                if (current == version) {
                    echo "Already at ${version}. No PR."
                    return
                }
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
                    sh 'git checkout -b "$BRANCH"'
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
        }
    }
}

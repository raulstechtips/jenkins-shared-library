/**
 * Runs a release-only declarative pipeline for a repository whose checks run somewhere else.
 *
 * It reads no project-pipeline-config.yaml and runs no build, test or scan. With RELEASE_VERSION
 * set (X.Y.Z, main only) the Release stage checks out the repo on a pod with only the tools
 * container and runs releasePipeline.prepare and releasePipeline.publish: it releases CHANGELOG.md,
 * commits "release: X.Y.Z" and pushes main and the tag X.Y.Z. No version file is changed. Without
 * RELEASE_VERSION (every push and pull request build) the Release stage is skipped before an agent
 * starts, so the build ends green and does nothing. The build times out after 15 minutes and builds
 * do not run concurrently.
 *
 * @throws error (fails the build) when RELEASE_VERSION is set on a branch other than main or on a
 *     pull request. releasePipeline raises its own errors.
 */
def call() {
    Map cfg = [type: 'release', chartPresent: false, 'pipeline-steps': [:]]
    cfg.releaseVersion = params.RELEASE_VERSION ?: ''
    if (cfg.releaseVersion && (env.BRANCH_NAME != 'main' || env.CHANGE_ID)) {
        error('RELEASE_VERSION is only allowed on the main job.')
    }
    cfg.podYaml = agentPodYaml(cfg)
    if (!cfg.releaseVersion) {
        currentBuild.description = 'No RELEASE_VERSION: nothing to release.'
    }

    pipeline {
        agent none
        options {
            skipDefaultCheckout()
            timeout(time: 15, unit: 'MINUTES')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20'))
        }
        parameters {
            string(name: 'RELEASE_VERSION', defaultValue: '',
                description: 'X.Y.Z. Only on main. Empty = no release.')
        }
        stages {
            stage('Release') {
                when {
                    beforeAgent true
                    expression { cfg.releaseVersion as boolean }
                }
                agent {
                    kubernetes {
                        inheritFrom 'generic'
                        yaml cfg.podYaml
                        yamlMergeStrategy merge()
                    }
                }
                steps {
                    script {
                        checkout(scm)
                        currentBuild.displayName = cfg.releaseVersion
                        releasePipeline.prepare(cfg)
                        releasePipeline.publish(cfg)
                    }
                }
            }
        }
    }
}

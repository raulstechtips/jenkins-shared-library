import dev.raulstechtips.jenkins.helpers.InfisicalReleases
import dev.raulstechtips.jenkins.helpers.RegistryCatalog

/**
 * Runs the image build for the Infisical fork on release/vX.Y.Z branches.
 *
 * It loads project-pipeline-config.yaml with the cluster shape and runs: checkout and version,
 * the apt unpin of the image Dockerfile (the infisical CLI pin stays), the Trivy Dockerfile scan,
 * the image build on the builder agent, and the Trivy image scan. The scans run when trivy-scan
 * is true and fail only on the severities in trivy-fail-on. The image build runs when
 * build-docker-image is true. There are no unit tests, Helm steps or deploys.
 *
 * The version is TAG-release-HEAD7-BUILD, from the branch name, the head commit and the build
 * number, for example v0.162.21-release-3e32d04-1. Any other branch, and every pull request,
 * fails at Checkout. The image build runs on the builder-large agent, whose Kaniko container
 * requests 10Gi of memory with a 12Gi limit, and the build times out after 180 minutes.
 *
 * @throws error (fails the build) when the branch is not release/vX.Y.Z. Each step raises its
 *     own errors.
 */
def call() {
    def cfg = loadProjectConfig(type: 'cluster')
    cfg.podYaml = agentPodYaml(cfg)
    // There is no chart, so the image scan has no third-party images to find.
    cfg.upstreamImages = []

    pipeline {
        agent none
        options {
            skipDefaultCheckout()
            timeout(time: 180, unit: 'MINUTES')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '20', artifactNumToKeepStr: '10'))
        }
        stages {
            stage('Pipeline') {
                agent {
                    kubernetes {
                        inheritFrom 'generic'
                        yaml cfg.podYaml
                        yamlMergeStrategy merge()
                    }
                }
                stages {
                    stage('Checkout') {
                        steps {
                            script {
                                Map scmVars = checkout(scm) ?: [:]
                                cfg.gitCommit = scmVars.GIT_COMMIT
                                cfg.gitUrl = scmVars.GIT_URL
                                currentBuild.description = cfg['project-description']
                                if (env.CHANGE_ID) {
                                    error('Pull requests are not built; push to a release/vX.Y.Z branch.')
                                }
                                try {
                                    cfg.version = InfisicalReleases.imageVersion(
                                        env.BRANCH_NAME, cfg.gitCommit, env.BUILD_NUMBER)
                                } catch (IllegalArgumentException invalid) {
                                    error(invalid.message)
                                }
                                currentBuild.displayName = cfg.version
                            }
                        }
                    }
                    stage('Unpin apt packages') {
                        steps {
                            unpinAptPackages(cfg, ['infisical'])
                        }
                    }
                    stage('Trivy Dockerfile scan') {
                        when {
                            expression { cfg['pipeline-steps']['trivy-scan'] }
                        }
                        steps {
                            trivyDockerfileScan(cfg)
                        }
                    }
                    stage('Prepare image context') {
                        when {
                            expression { cfg['pipeline-steps']['build-docker-image'] }
                        }
                        steps {
                            stash name: 'image-context', includes: '**/*',
                                excludes: '.git,.git/**,.pipeline/**', useDefaultExcludes: false
                        }
                    }
                    stage('Build image') {
                        when {
                            beforeAgent true
                            expression { cfg['pipeline-steps']['build-docker-image'] }
                        }
                        agent {
                            kubernetes {
                                // The jenkins chart template gives Kaniko a 10Gi request and a 12Gi limit.
                                inheritFrom 'builder-large'
                            }
                        }
                        steps {
                            script {
                                cfg.digest = buildDockerImage(cfg)
                            }
                        }
                    }
                    stage('Trivy image scan') {
                        when {
                            expression { cfg['pipeline-steps']['trivy-scan'] && cfg.digest }
                        }
                        steps {
                            script {
                                Map registries = readYaml(text: libraryResource('dev/raulstechtips/jenkins/registries.yaml'))
                                Map target = new RegistryCatalog(registries).target(cfg['publish-targets'][0])
                                trivyImageScan(cfg, target['image-prefix'] + '/' + cfg['docker-image'].name +
                                    ':' + cfg.version + '@' + cfg.digest)
                            }
                        }
                    }
                }
                post {
                    always {
                        archiveArtifacts(artifacts: '.pipeline/reports/**', allowEmptyArchive: true)
                    }
                }
            }
        }
    }
}

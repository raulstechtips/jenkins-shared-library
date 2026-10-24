import dev.raulstechtips.jenkins.helpers.RegistryCatalog
import dev.raulstechtips.jenkins.helpers.SemVer
import dev.raulstechtips.jenkins.helpers.VersionCalculator

def call() {
    def cfg = loadProjectConfig(type: 'java')
    cfg.podYaml = agentPodYaml(cfg)
    cfg.upstreamImages = []
    cfg.releaseVersion = params.RELEASE_VERSION ?: ''
    if (cfg.releaseVersion && (env.BRANCH_NAME != 'main' || env.CHANGE_ID)) {
        error('RELEASE_VERSION is only allowed on the main job.')
    }

    pipeline {
        agent none
        options {
            skipDefaultCheckout()
            timeout(time: 60, unit: 'MINUTES')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeep: '20', artifactNumToKeep: '10'))
        }
        parameters {
            string(name: 'RELEASE_VERSION', defaultValue: '',
                description: 'X.Y.Z. Only on main. Empty = normal build.')
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
                            checkout scm
                            script {
                                cfg.skipAll = false
                                currentBuild.description = cfg['project-description']
                                if (!cfg.releaseVersion && env.BRANCH_NAME == 'main' && !env.CHANGE_ID) {
                                    String subject = sh(script: 'git log -1 --pretty=%s', returnStdout: true).trim()
                                    String version = VersionCalculator.gradleVersion(readFile('gradle.properties'))
                                    try {
                                        SemVer.parse(version)
                                    } catch (IllegalArgumentException ignored) {
                                        error('Version file gradle.properties must contain X.Y.Z')
                                    }
                                    if (subject == 'release: ' + version) {
                                        cfg.skipAll = true
                                        currentBuild.result = 'NOT_BUILT'
                                        currentBuild.description = "Release commit ${version}: already built by the release run."
                                    }
                                }
                                if (!cfg.skipAll) {
                                    cfg.version = computeVersion(cfg)
                                    currentBuild.displayName = cfg.version
                                    if (cfg.releaseVersion) {
                                        releasePipeline.prepare(cfg)
                                    }
                                }
                            }
                        }
                    }
                    stage('Render') {
                        when {
                            expression { !cfg.skipAll && cfg.chartPresent }
                        }
                        steps {
                            renderChart(cfg)
                            script {
                                if (cfg['pipeline-steps']['trivy-scan']) {
                                    cfg.upstreamImages = trivyImageScan.upstreamImages(cfg)
                                }
                            }
                        }
                    }
                    stage('Early checks') {
                        when {
                            expression { !cfg.skipAll }
                        }
                        parallel {
                            stage('Trivy scan') {
                                when {
                                    expression { !cfg.skipAll && cfg['pipeline-steps']['trivy-scan'] }
                                }
                                steps {
                                    trivySecretScan(cfg)
                                    trivyDockerfileScan(cfg)
                                }
                            }
                            stage('Opengrep scan') {
                                when {
                                    expression { !cfg.skipAll && cfg['pipeline-steps']['opengrep-scan'] }
                                }
                                steps {
                                    opengrepScan(cfg)
                                }
                            }
                        }
                    }
                    stage('Build') {
                        when {
                            expression { !cfg.skipAll && cfg['pipeline-steps']['build'] }
                        }
                        steps {
                            runBuildCommand(cfg, 'jdk')
                        }
                    }
                    stage('Unit test') {
                        when {
                            expression { !cfg.skipAll && cfg['pipeline-steps']['unit-test'] }
                        }
                        steps {
                            runTestCommand(cfg, 'jdk')
                        }
                    }
                    stage('Helm test') {
                        when {
                            expression { !cfg.skipAll && cfg['pipeline-steps']['helm-test'] }
                        }
                        steps {
                            helmTest(cfg)
                        }
                    }
                    stage('Build image') {
                        when {
                            beforeAgent true
                            expression { !cfg.skipAll && cfg['pipeline-steps']['build-docker-image'] }
                        }
                        agent {
                            kubernetes {
                                inheritFrom 'builder'
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
                            expression {
                                !cfg.skipAll && cfg['pipeline-steps']['trivy-scan'] &&
                                    (cfg.digest || cfg.upstreamImages)
                            }
                        }
                        steps {
                            script {
                                String builtImageRef = null
                                if (cfg.digest) {
                                    Map registries = readYaml(text: libraryResource('dev/raulstechtips/jenkins/registries.yaml'))
                                    Map target = new RegistryCatalog(registries).target(cfg['publish-targets'][0])
                                    builtImageRef = target['image-prefix'] + '/' + cfg['docker-image'].name +
                                        ':' + cfg.version + '@' + cfg.digest
                                }
                                trivyImageScan(cfg, builtImageRef)
                            }
                        }
                    }
                    stage('Package and upload chart') {
                        when {
                            expression { !cfg.skipAll && cfg['pipeline-steps']['helm-package-upload'] }
                        }
                        steps {
                            helmPackageUpload(cfg, cfg.digest)
                        }
                    }
                    stage('Trivy chart scan') {
                        when {
                            expression {
                                !cfg.skipAll && cfg['pipeline-steps']['trivy-scan'] &&
                                    cfg['pipeline-steps']['helm-package-upload']
                            }
                        }
                        steps {
                            trivyChartScan(cfg)
                        }
                    }
                    stage('Helm clean install') {
                        when {
                            beforeAgent true
                            expression {
                                !cfg.skipAll && cfg['pipeline-steps']['helm-clean-install'] &&
                                    env.BRANCH_NAME != 'main' && !cfg.releaseVersion
                            }
                        }
                        agent {
                            kubernetes {
                                inheritFrom 'deployer'
                            }
                        }
                        steps {
                            helmCleanInstall(cfg, cfg.digest)
                        }
                    }
                    stage('Helm install') {
                        when {
                            beforeAgent true
                            expression {
                                !cfg.skipAll && cfg['pipeline-steps']['helm-install'] &&
                                    !cfg['pipeline-steps']['helm-clean-install'] &&
                                    env.BRANCH_NAME != 'main' && !cfg.releaseVersion
                            }
                        }
                        agent {
                            kubernetes {
                                inheritFrom 'deployer'
                            }
                        }
                        steps {
                            helmInstall(cfg, cfg.digest)
                        }
                    }
                    stage('Argo deploy') {
                        when {
                            expression {
                                !cfg.skipAll && cfg['pipeline-steps']['argo-deploy'] &&
                                    ((env.BRANCH_NAME == 'main' && !env.CHANGE_ID) || cfg.releaseVersion)
                            }
                        }
                        steps {
                            argoDeploy(cfg)
                        }
                    }
                    stage('Release') {
                        when {
                            expression { !cfg.skipAll && cfg.releaseVersion }
                        }
                        steps {
                            script {
                                releasePipeline.finish(cfg)
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

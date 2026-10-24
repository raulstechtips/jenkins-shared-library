def call(boolean configError = false) {
    if (configError) {
        error('CONFIG-ERROR')
    }
    def cfg = [podYaml: 'POD-FROM-CONFIG']

    pipeline {
        agent none
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
                                cfg.flag = true
                            }
                        }
                    }
                    stage('Second') {
                        when {
                            expression { cfg.flag == true }
                        }
                        steps {
                            echo 'SECOND-SAW-CHECKOUT'
                        }
                    }
                    stage('Skipped builder') {
                        agent {
                            kubernetes {
                                inheritFrom 'builder'
                            }
                        }
                        when {
                            beforeAgent true
                            expression { false }
                        }
                        steps {
                            echo 'BUILDER-MUST-NOT-RUN'
                        }
                    }
                }
            }
        }
    }
}

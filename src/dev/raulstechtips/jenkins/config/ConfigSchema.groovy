package dev.raulstechtips.jenkins.config

import com.cloudbees.groovy.cps.NonCPS

class ConfigSchema implements Serializable {
    @NonCPS
    static Map tree(String type) {
        Map flags = [:]
        ['helm-test', 'trivy-scan', 'opengrep-scan', 'unit-test',
            'build-docker-image', 'helm-package-upload', 'helm-install',
            'helm-clean-install', 'argo-deploy'].each { String flag ->
            flags[flag] = [type: 'boolean', defaultValue: false]
        }
        if (type == 'java') {
            flags.build = [type: 'boolean', defaultValue: false]
        }
        Map keys = [
            'project-name': [type: 'string', required: true],
            'project-description': [type: 'string', defaultValue: ''],
            'project-namespace': [type: 'string', defaultValue: null],
            'publish-targets': [type: 'list', defaultValue: [], choices: ['ghcr', 'harbor']],
            'test-command': [type: 'string', defaultValue: null],
            'helm-overrides': [type: 'list', defaultValue: []],
            'trivy-fail-on': [type: 'list', defaultValue: [],
                choices: ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN']],
            'opengrep-fail-on': [type: 'list', defaultValue: [],
                choices: ['ERROR', 'WARNING', 'INFO']],
            'helm-image-tag-path': [type: 'string', defaultValue: ''],
            'docker-image': [type: 'map', children: [
                name: [type: 'string', defaultValue: null],
                dockerfile: [type: 'string', defaultValue: 'Dockerfile'],
                context: [type: 'string', defaultValue: '.']
            ]],
            'pipeline-steps': [type: 'map', children: flags]
        ]
        if (type == 'java') {
            keys['build-command'] = [type: 'string', defaultValue: null]
        }
        ['project-config': [type: 'map', required: true, children: keys]]
    }
}

import com.cloudbees.groovy.cps.NonCPS
import dev.raulstechtips.jenkins.helpers.RegistryCatalog

def call(Map cfg) {
    checkout scm
    Map registries = readYaml(text: libraryResource('dev/raulstechtips/jenkins/registries.yaml'))
    def catalog = new RegistryCatalog(registries)
    List targets = cfg['publish-targets'].collect { String name ->
        [name: name, registry: catalog.target(name)]
    }
    Map image = cfg['docker-image']
    List buildEnv = ['BUILD_CONTEXT=' + image.context, 'BUILD_DOCKERFILE=' + image.dockerfile]
    String command = '/kaniko/executor --context "$BUILD_CONTEXT" --dockerfile "$BUILD_DOCKERFILE"'
    targets.eachWithIndex { Map target, int index ->
        buildEnv << 'BUILD_DESTINATION_' + index + '=' + target.registry['image-prefix'] +
            '/' + image.name + ':' + cfg.version
        command += ' --destination "$BUILD_DESTINATION_' + index + '"'
    }
    command += ' --digest-file .pipeline/image.digest'

    container('kaniko') {
        try {
            withEnv(buildEnv) {
                publish(targets, 0, command)
            }
        } finally {
            sh 'set +x\nrm -f /kaniko/.docker/config.json'
        }
    }
    String digest = readFile('.pipeline/image.digest').trim()
    if (!validDigest(digest)) {
        error('Kaniko returned an invalid image digest; expected sha256 followed by 64 lowercase hex characters.')
    }
    return digest
}

def publish(List targets, int index, String command) {
    Map target = targets[index]
    withRegistryLogin(target.name as String) {
        if (index == 0) {
            sh '''set +x
umask 077
mkdir -p /kaniko/.docker .pipeline
printf '{"auths":{' > /kaniko/.docker/config.json'''
        }
        // Append before nesting: the next binding shadows REG_USER / REG_PASS.
        // Base64 is JSON-safe; BusyBox printf needs no jq in the builder image.
        withEnv(['REG_HOST=' + target.registry.host, 'REG_SEPARATOR=' + (index == 0 ? '' : ',')]) {
            sh '''set +x
AUTH=$(printf '%s:%s' "$REG_USER" "$REG_PASS" | base64 | tr -d '\\n')
printf '%s"%s":{"auth":"%s"}' "$REG_SEPARATOR" "$REG_HOST" "$AUTH" >> /kaniko/.docker/config.json'''
        }
        if (index + 1 < targets.size()) {
            publish(targets, index + 1, command)
        } else {
            sh '''set +x
printf '}}\\n' >> /kaniko/.docker/config.json'''
            sh 'set +x\n' + command
        }
    }
}

@NonCPS
boolean validDigest(String digest) {
    return digest ==~ /^sha256:[0-9a-f]{64}$/
}

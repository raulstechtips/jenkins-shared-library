package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/** Validates optional inputs and builds selective Kubernetes merge-patch commands. Pure: no steps. */
class InfisicalAuthCommands implements Serializable {

    /**
     * Returns server dry-run commands followed by the actual patches.
     *
     * Only supplied fields are included. Credential values are read by jq from the
     * agent environment; they are never included in a command string.
     *
     * @param inputs clientId, clientSecret, url and connectionName from the build environment
     * @return preflight commands, then update commands, in the same resource order
     * @throws IllegalArgumentException when every input is blank, the URL is invalid,
     *     or the connection name is blank when a URL is supplied
     */
    @NonCPS
    List<String> update(Map inputs) {
        boolean id = supplied(inputs.clientId)
        boolean secret = supplied(inputs.clientSecret)
        boolean url = supplied(inputs.url)
        if (!id && !secret && !url) {
            throw new IllegalArgumentException('Supply at least one of INFISICAL_CLIENT_ID, INFISICAL_CLIENT_SECRET or INFISICAL_URL.')
        }
        if (url) {
            validateUrl(inputs.url as String)
        }

        List<String> patches = []
        if (id || secret) {
            List<String> fields = []
            if (id) { fields << 'clientId: (env.INFISICAL_CLIENT_ID | @base64)' }
            if (secret) { fields << 'clientSecret: (env.INFISICAL_CLIENT_SECRET | @base64)' }
            patches << patch('{data: {' + fields.join(', ') + '}}', 'secret',
                'infisical', 'universal-auth-credentials')
        }
        if (url) {
            patches << patch('{spec: {address: env.INFISICAL_URL}}',
                'infisicalconnections.secrets.infisical.com',
                'default', required(inputs, 'connectionName'))
        }
        return patches.collect { it + ' --dry-run=server -o name >/dev/null' } +
            patches.collect { it + ' -o name' }
    }

    @NonCPS
    private static String patch(String filter, String resource, String namespace, String name) {
        '#!/bin/bash\nset +x\nset -euo pipefail\njq -cn ' + ShellQuote.quote(filter) +
            ' | kubectl --namespace ' + ShellQuote.quote(namespace) +
            ' patch ' + resource + ' ' + ShellQuote.quote(name) +
            ' --type=merge --patch-file=/dev/stdin'
    }

    @NonCPS
    private static boolean supplied(Object value) {
        value != null && !value.toString().trim().isEmpty()
    }

    @NonCPS
    private static String required(Map targets, String key) {
        Object value = targets[key]
        if (!(value instanceof String) || !value.trim()) {
            throw new IllegalArgumentException("${key} must be a non-empty string")
        }
        value
    }

    @NonCPS
    private static void validateUrl(String value) {
        URI uri
        try {
            uri = new URI(value)
        } catch (URISyntaxException ignored) {
            throw new IllegalArgumentException('INFISICAL_URL must be an HTTP or HTTPS URL.')
        }
        if (!(uri.scheme in ['http', 'https']) || !uri.host || uri.userInfo != null ||
            uri.query != null || uri.fragment != null) {
            throw new IllegalArgumentException('INFISICAL_URL must be an HTTP or HTTPS URL without credentials, query or fragment.')
        }
    }
}

package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS
import dev.raulstechtips.jenkins.config.ConfigSchema

class ConfigValidator implements Serializable {
    // Object deliberately accepts malformed YAML roots so they get validation errors.
    @NonCPS
    static Map validate(Object raw, String type) {
        if (!(type in ['cluster', 'java'])) {
            throw new IllegalArgumentException("Pipeline type must be 'cluster' or 'java'.")
        }
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException("Invalid type for 'root': expected map, found ${yamlType(raw)}.")
        }
        List<String> errors = []
        Map root = normalize(raw as Map, ConfigSchema.tree(type), '', errors)
        Map cfg = root['project-config'] as Map
        if (cfg != null) {
            Map steps = cfg['pipeline-steps'] ?: [:]
            Map image = cfg['docker-image']
            String name = cfg['project-name']
            String namespace = cfg['project-namespace']
            if (name != null && (!(name ==~ /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/) || name.length() > 53)) {
                errors.add("'project-config.project-name' must match ^[a-z0-9]([-a-z0-9]*[a-z0-9])?\$ and be at most 53 characters.")
            }
            if (namespace != null && !(namespace ==~ /^[a-z0-9]([-a-z0-9]*[a-z0-9])?$/)) {
                errors.add("'project-config.project-namespace' must match ^[a-z0-9]([-a-z0-9]*[a-z0-9])?\$.")
            }
            if ((steps['helm-install'] || steps['helm-clean-install']) && !namespace?.trim()) {
                errors.add("'project-config.project-namespace' is required when helm-install or helm-clean-install is true.")
            }
            if (steps['unit-test'] && !cfg['test-command']?.trim()) {
                errors.add("'project-config.test-command' is required when unit-test is true.")
            }
            if (steps.build && !cfg['build-command']?.trim()) {
                errors.add("'project-config.build-command' is required when build is true.")
            }
            List targets = cfg['publish-targets']
            if (targets != null && targets.unique(false).size() != targets.size()) {
                errors.add("'project-config.publish-targets' must not contain duplicates.")
            }
            if ((steps['build-docker-image'] || steps['helm-package-upload']) && !targets) {
                errors.add("'project-config.publish-targets' must be non-empty when build-docker-image or helm-package-upload is true.")
            }
            if (image != null) {
                if (!(raw['project-config']['docker-image'] instanceof Map) ||
                    !raw['project-config']['docker-image'].containsKey('name')) {
                    image.name = name
                }
                if (steps['build-docker-image'] && image.name != null &&
                    !(image.name ==~ /^[a-z0-9]+([._-][a-z0-9]+)*$/)) {
                    errors.add("'project-config.docker-image.name' must match ^[a-z0-9]+([._-][a-z0-9]+)*\$.")
                }
                ['dockerfile', 'context'].each { String key ->
                    if (image[key] != null && !relativePath(image[key])) {
                        errors.add("'project-config.docker-image.${key}' must be a non-empty path relative to the repo root.")
                    }
                }
            }
            cfg['helm-overrides']?.eachWithIndex { String path, int index ->
                if (path != null && !relativePath(path)) {
                    errors.add("'project-config.helm-overrides[${index}]' must be a non-empty path relative to the repo root.")
                }
            }
            String tagPath = cfg['helm-image-tag-path']
            if (tagPath) {
                if (!tagPath.startsWith('.')) {
                    errors.add("'project-config.helm-image-tag-path' must start with '.'.")
                }
                if (!steps['build-docker-image']) {
                    errors.add("'project-config.helm-image-tag-path' requires build-docker-image to be true.")
                }
            }
        }
        if (!errors.empty) {
            throw new IllegalArgumentException(errors.join('\n'))
        }
        cfg
    }

    @NonCPS
    private static Map normalize(Map raw, Map schema, String prefix, List<String> errors) {
        Map result = [:]
        raw.keySet().each { key ->
            if (!schema.containsKey(key)) {
                String text = String.valueOf(key).replace('\n', '\\n').replace('\r', '\\r')
                String path = prefix ? "${prefix}.${text}" : text
                String suggestion = NearestKey.suggest(text, schema.keySet())
                errors.add("Unknown key '${path}'." + (suggestion ? " Did you mean '${suggestion}'?" : ''))
            }
        }
        schema.each { String key, Map spec ->
            String path = prefix ? "${prefix}.${key}" : key
            if (!raw.containsKey(key)) {
                if (spec.required) {
                    errors.add("Missing required key '${path}'.")
                }
                result[key] = spec.type == 'map' && !spec.required ?
                    normalize([:], spec.children, path, errors) : spec.defaultValue
            } else {
                Object value = raw[key]
                if (yamlType(value) != spec.type) {
                    errors.add("Invalid type for '${path}': expected ${spec.type}, found ${yamlType(value)}.")
                    result[key] = null
                } else if (spec.type == 'map') {
                    result[key] = normalize(value as Map, spec.children, path, errors)
                } else if (spec.type == 'list') {
                    result[key] = []
                    value.eachWithIndex { Object item, int index ->
                        if (!(item instanceof String)) {
                            errors.add("Invalid type for '${path}[${index}]': expected string, found ${yamlType(item)}.")
                            result[key].add(null)
                        } else {
                            result[key].add(item)
                            if (spec.choices != null && !spec.choices.contains(item)) {
                                errors.add("Invalid value for '${path}[${index}]': expected one of ${spec.choices.join(', ')}.")
                            }
                        }
                    }
                } else {
                    result[key] = value
                }
            }
        }
        result
    }

    @NonCPS
    private static String yamlType(Object value) {
        if (value == null) { return 'null' }
        if (value instanceof Map) { return 'map' }
        if (value instanceof List) { return 'list' }
        if (value instanceof Boolean) { return 'boolean' }
        if (value instanceof String) { return 'string' }
        if (value instanceof Number) { return 'number' }
        if (value instanceof Date) { return 'timestamp' }
        value.getClass().simpleName
    }

    @NonCPS
    private static boolean relativePath(String path) {
        path.trim() && !path.startsWith('/') && !path.tokenize('/').contains('..')
    }
}

package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/**
 * Fills the placeholders in docker-image.build-args entries.
 *
 * Only ${VERSION}, ${GIT_COMMIT} and ${GIT_URL} are replaced. All other text, including other
 * ${...} forms, stays literal. The result is passed to Kaniko through environment variables, so no
 * value goes through the shell.
 */
class BuildArgs implements Serializable {
    static final List<String> PLACEHOLDERS = Collections.unmodifiableList(['VERSION', 'GIT_COMMIT', 'GIT_URL'])

    /**
     * Returns the entries with each placeholder replaced by its value.
     *
     * @param entries the NAME=VALUE entries from the config, or null
     * @param values the placeholder values, keyed by VERSION, GIT_COMMIT and GIT_URL
     * @return a new list of resolved entries, empty when entries is null or empty
     * @throws IllegalArgumentException when an entry uses a placeholder that has no value in this build
     */
    @NonCPS
    static List<String> resolve(List<String> entries, Map values) {
        (entries ?: []).collect { String entry ->
            String resolved = entry
            PLACEHOLDERS.each { String name ->
                String token = '${' + name + '}'
                if (resolved.contains(token)) {
                    Object value = values[name]
                    if (value == null || value.toString().isEmpty()) {
                        throw new IllegalArgumentException(
                            "Build argument '${entry}' uses ${token}, which has no value in this build.".toString())
                    }
                    resolved = resolved.replace(token, value.toString())
                }
            }
            resolved
        }
    }
}

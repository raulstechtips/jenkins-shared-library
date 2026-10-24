package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/**
 * Looks up publish targets in the parsed registries.yaml resource.
 */
class RegistryCatalog implements Serializable {
    private final Map targets

    /**
     * Creates a catalog over the given targets.
     *
     * @param targets the map of target name (for example ghcr or harbor) to its settings
     */
    RegistryCatalog(Map targets) {
        this.targets = targets
    }

    /**
     * Returns a copy of one target's settings, such as host, image-prefix, chart-repo and
     * credentials-id.
     *
     * @param name the target name
     * @return a copy of the settings, so callers cannot change the catalog
     * @throws IllegalArgumentException when name is not a known target
     */
    @NonCPS
    Map target(String name) {
        if (!targets.containsKey(name)) {
            throw new IllegalArgumentException("Unknown registry target '${name}'")
        }
        return new LinkedHashMap(targets[name] as Map)
    }
}

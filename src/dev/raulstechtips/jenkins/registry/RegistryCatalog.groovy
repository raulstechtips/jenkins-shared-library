package dev.raulstechtips.jenkins.registry

import com.cloudbees.groovy.cps.NonCPS

class RegistryCatalog implements Serializable {
    private final Map targets

    RegistryCatalog(Map targets) {
        this.targets = targets
    }

    @NonCPS
    Map target(String name) {
        if (!targets.containsKey(name)) {
            throw new IllegalArgumentException("Unknown registry target '${name}'")
        }
        return new LinkedHashMap(targets[name] as Map)
    }
}

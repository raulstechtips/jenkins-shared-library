package dev.raulstechtips.jenkins.shell

import com.cloudbees.groovy.cps.NonCPS

class ShellQuote implements Serializable {
    @NonCPS
    static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'"
    }
}

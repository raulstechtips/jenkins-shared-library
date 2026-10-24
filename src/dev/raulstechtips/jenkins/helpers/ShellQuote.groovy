package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/** The one POSIX shell quoting helper. Pure: no steps, no state. */
class ShellQuote implements Serializable {
    /**
     * Returns value as one POSIX single-quoted word; each embedded single quote becomes '\''.
     * A null value throws IllegalArgumentException: a missing config value must fail loudly,
     * not become an empty argument.
     *
     * @param value the text to quote
     * @return the quoted word, safe to paste into a shell command
     * @throws IllegalArgumentException when value is null
     */
    @NonCPS
    static String quote(String value) {
        if (value == null) {
            throw new IllegalArgumentException('Cannot shell-quote a null value')
        }
        return "'" + value.replace("'", "'\\''") + "'"
    }
}

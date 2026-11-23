package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS
import java.util.regex.Matcher
import java.util.regex.Pattern

/**
 * Removes exact version pins from apt-get install commands in a Dockerfile.
 *
 * Debian keeps only the newest version of a package in its repositories, so a pin such as
 * libc6=2.41-12+deb13u3 stops working when a newer fix ships. This helper turns each
 * package=version argument of an apt-get install command into package, so apt installs the newest
 * version. Only RUN instructions are read, with their continuation lines (comment and blank lines
 * inside a continuation belong to it, as in Docker), and inside them only the arguments between apt-get
 * install and the next &&, ||, ; or |. A version must start with a digit. Packages in the keep list
 * stay pinned.
 */
class AptUnpin implements Serializable {
    private static final Pattern INSTALL = Pattern.compile(/apt-get(?:\s+(?:-[oc]\s+\S+|-\S+))*\s+install\b/)
    private static final Pattern SEPARATOR = Pattern.compile(/&&|\|\||;|\|/)
    private static final Pattern PIN = Pattern.compile(/(?<=^|\s)([a-z0-9][a-z0-9+.-]*)=([0-9][^\s\\;&|)]*)(?=\s|\\|$)/)

    /**
     * Returns the Dockerfile without the apt version pins, and the list of removed pins.
     *
     * @param dockerfile the Dockerfile text
     * @param keep package names whose pin stays, for example ['infisical']
     * @return a map with text (the new Dockerfile) and changes (one 'name=version -> name' line per
     *     removed pin, in file order)
     */
    @NonCPS
    static Map unpin(String dockerfile, List<String> keep) {
        List<String> lines = dockerfile.split('\n', -1) as List
        List<String> changes = []
        int index = 0
        while (index < lines.size()) {
            if (!(lines[index] ==~ /(?i)^\s*RUN(\s.*)?$/)) {
                index++
                continue
            }
            int end = index
            while (end < lines.size() - 1 && (lines[end].replaceAll(/\s+$/, '').endsWith('\\') ||
                (end > index && lines[end] ==~ /^\s*(#.*)?$/))) {
                end++
            }
            // Comment lines are blanked while the span is read, so their text is never a separator
            // or a pin, and restored afterwards. The replacements never add or remove a line.
            List<String> masked = lines.subList(index, end + 1).collect { String line ->
                line ==~ /^\s*#.*$/ ? '' : line
            }
            String result = unpinSpan(masked.join('\n'), keep ?: [], changes)
            List<String> replaced = result.split('\n', -1) as List
            for (int i = 0; i < replaced.size(); i++) {
                if (masked[i] != '' || lines[index + i] == '') {
                    lines[index + i] = replaced[i]
                }
            }
            index = end + 1
        }
        [text: lines.join('\n'), changes: changes]
    }

    @NonCPS
    private static String unpinSpan(String span, List<String> keep, List<String> changes) {
        StringBuilder out = new StringBuilder()
        int position = 0
        Matcher install = INSTALL.matcher(span)
        while (install.find(position)) {
            int start = install.end()
            Matcher separator = SEPARATOR.matcher(span)
            int stop = separator.find(start) ? separator.start() : span.length()
            out.append(span, position, start)
            out.append(unpinArguments(span.substring(start, stop), keep, changes))
            position = stop
        }
        out.append(span.substring(position))
        out.toString()
    }

    @NonCPS
    private static String unpinArguments(String arguments, List<String> keep, List<String> changes) {
        Matcher pin = PIN.matcher(arguments)
        StringBuffer out = new StringBuffer()
        while (pin.find()) {
            String name = pin.group(1)
            if (keep.contains(name)) {
                pin.appendReplacement(out, Matcher.quoteReplacement(pin.group(0)))
            } else {
                changes.add(pin.group(0) + ' -> ' + name)
                pin.appendReplacement(out, Matcher.quoteReplacement(name))
            }
        }
        pin.appendTail(out)
        out.toString()
    }
}

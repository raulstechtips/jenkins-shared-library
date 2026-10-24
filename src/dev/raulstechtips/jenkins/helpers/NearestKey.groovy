package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS

/**
 * Finds the known key closest to a mistyped key, for the "Did you mean" hint in config errors.
 */
class NearestKey implements Serializable {
    /**
     * Returns the sibling with the smallest Levenshtein edit distance to key, when that distance is
     * below 4.
     *
     * On a tie, the first sibling found wins.
     *
     * @param key the unknown key
     * @param siblings the valid keys at the same level
     * @return the closest sibling, or null when none is within 3 edits
     */
    @NonCPS
    static String suggest(String key, Collection<String> siblings) {
        String nearest = null
        int minimum = 4
        for (String sibling : siblings) {
            int distance = distance(key, sibling)
            if (distance < minimum) {
                minimum = distance
                nearest = sibling
            }
        }
        nearest
    }

    @NonCPS
    private static int distance(String left, String right) {
        int[] previous = new int[right.length() + 1]
        for (int j = 0; j <= right.length(); j++) {
            previous[j] = j
        }
        for (int i = 1; i <= left.length(); i++) {
            int[] current = new int[right.length() + 1]
            current[0] = i
            for (int j = 1; j <= right.length(); j++) {
                int substitution = previous[j - 1] + (left.charAt(i - 1) == right.charAt(j - 1) ? 0 : 1)
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1), substitution)
            }
            previous = current
        }
        previous[right.length()]
    }
}

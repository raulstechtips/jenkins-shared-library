package dev.raulstechtips.jenkins.config

import com.cloudbees.groovy.cps.NonCPS

class NearestKey implements Serializable {
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

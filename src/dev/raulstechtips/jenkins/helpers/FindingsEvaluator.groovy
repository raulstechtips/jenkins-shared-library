package dev.raulstechtips.jenkins.helpers

import com.cloudbees.groovy.cps.NonCPS
import groovy.json.JsonSlurper

/**
 * Pure evaluation of Trivy and opengrep JSON reports against a fail list.
 *
 * Both methods return a plain LinkedHashMap:
 *   counts  - LinkedHashMap severity -> Integer (Trivy: CRITICAL, HIGH, MEDIUM, LOW, UNKNOWN;
 *             opengrep: ERROR, WARNING, INFO)
 *   secrets - Integer, number of Trivy secret findings (always 0 for opengrep)
 *   fail    - Boolean, true when any counted finding has a severity in failOn
 *
 * Trivy secret findings are also counted in counts under their own severity.
 * Report content is untrusted: missing or null arrays are treated as empty, and
 * input that is not parsable JSON is rejected with IllegalArgumentException.
 */
class FindingsEvaluator implements Serializable {

    private static final long serialVersionUID = 1L

    @NonCPS
    Map evaluateTrivy(String json, List failOn) {
        Map counts = emptyCounts(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'])
        int secrets = 0
        Object root = parse(json)
        List results = root instanceof Map ? asList(((Map) root).get('Results')) : []
        for (Object result : results) {
            if (!(result instanceof Map)) {
                continue
            }
            Map r = (Map) result
            for (Object vuln : asList(r.get('Vulnerabilities'))) {
                if (vuln instanceof Map) {
                    bump(counts, ((Map) vuln).get('Severity'), 'UNKNOWN')
                }
            }
            for (Object misconf : asList(r.get('Misconfigurations'))) {
                if (misconf instanceof Map && 'FAIL'.equals(((Map) misconf).get('Status'))) {
                    bump(counts, ((Map) misconf).get('Severity'), 'UNKNOWN')
                }
            }
            for (Object secret : asList(r.get('Secrets'))) {
                if (secret instanceof Map) {
                    secrets++
                    bump(counts, ((Map) secret).get('Severity'), 'UNKNOWN')
                }
            }
        }
        return summary(counts, secrets, failOn)
    }

    @NonCPS
    Map evaluateOpengrep(String json, List failOn) {
        Map counts = emptyCounts(['ERROR', 'WARNING', 'INFO'])
        Object root = parse(json)
        List results = root instanceof Map ? asList(((Map) root).get('results')) : []
        for (Object result : results) {
            if (result instanceof Map) {
                Object extra = ((Map) result).get('extra')
                Object severity = extra instanceof Map ? ((Map) extra).get('severity') : null
                bump(counts, severity, null)
            }
        }
        return summary(counts, 0, failOn)
    }

    @NonCPS
    private Object parse(String json) {
        if (json == null || json.trim().isEmpty()) {
            throw new IllegalArgumentException('Findings report is empty')
        }
        try {
            return new JsonSlurper().parseText(json)
        } catch (Exception e) {
            throw new IllegalArgumentException('Findings report is not valid JSON: ' + e.getMessage())
        }
    }

    @NonCPS
    private List asList(Object value) {
        return value instanceof List ? (List) value : []
    }

    @NonCPS
    private Map emptyCounts(List severities) {
        Map counts = new LinkedHashMap()
        for (Object s : severities) {
            counts.put(s, 0)
        }
        return counts
    }

    // Severities outside the known keys go to fallback; a null fallback ignores them.
    @NonCPS
    private void bump(Map counts, Object severity, String fallback) {
        String key = severity == null ? null : severity.toString().toUpperCase()
        if (key == null || !counts.containsKey(key)) {
            key = fallback
        }
        if (key != null) {
            counts.put(key, ((Integer) counts.get(key)) + 1)
        }
    }

    @NonCPS
    private Map summary(Map counts, int secrets, List failOn) {
        boolean fail = false
        if (failOn != null) {
            for (Object s : failOn) {
                if (s != null) {
                    Object n = counts.get(s.toString().toUpperCase())
                    if (n != null && ((Integer) n) > 0) {
                        fail = true
                    }
                }
            }
        }
        Map result = new LinkedHashMap()
        result.put('counts', counts)
        result.put('secrets', secrets)
        result.put('fail', fail)
        return result
    }
}

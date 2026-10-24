package dev.raulstechtips.jenkins.scan

import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertFalse
import static org.junit.jupiter.api.Assertions.assertThrows
import static org.junit.jupiter.api.Assertions.assertTrue

import org.junit.jupiter.api.Test

class FindingsEvaluatorTest {

    private final FindingsEvaluator evaluator = new FindingsEvaluator()

    private static String fixture(String name) {
        return new File("test/resources/fixtures/scan/${name}").text
    }

    @Test
    void countsMisconfigurationsPerSeverityFromRealConfigReport() {
        Map r = evaluator.evaluateTrivy(fixture('trivy-config.json'), ['CRITICAL'])

        assertEquals([CRITICAL: 5, HIGH: 9, MEDIUM: 28, LOW: 94, UNKNOWN: 0], r.counts)
        assertEquals(0, r.secrets)
        assertTrue(r.fail)
    }

    @Test
    void countsVulnerabilitiesPerSeverityFromRealImageReport() {
        Map r = evaluator.evaluateTrivy(fixture('trivy-image.json'), ['CRITICAL', 'HIGH'])

        assertEquals([CRITICAL: 8, HIGH: 160, MEDIUM: 546, LOW: 136, UNKNOWN: 4], r.counts)
        assertTrue(r.fail)
    }

    @Test
    void failIsTrueOnlyForSeveritiesInTheList() {
        String json = '{"Results":[{"Vulnerabilities":[{"Severity":"MEDIUM"},{"Severity":"LOW"}]}]}'

        assertFalse(evaluator.evaluateTrivy(json, ['CRITICAL', 'HIGH']).fail)
        assertTrue(evaluator.evaluateTrivy(json, ['LOW']).fail)
        assertTrue(evaluator.evaluateTrivy(json, ['medium']).fail, 'fail list is case-insensitive')
    }

    @Test
    void emptyOrNullFailListNeverFails() {
        assertFalse(evaluator.evaluateTrivy(fixture('trivy-image.json'), []).fail)
        assertFalse(evaluator.evaluateTrivy(fixture('trivy-image.json'), null).fail)
        assertFalse(evaluator.evaluateOpengrep(fixture('opengrep.json'), []).fail)
    }

    @Test
    void realSecretReportCountsOneSecretAndFailsOnItsSeverity() {
        Map r = evaluator.evaluateTrivy(fixture('trivy-secret.json'), ['HIGH'])

        assertEquals(1, r.secrets)
        assertEquals(1, r.counts.HIGH)
        assertTrue(r.fail)
        assertFalse(evaluator.evaluateTrivy(fixture('trivy-secret.json'), ['CRITICAL']).fail)
    }

    @Test
    void committedSecretFixtureHoldsNoKeyBody() {
        String text = fixture('trivy-secret.json')

        assertEquals(null, text.find(/[A-Za-z0-9+\/]{40,}/), 'unmasked base64 run in secret fixture')
    }

    @Test
    void opengrepSeveritiesAreCountedFromRealReport() {
        Map r = evaluator.evaluateOpengrep(fixture('opengrep.json'), ['ERROR'])

        assertEquals([ERROR: 0, WARNING: 1, INFO: 0], r.counts)
        assertEquals(0, r.secrets)
        assertFalse(r.fail)
        assertTrue(evaluator.evaluateOpengrep(fixture('opengrep.json'), ['WARNING']).fail)
    }

    @Test
    void onlyFailedMisconfigurationsAreCounted() {
        String json = '{"Results":[{"Misconfigurations":[' +
            '{"Status":"FAIL","Severity":"HIGH"},{"Status":"PASS","Severity":"HIGH"},{"Status":"FAIL","Severity":"LOW"}]}]}'

        Map r = evaluator.evaluateTrivy(json, ['HIGH'])

        assertEquals(1, r.counts.HIGH)
        assertEquals(1, r.counts.LOW)
    }

    @Test
    void missingNullOrEmptyResultsAreTolerated() {
        for (String json : ['{}', '{"Results":null}', '{"Results":[]}', '[]', 'null']) {
            Map r = evaluator.evaluateTrivy(json, ['CRITICAL'])
            assertEquals([CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, UNKNOWN: 0], r.counts, json)
            assertEquals(0, r.secrets, json)
            assertFalse(r.fail, json)
        }
    }

    @Test
    void missingOrNullVulnerabilitiesAreTolerated() {
        String json = '{"Results":[{"Target":"a"},{"Vulnerabilities":null},{"Vulnerabilities":[{"Severity":"HIGH"}]},null]}'

        Map r = evaluator.evaluateTrivy(json, ['HIGH'])

        assertEquals(1, r.counts.HIGH)
        assertTrue(r.fail)
    }

    @Test
    void missingOrNullSecretsAreTolerated() {
        String json = '{"Results":[{"Target":"a"},{"Secrets":null},{"Secrets":[{"Severity":"CRITICAL"}]}]}'

        Map r = evaluator.evaluateTrivy(json, ['CRITICAL'])

        assertEquals(1, r.secrets)
        assertEquals(1, r.counts.CRITICAL)
    }

    @Test
    void missingOrNullMisconfigurationsAndOpengrepResultsAreTolerated() {
        assertFalse(evaluator.evaluateTrivy('{"Results":[{"Misconfigurations":null}]}', ['HIGH']).fail)
        for (String json : ['{}', '{"results":null}', '{"results":[{"extra":null},{}]}']) {
            Map r = evaluator.evaluateOpengrep(json, ['ERROR'])
            assertEquals([ERROR: 0, WARNING: 0, INFO: 0], r.counts, json)
            assertFalse(r.fail, json)
        }
    }

    @Test
    void unknownOrMissingSeverityCountsAsUnknown() {
        String json = '{"Results":[{"Vulnerabilities":[{},{"Severity":"BOGUS"},{"Severity":null}]}]}'

        assertEquals(3, evaluator.evaluateTrivy(json, []).counts.UNKNOWN)
    }

    @Test
    void returnsPlainMapsNotLazyMaps() {
        Map r = evaluator.evaluateTrivy(fixture('trivy-secret.json'), ['HIGH'])

        assertEquals(LinkedHashMap, r.getClass())
        assertEquals(LinkedHashMap, r.counts.getClass())
        assertTrue(r.fail instanceof Boolean)
        assertTrue(r.secrets instanceof Integer)
    }

    @Test
    void unparsableOrBlankInputIsRejectedInsteadOfPassing() {
        assertThrows(IllegalArgumentException) { evaluator.evaluateTrivy('not json', ['HIGH']) }
        assertThrows(IllegalArgumentException) { evaluator.evaluateTrivy('', ['HIGH']) }
        assertThrows(IllegalArgumentException) { evaluator.evaluateOpengrep(null, ['ERROR']) }
    }
}

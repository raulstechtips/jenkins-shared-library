package vars

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import static org.junit.jupiter.api.Assertions.*

class TrivySecretScanTest extends TrivyTestSupport {
    @ParameterizedTest
    @ValueSource(booleans = [false, true])
    void secretsAlwaysFailRegardlessOfFailList(boolean high) {
        reportFixture = 'trivy-secret'
        cfg['trivy-fail-on'] = high ? ['HIGH'] : []
        def failure = assertThrows(IllegalStateException) {
            loadScript('vars/trivySecretScan.groovy').call(cfg)
        }
        assertEquals('Trivy found a secret. See .pipeline/reports/trivy-secret.json in the build artifacts.', failure.message)
        assertEquals('trivy fs --scanners secret --skip-dirs .git --skip-dirs .pipeline --format json --output .pipeline/reports/trivy-secret.json --exit-code 0 .',
            scans('fs')[0].script)
        assertScanCommands('fs')
        assertSummary()
        assertEquals(['.pipeline/reports/trivy-secret.json'], reads)
    }

    @ParameterizedTest
    @CsvSource(['false,false', 'true,false', 'false,true', 'true,true'])
    void appliesIgnoreFilesWithoutWeakeningSecretGate(boolean yaml, boolean plain) {
        reportFixture = 'trivy-secret'
        setIgnore(yaml, plain)
        assertThrows(IllegalStateException) {
            loadScript('vars/trivySecretScan.groovy').call(cfg)
        }
        assertIgnore('fs', yaml, plain)
    }

    @Test
    void noSecretsDoesNotFailEvenWithOtherFindings() {
        cfg['trivy-fail-on'] = ['HIGH']
        loadScript('vars/trivySecretScan.groovy').call(cfg)
        assertSummary()
    }
}

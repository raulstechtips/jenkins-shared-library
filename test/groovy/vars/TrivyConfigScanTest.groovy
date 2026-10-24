package vars

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import static org.junit.jupiter.api.Assertions.*

class TrivyConfigScanTest extends TrivyTestSupport {
    @Test
    void warnsWithRealReportAndEmptyFailList() {
        loadScript('vars/trivyConfigScan.groovy').call('plugins/Dockerfile', 'plugins-Dockerfile', cfg)
        assertScanCommands('config')
        assertEquals('trivy config --format json --output \'.pipeline/reports/trivy-config-plugins-Dockerfile.json\' --exit-code 0 \'plugins/Dockerfile\'',
            scans('config')[0].script)
        assertEquals(['.pipeline/reports/trivy-config-plugins-Dockerfile.json'], reads)
        assertSummary()
        assertTrue(messages.any { it.contains('HIGH: ') && !it.endsWith('HIGH: 0') })
    }

    @Test
    void failsOnHighFromRealReportEvenWithSuccessfulShellOutput() {
        cfg['trivy-fail-on'] = ['HIGH']
        def failure = assertThrows(IllegalStateException) {
            loadScript('vars/trivyConfigScan.groovy').call('Dockerfile', 'Dockerfile', cfg)
        }
        assertTrue(failure.message.contains('.pipeline/reports/trivy-config-Dockerfile.json'))
        assertSummary()
        assertScanCommands('config')
    }

    @ParameterizedTest
    @CsvSource(['false,false', 'true,false', 'false,true', 'true,true'])
    void selectsIgnoreFileWithYamlPrecedence(boolean yaml, boolean plain) {
        setIgnore(yaml, plain)
        loadScript('vars/trivyConfigScan.groovy').call('Dockerfile', 'Dockerfile', cfg)
        assertIgnore('config', yaml, plain)
    }

    @Test
    void propagatesScannerFailureWithoutReadingStaleReport() {
        shellFailure = true
        assertThrows(IllegalStateException) {
            loadScript('vars/trivyConfigScan.groovy').call('Dockerfile', 'Dockerfile', cfg)
        }
        assertTrue(reads.empty)
    }

    @Test
    void targetAndReportPathAreSingleQuotedWithEmbeddedQuoteEscaping() {
        String target = "folder with space/Dockerfile'; \$(touch injected)"
        String label = "label'; \$(touch injected)"
        loadScript('vars/trivyConfigScan.groovy').call(target, label, cfg)
        String quotedTarget = "'" + target.replace("'", "'\\''") + "'"
        String quotedReport = "'" + ('.pipeline/reports/trivy-config-' + label + '.json').replace("'", "'\\''") + "'"
        assertTrue(scans('config')[0].script.contains('--output ' + quotedReport))
        assertTrue(scans('config')[0].script.endsWith(quotedTarget))
        List<String> args = shellArguments(scans('config')[0].script)
        assertEquals(target, args.last())
        assertEquals('.pipeline/reports/trivy-config-' + label + '.json', args[args.indexOf('--output') + 1])
    }
}

package vars

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class TrivyDockerfileScanTest extends TrivyTestSupport {
    @Test
    void findsAllDockerfileFormsAndDeduplicatesPaths() {
        files['**/Dockerfile'] = ['Dockerfile', 'plugins/Dockerfile', '.pipeline/pulled/Dockerfile', '.git/Dockerfile']
        files['**/*.Dockerfile'] = ['ci/build.Dockerfile', 'plugins/Dockerfile']
        files['**/Dockerfile.*'] = ['ci/Dockerfile.dev']
        loadScript('vars/trivyDockerfileScan.groovy').call(cfg)
        assertEquals(['**/Dockerfile', '**/*.Dockerfile', '**/Dockerfile.*'], searches*.glob)
        searches.each { assertEquals('.pipeline/**,.git/**', it.excludes) }
        assertEquals(['Dockerfile', 'plugins/Dockerfile', 'ci/build.Dockerfile', 'ci/Dockerfile.dev'],
            engineCalls.collect { it[0] })
        assertEquals(['Dockerfile', 'plugins-Dockerfile', 'ci-build.Dockerfile', 'ci-Dockerfile.dev'],
            engineCalls.collect { it[1] })
        assertScanCommands('config', 4)
        assertSummary(4)
    }

    @Test
    void logsWhenNoDockerfileExists() {
        loadScript('vars/trivyDockerfileScan.groovy').call(cfg)
        assertEquals(['No Dockerfile found'], messages)
        assertTrue(engineCalls.empty)
        assertTrue(shells.empty)
    }
}

package vars

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class TrivyChartScanTest extends TrivyTestSupport {
    @Test
    void pullsFirstTargetWithLoginRendersDefaultAndEachRepositoryCiFile() {
        cfg['publish-targets'] = ['harbor', 'ghcr']
        files['charts/ci/*-values.yaml'] = ['charts/ci/all-on-values.yaml', 'charts/ci/minimal-values.yaml']
        loadScript('vars/trivyChartScan.groovy').call(cfg)
        assertEquals(['harbor-push'], logins)
        def pull = shells.find { it.script.contains('helm pull ') }
        assertEquals('helm', pull.container)
        assertTrue(pull.credentials)
        assertTrue(pull.script.startsWith('set +x\n'))
        assertTrue(pull.script.contains('printf \'%s\' "$REG_PASS" | helm registry login \'harbor.prod.raulstechtips.dev\' -u "$REG_USER" --password-stdin'))
        assertTrue(pull.script.contains("helm pull 'oci://harbor.prod.raulstechtips.dev/raulstechtips/charts/demo-chart' --version '1.2.3' --untar --untardir .pipeline/pulled"))
        def renders = shells.findAll { it.script.startsWith('helm template ') }
        assertEquals(3, renders.size())
        renders.each { assertEquals('helm', it.container); assertFalse(it.credentials) }
        assertEquals("helm template 'demo' '.pipeline/pulled/demo-chart' -n 'demo' > '.pipeline/render-uploaded/default.yaml'",
            renders[0].script)
        assertTrue(renders[1].script.contains("-f 'charts/ci/all-on-values.yaml'"))
        assertTrue(renders[2].script.contains("-f 'charts/ci/minimal-values.yaml'"))
        assertEquals(['.pipeline/render-uploaded/default.yaml', '.pipeline/render-uploaded/ci-all-on-values.yaml',
            '.pipeline/render-uploaded/ci-minimal-values.yaml'], engineCalls.collect { it[0] })
        assertEquals(['uploaded-default', 'uploaded-ci-all-on-values', 'uploaded-ci-minimal-values'],
            engineCalls.collect { it[1] })
        assertScanCommands('config', 3)
        assertTrue(reads.contains('.pipeline/reports/trivy-config-uploaded-default.json'))
        assertSummary(3)
        assertFalse(credentialsActive)
    }

    @Test
    void defaultsOnlyAndExplicitNamespace() {
        cfg['project-namespace'] = 'demo-tests'
        loadScript('vars/trivyChartScan.groovy').call(cfg)
        assertEquals(1, engineCalls.size())
        assertTrue(shells.any { it.script.contains("-n 'demo-tests'") })
    }

    @Test
    void uploadedChartFindingsEnforceFailList() {
        cfg['trivy-fail-on'] = ['HIGH']
        assertThrows(IllegalStateException) {
            loadScript('vars/trivyChartScan.groovy').call(cfg)
        }
        assertSummary()
    }

    @Test
    void quotesConfigVersionNamespaceAndRepositoryValuesPath() {
        cfg.version = "version'; \$(touch injected)"
        cfg['project-namespace'] = "namespace'; \$(touch injected)"
        files['charts/ci/*-values.yaml'] = ["charts/ci/space '; \$(touch injected)-values.yaml"]
        loadScript('vars/trivyChartScan.groovy').call(cfg)
        assertTrue(shells.find { it.script.contains('helm pull ') }.script.contains(
            "--version 'version'\\''; \$(touch injected)'"))
        def renders = shells.findAll { it.script.startsWith('helm template ') }
        assertTrue(renders[0].script.contains("-n 'namespace'\\''; \$(touch injected)'"))
        assertTrue(renders[1].script.contains("-f 'charts/ci/space '\\''; \$(touch injected)-values.yaml'"))
    }
}

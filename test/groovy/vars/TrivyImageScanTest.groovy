package vars

import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import static org.junit.jupiter.api.Assertions.*

class TrivyImageScanTest extends TrivyTestSupport {
    String builtRef(String prefix = 'ghcr.io/raulstechtips') {
        prefix + '/demo:1.2.3@sha256:' + ('a' * 64)
    }

    @Test
    void extractsUniqueUpstreamsFromRenderAndDropsOwnedEmptyAndMapValues() {
        reportFixture = 'trivy-image'
        files['.pipeline/render/*.yaml'] = ['.pipeline/render/default.yaml']
        loadScript('vars/trivyImageScan.groovy').call(cfg, null)
        def extraction = shells.find { it.script.startsWith('yq eval-all') }
        assertEquals('tools', extraction.container)
        assertEquals('yq eval-all \'.. | select(has("image")) | .image | select(tag == "!!str")\' \'.pipeline/render/default.yaml\'',
            extraction.script)
        assertScanCommands('image', 2)
        assertTrue(scans('image')[0].script.endsWith("'quay.io/example/service:stable'"))
        assertTrue(scans('image')[1].script.endsWith("'docker.io/library/alpine:stable'"))
        assertTrue(logins.empty)
        assertSummary(2)
        reads.each { assertTrue(it ==~ /\.pipeline\/reports\/trivy-image-[A-Za-z0-9._-]+\.json/) }
        assertEquals(2, reads.unique().size())
    }

    @Test
    void builtRefIncludesDigestAndUsesFirstTargetsCredentialsOnly() {
        reportFixture = 'trivy-image'
        cfg['publish-targets'] = ['harbor', 'ghcr']
        String ref = builtRef('harbor.prod.raulstechtips.dev/raulstechtips')
        loadScript('vars/trivyImageScan.groovy').call(cfg, ref)
        assertEquals(['harbor-push'], logins)
        assertScanCommands('image')
        def scan = scans('image')[0]
        assertTrue(scan.credentials)
        assertTrue(scan.script.startsWith('set +x\n'))
        assertTrue(scan.script.contains('TRIVY_USERNAME="$REG_USER" TRIVY_PASSWORD="$REG_PASS"'))
        assertTrue(scan.script.endsWith("'" + ref + "'"))
        assertFalse(credentialsActive)
        assertSummary()
    }

    @Test
    void upstreamScanRunsOutsideBuiltImageCredentialScope() {
        reportFixture = 'trivy-image'
        files['.pipeline/render/*.yaml'] = ['.pipeline/render/default.yaml']
        loadScript('vars/trivyImageScan.groovy').call(cfg, builtRef())
        assertEquals([true, false, false], scans('image')*.credentials)
        scans('image').drop(1).each { assertFalse(it.script.contains('REG_PASS')) }
    }

    @Test
    void failsOnHighUsingActualImageReport() {
        reportFixture = 'trivy-image'
        cfg['trivy-fail-on'] = ['HIGH']
        def failure = assertThrows(IllegalStateException) {
            loadScript('vars/trivyImageScan.groovy').call(cfg, builtRef())
        }
        assertTrue(failure.message.contains('.pipeline/reports/trivy-image-'))
        assertScanCommands('image')
        assertSummary()
        assertFalse(credentialsActive)
    }

    @ParameterizedTest
    @CsvSource(['false,false', 'true,false', 'false,true', 'true,true'])
    void honorsIgnoreFilesForBuiltAndUpstreamImages(boolean yaml, boolean plain) {
        reportFixture = 'trivy-image'
        setIgnore(yaml, plain)
        files['.pipeline/render/*.yaml'] = ['.pipeline/render/default.yaml']
        loadScript('vars/trivyImageScan.groovy').call(cfg, builtRef())
        assertScanCommands('image', 3)
        assertIgnore('image', yaml, plain)
    }

    @Test
    void emptyRenderAndNoBuiltImageDoNotInvokeScanner() {
        renderFixture = 'empty.yaml'
        files['.pipeline/render/*.yaml'] = ['.pipeline/render/default.yaml']
        loadScript('vars/trivyImageScan.groovy').call(cfg, null)
        assertTrue(scans('image').empty)
        assertTrue(reads.empty)
    }

    @Test
    void absentRenderDoesNotExecuteYqOnUnmatchedGlob() {
        loadScript('vars/trivyImageScan.groovy').call(cfg, null)
        assertTrue(shells.empty)
    }

    @Test
    void everyCredentialShellUsesLiteralEnvironmentReferences() {
        reportFixture = 'trivy-image'
        loadScript('vars/trivyImageScan.groovy').call(cfg, builtRef())
        shells.findAll { it.credentials }.each {
            assertTrue(it.script.startsWith('set +x\n'))
            assertFalse(it.script.contains('robot$'))
        }
    }

    @Test
    void toolOutputRefsAndRenderPathsRemainInertAndSlugsCannotCollide() {
        reportFixture = 'trivy-image'
        renderFixture = 'hostile.yaml'
        String path = ".pipeline/render/name'; \$(touch injected).yaml"
        files['.pipeline/render/*.yaml'] = [path]
        loadScript('vars/trivyImageScan.groovy').call(cfg, null)
        assertTrue(shells[0].script.endsWith("'" + path.replace("'", "'\\''") + "'"))
        assertScanCommands('image', 4)
        assertTrue(scans('image')[0].script.endsWith("'example/service:tag'\\''; \$(touch injected)'"))
        assertEquals("example/service:tag'; \$(touch injected)", shellArguments(scans('image')[0].script).last())
        reads.each {
            String name = it.tokenize('/').last()
            assertTrue(name ==~ /[A-Za-z0-9._-]+/)
            assertTrue(name.length() <= 255)
        }
        assertEquals(4, reads.unique().size())
    }
}

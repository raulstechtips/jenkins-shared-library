package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.yaml.snakeyaml.Yaml
import static org.junit.jupiter.api.Assertions.*

class HelmPackageUploadTest extends BasePipelineTest {
    private List<Map> commands
    private List<String> scopes
    private Map environment
    private String activeContainer
    private String remoteVersion
    private Map cfg
    private String digest
    private String chartName
    private Map registryOverrides

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        commands = []
        scopes = []
        environment = [:]
        remoteVersion = '1.2.3'
        digest = 'sha256:' + 'a' * 64
        chartName = 'chart-name'
        registryOverrides = [:]
        cfg = [version: '1.2.3', 'publish-targets': ['ghcr', 'harbor'],
            'helm-image-tag-path': '.jenkins.controller.image.tag']
        helper.registerAllowedMethod('libraryResource', [String]) { String path ->
            assertEquals('dev/raulstechtips/jenkins/registries.yaml', path)
            new File('resources/' + path).text
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            if (args.file) {
                assertEquals('charts/Chart.yaml', args.file)
                return [name: chartName]
            }
            Map parsed = new Yaml().load(args.text)
            if (parsed.ghcr) {
                parsed.ghcr.putAll(registryOverrides)
            }
            parsed
        }
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            activeContainer = name
            try { body.call() } finally { activeContainer = null }
        }
        helper.registerAllowedMethod('withEnv', [List, Closure]) { List values, Closure body ->
            Map previous = new LinkedHashMap(environment)
            values.each { String value ->
                int separator = value.indexOf('=')
                environment[value.substring(0, separator)] = value.substring(separator + 1)
            }
            try { body.call() } finally { environment = previous }
        }
        helper.registerAllowedMethod('withRegistryLogin', [String, Closure]) { String target, Closure body ->
            Map previous = new LinkedHashMap(environment)
            environment.REG_USER = 'robot$' + target
            environment.REG_PASS = 'synthetic-' + target + '-password'
            scopes << target
            try { body.call() } finally {
                scopes.remove(scopes.size() - 1)
                environment = previous
            }
        }
        helper.registerAllowedMethod('sh', [String]) { String text -> recordCommand(text) }
        helper.registerAllowedMethod('sh', [Map]) { Map args ->
            assertEquals(true, args.returnStdout)
            recordCommand(args.script as String)
            new Yaml().dump([name: chartName, version: remoteVersion])
        }
        helper.registerAllowedMethod('error', [String]) { String message ->
            throw new IllegalArgumentException(message)
        }
    }

    private void recordCommand(String text) {
        commands << [text: text, container: activeContainer,
            scopes: new ArrayList(scopes), environment: new LinkedHashMap(environment)]
    }

    @Test
    void writesVersionDigestTagBeforePackagingAndPushesSameArchiveToBothTargets() {
        loadScript('vars/helmPackageUpload.groovy').call(cfg, digest)
        Map tag = commands.find { it.text.contains('yq -i') }
        assertEquals('tools', tag.container)
        assertEquals(['-i', '.jenkins.controller.image.tag = strenv(TAG)', 'charts/values.yaml'],
            PublishingShellProbe.argv(tag, 'yq'))
        assertEquals('1.2.3@' + digest, tag.environment.TAG)
        Map pack = commands.find { it.text.contains('helm package') }
        assertEquals('helm', pack.container)
        assertTrue(pack.scopes.empty, 'Packaging must not bind registry credentials')
        assertEquals(['package', 'charts', '--version', '1.2.3', '--destination', '.pipeline/dist'],
            PublishingShellProbe.argv(pack, 'helm'))
        assertTrue(commands.indexOf(tag) < commands.indexOf(pack))
        List pushes = commands.findAll { it.text.contains('helm push ') }
        assertEquals(2, pushes.size())
        assertEquals([
            ['push', '.pipeline/dist/chart-name-1.2.3.tgz', 'oci://ghcr.io/raulstechtips/charts'],
            ['push', '.pipeline/dist/chart-name-1.2.3.tgz', 'oci://harbor.prod.raulstechtips.dev/raulstechtips/charts']
        ], pushes.collect { PublishingShellProbe.argv(it, 'helm') })
        ['ghcr', 'harbor'].eachWithIndex { String target, int index ->
            String host = index == 0 ? 'ghcr.io' : 'harbor.prod.raulstechtips.dev'
            List targetCommands = commands.findAll { it.scopes == [target] }
            assertEquals(2, targetCommands.size(), 'Only login and push bind credentials')
            assertEquals(['registry', 'login', host, '-u', 'robot$' + target, '--password-stdin'],
                PublishingShellProbe.argv(targetCommands[0], 'helm'))
            assertTrue(targetCommands[0].text.startsWith('set +x\n'))
            assertTrue(targetCommands[0].text.contains('echo "$REG_PASS"'))
            assertTrue(targetCommands[0].text.contains('"$REG_USER"'))
            assertEquals(pushes[index], targetCommands[1])
            Map show = commands.find { it.text.contains('helm show chart') &&
                it.environment.CHART_REPO == 'oci://' + host + '/raulstechtips/charts' }
            assertEquals(['show', 'chart', 'oci://' + host + '/raulstechtips/charts/chart-name', '--version', '1.2.3'],
                PublishingShellProbe.argv(show, 'helm'))
            assertTrue(show.scopes.empty)
            assertFalse(show.environment.containsKey('REG_PASS'))
            assertTrue(commands.indexOf(pushes[index]) < commands.indexOf(show))
            assertTrue(targetCommands.every { it.container == 'helm' })
        }
        assertFalse(commands.any { it.text.contains('latest') })
        assertTrue(scopes.empty)
    }

    @Test
    void emptyTagPathLeavesValuesUntouchedAndNeedsNoDigest() {
        cfg['helm-image-tag-path'] = ''
        cfg['publish-targets'] = ['ghcr']
        loadScript('vars/helmPackageUpload.groovy').call(cfg, null)
        assertFalse(commands.any { it.text.contains('yq') })
        assertEquals(1, commands.count { it.text.contains('helm push ') })
    }

    @Test
    void rejectsWrongRemoteVersionEvenWhenHelmShowSucceeds() {
        remoteVersion = '9.9.9'
        def failure = assertThrows(IllegalArgumentException) {
            loadScript('vars/helmPackageUpload.groovy').call(cfg, digest)
        }
        assertTrue(failure.message.contains('version'))
        assertEquals(1, commands.count { it.text.contains('helm push ') })
        assertTrue(scopes.empty)
    }

    @Test
    void rejectsMissingRemoteVersion() {
        remoteVersion = ''
        assertThrows(IllegalArgumentException) {
            loadScript('vars/helmPackageUpload.groovy').call(cfg, digest)
        }
    }

    static List<Arguments> chartInputs() {
        ['tag-path', 'digest', 'version', 'chart-name', 'chart-repo', 'host'].collectMany { String field ->
            PublishingShellProbe.payloads.collect { String payload -> Arguments.of(field, payload) }
        }
    }

    @ParameterizedTest
    @MethodSource('chartInputs')
    void chartArgumentsStayInert(String field, String payload) {
        switch (field) {
            case 'tag-path': cfg['helm-image-tag-path'] = '.["' + payload + '"].image.tag'; break
            case 'digest': digest = payload; break
            case 'version': cfg.version = payload; remoteVersion = payload; break
            case 'chart-name': chartName = payload; break
            case 'chart-repo': registryOverrides['chart-repo'] = payload; break
            case 'host': registryOverrides.host = payload; break
        }
        loadScript('vars/helmPackageUpload.groovy').call(cfg, digest)
        Map tag = commands.find { it.text.contains('yq -i') }
        assertEquals(['-i', cfg['helm-image-tag-path'] + ' = strenv(TAG)', 'charts/values.yaml'],
            PublishingShellProbe.argv(tag, 'yq'))
        assertEquals(cfg.version + '@' + digest, tag.environment.TAG)
        Map pack = commands.find { it.text.contains('helm package') }
        assertEquals(['package', 'charts', '--version', cfg.version, '--destination', '.pipeline/dist'],
            PublishingShellProbe.argv(pack, 'helm'))
        String repo = registryOverrides['chart-repo'] ?: 'oci://ghcr.io/raulstechtips/charts'
        String host = registryOverrides.host ?: 'ghcr.io'
        Map login = commands.find { it.text.contains('helm registry login') }
        assertEquals(['registry', 'login', host, '-u', 'robot$ghcr', '--password-stdin'],
            PublishingShellProbe.argv(login, 'helm'))
        Map push = commands.find { it.text.contains('helm push ') }
        assertEquals(['push', '.pipeline/dist/' + chartName + '-' + cfg.version + '.tgz', repo],
            PublishingShellProbe.argv(push, 'helm'))
        Map show = commands.find { it.text.contains('helm show chart') }
        assertEquals(['show', 'chart', repo + '/' + chartName, '--version', cfg.version],
            PublishingShellProbe.argv(show, 'helm'))
    }
}

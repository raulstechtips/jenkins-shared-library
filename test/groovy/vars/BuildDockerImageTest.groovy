package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import groovy.json.JsonSlurper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.yaml.snakeyaml.Yaml
import static org.junit.jupiter.api.Assertions.*

class BuildDockerImageTest extends BasePipelineTest {
    private List<Map> commands
    private List<String> scopes
    private Map environment
    private String activeContainer
    private List checkouts
    private String digest
    private boolean executorFails
    private Map cfg
    private Map registries

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        commands = []
        scopes = []
        environment = [:]
        checkouts = []
        digest = 'sha256:' + 'a' * 64
        executorFails = false
        registries = new Yaml().load(new File('resources/dev/raulstechtips/jenkins/registries.yaml').text)
        cfg = ['project-name': 'example', version: '1.2.3',
            'publish-targets': ['ghcr', 'harbor'],
            'docker-image': [name: 'controller', dockerfile: 'plugins/Dockerfile', context: 'plugins']]
        binding.setVariable('scm', [branch: 'test'])
        helper.registerAllowedMethod('checkout', [Map]) { Map value ->
            assertNull(activeContainer, 'Checkout needs the default git-capable container')
            checkouts << value
        }
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            activeContainer = name
            try { body.call() } finally { activeContainer = null }
        }
        helper.registerAllowedMethod('libraryResource', [String]) { String path ->
            assertEquals('dev/raulstechtips/jenkins/registries.yaml', path)
            new File('resources/' + path).text
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args -> registries }
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
            scopes << target
            environment.REG_USER = 'robot$' + target
            environment.REG_PASS = 'synthetic-' + target + '-password'
            try { body.call() } finally {
                scopes.remove(scopes.size() - 1)
                environment = previous
            }
        }
        helper.registerAllowedMethod('sh', [String]) { String text ->
            commands << [text: text, container: activeContainer,
                scopes: new ArrayList(scopes), environment: new LinkedHashMap(environment)]
            if (executorFails && text.contains('/kaniko/executor')) {
                throw new IllegalStateException('executor failed')
            }
        }
        helper.registerAllowedMethod('readFile', [String]) { String path ->
            assertEquals('.pipeline/image.digest', path)
            digest
        }
        helper.registerAllowedMethod('error', [String]) { String message ->
            throw new IllegalArgumentException(message)
        }
    }

    @Test
    void buildsOnceToBothTargetsInsideNestedLoginsAndReturnsValidatedDigest() {
        assertEquals(digest, loadScript('vars/buildDockerImage.groovy').call(cfg))
        assertEquals([[branch: 'test']], checkouts)
        List executors = commands.findAll { it.text.contains('/kaniko/executor') }
        assertEquals(1, executors.size())
        Map executor = executors[0]
        assertEquals('kaniko', executor.container)
        assertEquals(['ghcr', 'harbor'], executor.scopes)
        assertEquals(2, executor.text.count('--destination '))
        assertEquals(['--context', 'plugins', '--dockerfile', 'plugins/Dockerfile',
            '--destination', 'ghcr.io/raulstechtips/controller:1.2.3',
            '--destination', 'harbor.prod.raulstechtips.dev/raulstechtips/controller:1.2.3',
            '--digest-file', '.pipeline/image.digest'], PublishingShellProbe.argv(executor, 'kaniko'))
        assertFalse(commands.any { it.text.contains('latest') })
        assertTrue(scopes.empty)
    }

    @Test
    void appendsEachAuthBeforeCredentialsAreShadowedAndNeverInterpolatesSecrets() {
        loadScript('vars/buildDockerImage.groovy').call(cfg)
        List entries = commands.findAll { it.text.contains('base64') }
        assertEquals(2, entries.size())
        assertEquals([['ghcr'], ['ghcr', 'harbor']], entries*.scopes)
        assertEquals(['ghcr.io', 'harbor.prod.raulstechtips.dev'], entries.collect { it.environment.REG_HOST })
        assertEquals(['robot$ghcr', 'robot$harbor'], entries.collect { it.environment.REG_USER })
        entries.each { Map entry ->
            assertTrue(entry.text.startsWith('set +x\n'))
            assertTrue(entry.text.contains('printf \'%s:%s\' "$REG_USER" "$REG_PASS" | base64 | tr -d \'\\n\''))
            assertTrue(entry.text.contains('/kaniko/.docker/config.json'))
        }
        commands.each { Map command ->
            assertEquals('kaniko', command.container)
            assertFalse(command.text.contains('robot$'))
            assertFalse(command.text.contains('synthetic-'))
            if (command.text.contains('config.json') && !command.text.contains('rm -f')) {
                assertFalse(command.scopes.empty)
                assertTrue(command.text.startsWith('set +x\n'))
                assertFalse(command.text.contains('.pipeline/config.json'))
                assertFalse(command.text.contains('$WORKSPACE'))
            }
        }
        assertTrue(commands.last().text.contains('rm -f /kaniko/.docker/config.json'))
    }

    @Test
    void usesNormalizedDockerImageDefaults() {
        cfg['docker-image'] = [name: 'example', dockerfile: 'Dockerfile', context: '.']
        cfg['publish-targets'] = ['ghcr']
        loadScript('vars/buildDockerImage.groovy').call(cfg)
        Map executor = commands.find { it.text.contains('/kaniko/executor') }
        assertEquals(['--context', '.', '--dockerfile', 'Dockerfile',
            '--destination', 'ghcr.io/raulstechtips/example:1.2.3',
            '--digest-file', '.pipeline/image.digest'], PublishingShellProbe.argv(executor, 'kaniko'))
        assertEquals(1, executor.text.count('--destination '))
    }

    @Test
    void rejectsMalformedDigestRatherThanTrustingSuccessfulExecutor() {
        ['sha256:abc', 'sha256:' + 'A' * 64, 'sha512:' + 'a' * 64,
            'sha256:' + 'a' * 64 + '\nextra', ''].each { String invalid ->
            digest = invalid
            def failure = assertThrows(IllegalArgumentException) {
                loadScript('vars/buildDockerImage.groovy').call(cfg)
            }
            assertTrue(failure.message.contains('digest'))
        }
    }

    @Test
    void trimsDigestFileNewline() {
        digest += '\n'
        assertEquals(digest.trim(), loadScript('vars/buildDockerImage.groovy').call(cfg))
    }

    @Test
    void executorFailurePropagatesAndRemovesAuthFile() {
        executorFails = true
        assertThrows(IllegalStateException) { loadScript('vars/buildDockerImage.groovy').call(cfg) }
        assertTrue(commands.last().text.contains('rm -f /kaniko/.docker/config.json'))
        assertTrue(scopes.empty)
    }

    static List<Arguments> imageInputs() {
        ['context', 'dockerfile', 'prefix', 'name', 'version'].collectMany { String field ->
            PublishingShellProbe.payloads.collect { String payload -> Arguments.of(field, payload) }
        }
    }

    @ParameterizedTest
    @MethodSource('imageInputs')
    void imageArgumentsStayInert(String field, String payload) {
        if (field == 'prefix') {
            registries.ghcr['image-prefix'] = payload
        } else if (field == 'version') {
            cfg.version = payload
        } else {
            cfg['docker-image'][field] = payload
        }
        loadScript('vars/buildDockerImage.groovy').call(cfg)
        Map command = commands.find { it.text.contains('/kaniko/executor') }
        assertEquals([
            '--context', cfg['docker-image'].context, '--dockerfile', cfg['docker-image'].dockerfile,
            '--destination', registries.ghcr['image-prefix'] + '/' + cfg['docker-image'].name + ':' + cfg.version,
            '--destination', registries.harbor['image-prefix'] + '/' + cfg['docker-image'].name + ':' + cfg.version,
            '--digest-file', '.pipeline/image.digest'
        ], PublishingShellProbe.argv(command, 'kaniko'))
    }

    @Test
    void authScriptsProduceBothEntriesWithoutTracingDerivedCredentials() {
        loadScript('vars/buildDockerImage.groovy').call(cfg)
        File directory = java.nio.file.Files.createTempDirectory('kaniko-auth-test').toFile()
        File config = new File(directory, 'config.json')
        try {
            commands.findAll { it.text.contains('config.json') && !it.text.contains('rm -f') }.each { Map command ->
                String text = command.text.replace('/kaniko/.docker/config.json', config.path)
                    .replace('mkdir -p /kaniko/.docker .pipeline', 'mkdir -p ' + directory.path)
                ProcessBuilder builder = new ProcessBuilder('sh', '-ex', '-c', text)
                builder.environment().putAll(command.environment as Map<String, String>)
                Process process = builder.redirectErrorStream(true).start()
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
                String output = process.inputStream.text
                assertEquals(0, process.exitValue(), output)
                assertFalse(output.contains('synthetic-'))
                assertFalse(output.contains('robot$'))
                assertFalse(output.contains('cm9ib3Qk'), 'Base64 auth must not appear in tracing')
            }
            Map auths = new JsonSlurper().parseText(config.text).auths
            assertEquals(['ghcr.io', 'harbor.prod.raulstechtips.dev'] as Set, auths.keySet())
            ['ghcr', 'harbor'].eachWithIndex { String target, int index ->
                String host = index == 0 ? 'ghcr.io' : 'harbor.prod.raulstechtips.dev'
                String expected = ('robot$' + target + ':synthetic-' + target + '-password').bytes.encodeBase64().toString()
                assertEquals([auth: expected], auths[host])
            }
        } finally {
            config.delete()
            directory.delete()
        }
    }
}

class PublishingShellProbe {
    static final List<String> payloads = [
        'with space', 'value; touch SENTINEL; #', '$(touch SENTINEL)',
        '`touch SENTINEL`', 'owner\'s"quote'
    ].asImmutable()

    static List<String> argv(Map command, String tool) {
        File directory = java.nio.file.Files.createTempDirectory('publish-argv-test').toFile()
        File argv = new File(directory, 'argv')
        try {
            String stub = tool + '''() { printf '%s\\000' "$@" > "$ARGV_FILE"; }\n'''
            String script = command.text.replace('/kaniko/executor', 'kaniko')
            ProcessBuilder builder = new ProcessBuilder('/bin/sh', '-ex', '-c', stub + script)
                .directory(directory).redirectErrorStream(true)
            builder.environment().putAll(command.environment as Map<String, String>)
            builder.environment().put('ARGV_FILE', argv.path)
            Process process = builder.start()
            try {
                assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS), 'Shell probe timed out')
                String output = process.inputStream.text
                assertFalse(new File(directory, 'SENTINEL').exists(), 'Shell evaluated an injected value')
                assertFalse(output.contains('synthetic-'), 'Shell traced registry credentials')
                assertEquals(0, process.exitValue(), output)
                assertTrue(argv.isFile(), 'Tool stub did not run')
                return argv.text.split('\u0000').toList()
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly()
                }
            }
        } finally {
            assertTrue(directory.deleteDir(), 'Shell probe scratch directory was not removed')
        }
    }
}

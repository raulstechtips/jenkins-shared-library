package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

class InstallMinioToolsTest extends BasePipelineTest {
    private static final String TOOLS_PATH = 'dev/raulstechtips/jenkins/tools.yaml'

    private List<Map> shCalls
    private String currentContainer
    private String toolsText

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        shCalls = []
        currentContainer = null
        toolsText = new File('resources/' + TOOLS_PATH).text
        binding.setVariable('env', [WORKSPACE: '/ws'])
        helper.registerAllowedMethod('libraryResource', [String]) { String path ->
            assertEquals(TOOLS_PATH, path)
            toolsText
        }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args -> new Yaml().load(args.text as String) }
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            currentContainer = name
            try {
                body.call()
            } finally {
                currentContainer = null
            }
        }
        helper.registerAllowedMethod('sh', [String]) { String script ->
            shCalls << [container: currentContainer, script: script]
            0
        }
        helper.registerAllowedMethod('error', [String]) { String message ->
            throw new IllegalArgumentException(message)
        }
    }

    private String install() {
        loadScript('vars/installMinioTools.groovy').call()
    }

    @Test
    void returnsTheBinFolderInTheWorkspace() {
        assertEquals('/ws/.minio-tools/bin', install())
    }

    @Test
    void toolsContainerDownloadsAndVerifiesBothPinnedBinaries() {
        install()

        assertEquals(1, shCalls.size())
        assertEquals('tools', shCalls[0].container)
        String script = shCalls[0].script
        Map pins = new Yaml().load(toolsText)
        assertTrue(script.startsWith('set -eu'), script)
        assertTrue(script.contains('bin="${WORKSPACE}/.minio-tools/bin"'), script)
        assertTrue(script.contains("curl -fsSL -o \"\$tmp/mc\" '${pins.mc.url}'".toString()), script)
        assertTrue(script.contains("curl -fsSL -o \"\$tmp/infisical.tar.gz\" '${pins.infisical.url}'".toString()), script)
        assertTrue(script.contains("echo '${pins.mc.sha256}  '\"\$tmp/mc\" | sha256sum -c -".toString()), script)
        assertTrue(script.contains("echo '${pins.infisical.sha256}  '\"\$tmp/infisical.tar.gz\" | sha256sum -c -".toString()), script)
        assertTrue(script.contains('trap \'rm -rf "$tmp"\' EXIT'), script)
    }

    @Test
    void verifiesBeforeUnpackingAndMakingExecutable() {
        install()

        String script = shCalls[0].script
        assertTrue(script.lastIndexOf('sha256sum') < script.indexOf('tar -xzf'), script)
        assertTrue(script.indexOf('tar -xzf') < script.indexOf('chmod +x'), script)
        assertTrue(script.contains('chmod +x "$bin/mc" "$bin/infisical"'), script)
    }

    @Test
    void malformedChecksumFailsBeforeAnyShell() {
        String sha = new Yaml().load(toolsText).mc.sha256
        toolsText = toolsText.replace(sha, 'zzzz')

        def failure = assertThrows(IllegalArgumentException) { install() }

        assertTrue(failure.message.contains('mc.sha256'), failure.message)
        assertTrue(shCalls.isEmpty())
    }

    @Test
    void missingToolEntryFailsBeforeAnyShell() {
        toolsText = toolsText.replace('infisical:', 'other:')

        def failure = assertThrows(IllegalArgumentException) { install() }

        assertTrue(failure.message.contains('infisical.url'), failure.message)
        assertTrue(shCalls.isEmpty())
    }

    @Test
    void hostileUrlStaysInertInScript() {
        String url = new Yaml().load(toolsText).mc.url
        toolsText = toolsText.replace(url, '"https://e.com/a b;$(id)"')

        install()

        assertTrue(shCalls[0].script.contains("'https://e.com/a b;\$(id)'"), shCalls[0].script)
    }
}

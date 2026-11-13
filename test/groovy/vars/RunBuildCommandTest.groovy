package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class RunBuildCommandTest extends BasePipelineTest {
    private List calls
    private String current

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        calls = []
        current = null
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body ->
            String previous = current
            current = name
            try {
                body.call()
            } finally {
                current = previous
            }
        }
        helper.registerAllowedMethod('sh', [String]) { String script -> calls << [current, script] }
    }

    @Test
    void runsCommandInCallerContainer() {
        loadScript('vars/runBuildCommand.groovy').call(['build-command': './gradlew build'], 'jdk')
        assertEquals([['jdk', './gradlew build']], calls)
    }

    @Test
    void usesTheContainerTheCallerPasses() {
        loadScript('vars/runBuildCommand.groovy').call(['build-command': 'make it'], 'other')
        assertEquals([['other', 'make it']], calls)
    }

    @Test
    void runsTheCommandVerbatimWithoutInterpolationOrExtraSteps() {
        String command = 'echo "\$HOME" && ./run.sh --flag \'x y\''
        loadScript('vars/runBuildCommand.groovy').call(['build-command': command], 'jdk')
        assertEquals([['jdk', command]], calls)
    }

    @Test
    void pythonRunsInsideGitHubAppCredentialsAndReferencesTokenOnlyByName() {
        List bindings = []
        String secret = 'ghs_literalTokenValue'
        helper.registerAllowedMethod('usernamePassword', [Map]) { Map args -> args }
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List args, Closure body ->
            bindings.addAll(args)
            current = current + '+credentials'
            binding.setVariable('env', [GH_TOKEN: secret])
            body.call()
        }
        loadScript('vars/runBuildCommand.groovy').call(['build-command': 'bash scripts/build-image-context.sh', type: 'python'], 'python')

        assertEquals([[credentialsId: 'github-app', usernameVariable: 'GH_USER', passwordVariable: 'GH_TOKEN']], bindings)
        assertEquals(1, calls.size())
        assertEquals('python+credentials', calls[0][0])
        String script = calls[0][1]
        assertTrue(script.startsWith('set +x\n'))
        assertTrue(script.contains('x-access-token:${GH_TOKEN}@github.com/'))
        assertTrue(script.endsWith('set -x\nbash scripts/build-image-context.sh'))
        assertFalse(script.contains(secret))
    }

    @Test
    void nodeEnablesCorepackPnpmWithoutCredentials() {
        helper.registerAllowedMethod('withCredentials', [List, Closure]) { List args, Closure body ->
            fail('node commands must not bind credentials')
        }
        loadScript('vars/runBuildCommand.groovy').call(['build-command': 'pnpm run it', type: 'node'], 'node')

        assertEquals(1, calls.size())
        assertEquals('node', calls[0][0])
        assertTrue(calls[0][1].startsWith('export COREPACK_ENABLE_DOWNLOAD_PROMPT=0'))
        assertTrue(calls[0][1].endsWith('corepack enable --install-directory "$HOME/.local/bin" pnpm\npnpm run it'))
    }
}

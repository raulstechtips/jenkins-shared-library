package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class RunTestCommandTest extends BasePipelineTest {
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
        loadScript('vars/runTestCommand.groovy').call(['test-command': './gradlew test'], 'shell')
        assertEquals([['shell', './gradlew test']], calls)
    }

    @Test
    void usesTheContainerTheCallerPasses() {
        loadScript('vars/runTestCommand.groovy').call(['test-command': 'make it'], 'other')
        assertEquals([['other', 'make it']], calls)
    }

    @Test
    void runsTheCommandVerbatimWithoutInterpolationOrExtraSteps() {
        String command = 'echo "\$HOME" && ./run.sh --flag \'x y\''
        loadScript('vars/runTestCommand.groovy').call(['test-command': command], 'shell')
        assertEquals([['shell', command]], calls)
    }
}

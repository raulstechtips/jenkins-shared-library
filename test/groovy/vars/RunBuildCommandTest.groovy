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
}

package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class ScmRevisionTest extends BasePipelineTest {
    private Script step

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        step = loadScript('vars/scmRevision.groovy')
    }

    @Test
    void readsActionAndReturnsPlainHeadAndBaseStrings() {
        Class actionClass = step.class.classLoader.parseClass(
            'package jenkins.scm.api; class SCMRevisionAction {}')
        def revision = new FakeRevision()
        def rawBuild = new FakeBuild(action: new FakeAction(revision: revision))
        binding.setVariable('currentBuild', [rawBuild: rawBuild])
        assertEquals([head: 'a1b2c3d012345', base: '9f8e7d6012345'], step.call())
        assertEquals(actionClass, rawBuild.requestedClass)
    }

    @Test
    void missingActionFailsWithExactMessage() {
        step.class.classLoader.parseClass('package jenkins.scm.api; class SCMRevisionAction {}')
        binding.setVariable('currentBuild', [rawBuild: new FakeBuild()])
        def error = assertThrows(IllegalArgumentException) { step.call() }
        assertEquals('Cannot read the PR head and base commits from the build.', error.message)
    }

    @Test
    void missingActionClassFailsWithExactMessage() {
        assertThrows(ClassNotFoundException) {
            step.class.classLoader.loadClass('jenkins.scm.api.SCMRevisionAction')
        }
        binding.setVariable('currentBuild', [rawBuild: new FakeBuild()])
        def error = assertThrows(IllegalArgumentException) { step.call() }
        assertEquals('Cannot read the PR head and base commits from the build.', error.message)
    }

    @Test
    void callResolvesActionByNameFromTheBuild() {
        step.class.classLoader.parseClass('package jenkins.scm.api; class SCMRevisionAction {}')
        def rawBuild = new FakeBuild(action: new FakeAction(revision: new FakeRevision()))
        binding.setVariable('currentBuild', [rawBuild: rawBuild])
        assertEquals([head: 'a1b2c3d012345', base: '9f8e7d6012345'], step.call())
        assertEquals('jenkins.scm.api.SCMRevisionAction', rawBuild.requestedClass.name)
    }

    @Test
    void missingRevisionFailsWithExactMessage() {
        step.class.classLoader.parseClass('package jenkins.scm.api; class SCMRevisionAction {}')
        binding.setVariable('currentBuild', [rawBuild: new FakeBuild(action: new FakeAction())])
        def error = assertThrows(IllegalArgumentException) { step.call() }
        assertEquals('Cannot read the PR head and base commits from the build.', error.message)
    }

    @Test
    void wrongRevisionTypeFailsWithExactMessage() {
        step.class.classLoader.parseClass('package jenkins.scm.api; class SCMRevisionAction {}')
        binding.setVariable('currentBuild', [rawBuild: new FakeBuild(action: new FakeAction(revision: new Object()))])
        def error = assertThrows(IllegalArgumentException) { step.call() }
        assertEquals('Cannot read the PR head and base commits from the build.', error.message)
    }

    static class FakeBuild {
        Object action
        Class requestedClass
        Object getAction(Class actionClass) {
            requestedClass = actionClass
            action
        }
    }

    static class FakeAction {
        Object revision
        Object getRevision() { revision }
    }

    static class FakeRevision {
        String getPullHash() { 'a1b2c3d012345' }
        String getBaseHash() { '9f8e7d6012345' }
    }
}

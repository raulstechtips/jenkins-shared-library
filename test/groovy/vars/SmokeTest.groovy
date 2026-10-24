package vars

import static com.lesfurets.jenkins.unit.global.lib.LibraryConfiguration.library
import static com.lesfurets.jenkins.unit.global.lib.ProjectSource.projectSource
import static org.junit.jupiter.api.Assertions.assertEquals
import static org.junit.jupiter.api.Assertions.assertTrue

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SmokeTest extends BasePipelineTest {

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        helper.registerSharedLibrary(library()
            .name('jenkins-shared-library')
            .retriever(projectSource())
            .targetPath('.')
            .defaultVersion('main')
            .allowOverride(true)
            .implicit(false)
            .build())
    }

    @Test
    void loadsLibraryFromRepoRootAndRunsFixtureVar() {
        def fixture = loadScript('test/resources/fixtures/smoke/smokeFixture.groovy')

        String credentialsId = fixture.call('ghcr')

        assertEquals('ghcr-push', credentialsId)
        assertTrue(helper.libLoader.libRecords.containsKey('jenkins-shared-library@main'),
            'library jenkins-shared-library@main was not loaded')
        assertTrue(helper.callStack.any { it.methodName == 'echo' && it.args[0].toString() == 'smoke: ghcr credentials-id is ghcr-push' },
            'fixture var did not echo the expected line')
    }
}

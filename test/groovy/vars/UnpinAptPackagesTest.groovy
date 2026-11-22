package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class UnpinAptPackagesTest extends BasePipelineTest {
    private Map<String, String> files
    private List<String> echoes
    private final Map cfg = ['docker-image': [dockerfile: 'Dockerfile.standalone-infisical']]

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        echoes = []
        files = ['Dockerfile.standalone-infisical':
            'RUN apt-get install -y infisical=0.43.90 \\\n    && apt-get install -y --only-upgrade libc6=2.41-12+deb13u3\n']
        helper.registerAllowedMethod('readFile', [String]) { String path -> files[path] }
        helper.registerAllowedMethod('writeFile', [Map]) { Map args -> files[args.file] = args.text }
        helper.registerAllowedMethod('echo', [String]) { String text -> echoes << text }
    }

    @Test
    void rewritesTheDockerfileAndReportsEachRemovedPin() {
        assertEquals(['libc6=2.41-12+deb13u3 -> libc6'],
            loadScript('vars/unpinAptPackages.groovy').call(cfg, ['infisical']))
        assertEquals('RUN apt-get install -y infisical=0.43.90 \\\n    && apt-get install -y --only-upgrade libc6\n',
            files['Dockerfile.standalone-infisical'])
        assertEquals('libc6=2.41-12+deb13u3 -> libc6\n', files['.pipeline/reports/apt-unpin.txt'])
        assertEquals(['Apt version pins removed from Dockerfile.standalone-infisical: 1',
            'libc6=2.41-12+deb13u3 -> libc6'], echoes)
    }

    @Test
    void reportsWhenNothingWasRemoved() {
        files['Dockerfile.standalone-infisical'] = 'RUN apt-get install -y curl\n'
        assertEquals([], loadScript('vars/unpinAptPackages.groovy').call(cfg, ['infisical']))
        assertEquals('No apt version pins removed.\n', files['.pipeline/reports/apt-unpin.txt'])
    }
}

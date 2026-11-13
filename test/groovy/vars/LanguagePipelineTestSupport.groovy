package vars

import com.lesfurets.jenkins.unit.declarative.DeclarativePipeline
import com.lesfurets.jenkins.unit.declarative.DeclarativePipelineTest
import com.lesfurets.jenkins.unit.declarative.GenericPipelineDeclaration
import org.junit.jupiter.api.BeforeEach
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

/**
 * Runs a repository pipeline var with the real config loader, pod builder and shared pipeline body,
 * and records the step vars it calls.
 */
abstract class LanguagePipelineTestSupport extends DeclarativePipelineTest {
    protected DeclarativePipeline declaration
    protected String configText
    protected boolean chartPresent
    protected Map observedConfig
    protected List<Map> calls
    protected List<Map> stashes
    protected final String digest = 'sha256:' + ('b' * 64)

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        declaration = null
        observedConfig = null
        chartPresent = false
        calls = []
        stashes = []
        binding.setVariable('env', [BRANCH_NAME: 'main', CHANGE_ID: null,
            CHANGE_BRANCH: null, BUILD_NUMBER: '13'])
        binding.setVariable('params', [RELEASE_VERSION: ''])
        binding.setVariable('scm', [url: 'mock-scm'])
        helper.registerAllowedMethod('merge', [], { -> 'merge' })
        helper.registerAllowedMethod('error', [String], { String message ->
            binding.getVariable('currentBuild').result = 'FAILURE'
            throw new IllegalArgumentException(message)
        })
        helper.registerAllowedMethod('pipeline', [Closure], { Closure body ->
            GenericPipelineDeclaration.binding = binding
            declaration = GenericPipelineDeclaration.createComponent(DeclarativePipeline, body)
            declaration.execute(delegate)
        })
        helper.registerAllowedMethod('readTrusted', [String], { String path ->
            if (path == 'project-pipeline-config.yaml') {
                return configText
            }
            assertEquals('charts/Chart.yaml', path)
            if (!chartPresent) {
                throw new FileNotFoundException(path)
            }
            'name: demo\nversion: 9.9.9\n'
        })
        helper.registerAllowedMethod('readYaml', [Map], { Map args ->
            assertEquals(['text'] as Set, args.keySet())
            new Yaml().load(args.text as String)
        })
        helper.registerAllowedMethod('libraryResource', [String], { String path ->
            getClass().getResource('/' + path).text
        })
        helper.registerAllowedMethod('sh', [Map], { Map args ->
            assertEquals('git log -1 --pretty=%an', args.script)
            'Raul Chakraborty\n'
        })
        helper.registerAllowedMethod('checkout', [Map], { Map scm -> })
        helper.registerAllowedMethod('stash', [Map], { Map args ->
            stashes << args
            record('stash', observedConfig)
        })
        helper.registerAllowedMethod('archiveArtifacts', [Map], { Map args -> })
        helper.registerAllowedMethod('logRotator', [Map], { Map args -> args })
        helper.registerAllowedMethod('buildDiscarder', [Map], { Map args -> })
        Script configLoader = loadScript('vars/loadProjectConfig.groovy')
        Script podBuilder = loadScript('vars/agentPodYaml.groovy')
        Script sharedPipeline = loadScript('vars/languageConfigurablePipeline.groovy')
        helper.registerAllowedMethod('loadProjectConfig', [Map], configLoader.&call)
        helper.registerAllowedMethod('agentPodYaml', [Map], podBuilder.&call)
        helper.registerAllowedMethod('languageConfigurablePipeline', [Map], sharedPipeline.&call)
        helper.registerAllowedMethod('computeVersion', [Map], { Map cfg ->
            record('computeVersion', cfg)
            '0.1.0-main-0067101-13'
        })
        ['renderChart', 'trivySecretScan', 'trivyDockerfileScan', 'opengrepScan',
            'helmTest', 'trivyChartScan', 'argoDeploy', 'updateChartVersion',
            'helmPackageUpload', 'helmInstall', 'helmCleanInstall'].each { String name ->
            helper.registerAllowedMethod(name, [Map], { Map cfg -> record(name, cfg) })
        }
        helper.registerAllowedMethod('buildDockerImage', [Map], { Map cfg ->
            record('buildDockerImage', cfg)
            digest
        })
        ['trivyImageScan', 'runBuildCommand', 'runTestCommand'].each { String name ->
            helper.registerAllowedMethod(name, [Map, String], { Map cfg, String argument ->
                record(name, cfg, argument)
            })
        }
        binding.setVariable('releasePipeline', [
            prepare: { Map cfg -> record('prepare', cfg) },
            publish: { Map cfg -> record('publish', cfg) }
        ])
        binding.setVariable('trivyImageScan', [
            upstreamImages: { Map cfg -> [] }
        ])
    }

    protected void record(String name, Map cfg, String argument = null) {
        observedConfig = cfg
        calls << [name: name, argument: argument, type: cfg.type, stage: leafStages().last()]
    }

    protected Map callNamed(String name) { calls.find { it.name == name } }

    protected String fixture(String name) { getClass().getResource("/fixtures/config/${name}.yaml").text }

    protected List<String> leafStages() {
        helper.callStack.findAll { it.methodName == 'stage' }
            .collect { it.args[0].toString() }.findAll { !(it in ['Pipeline', 'Early checks']) }
    }

    protected Map pod() {
        new Yaml().load(declaration.stages['Pipeline'].agent.kubernetes.yaml as String)
    }

    protected Map tools() {
        new Yaml().load(new File('resources/dev/raulstechtips/jenkins/tools.yaml').text)
    }
}

package vars

import com.lesfurets.jenkins.unit.BasePipelineTest
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml
import static org.junit.jupiter.api.Assertions.*

class ShellQuotingTest extends BasePipelineTest {
    private Map cfg
    private Map chart
    private List files
    private List<String> commands

    @Override
    @BeforeEach
    void setUp() throws Exception {
        super.setUp()
        cfg = [chartPresent: true, 'project-name': 'demo', 'project-namespace': 'team']
        chart = [name: 'demo']
        files = []
        commands = []
        helper.registerAllowedMethod('container', [String, Closure]) { String name, Closure body -> body.call() }
        helper.registerAllowedMethod('readYaml', [Map]) { Map args ->
            assertEquals([file: 'charts/Chart.yaml'], args)
            new Yaml().load(new Yaml().dump(chart))
        }
        helper.registerAllowedMethod('findFiles', [Map]) { Map args -> files }
        helper.registerAllowedMethod('fileExists', [String]) { String path -> false }
        helper.registerAllowedMethod('sh', [String]) { String command -> commands.add(command) }
    }

    private void checkShell(String step, Closure verify) {
        loadScript("vars/${step}.groovy").call(cfg)
        File workspace = Files.createTempDirectory('devsecops34-t9-shell-').toFile()
        try {
            File log = new File(workspace, 'argv')
            File output = new File(workspace, 'output')
            String stubs = '''
record() {
    printf '%s\\000' "$#" "$@" >> "$ARGV_LOG"
}
helm() {
    record helm "$@"
    printf 'rendered\\n'
}
kubeconform() {
    record kubeconform "$@"
}
'''
            ProcessBuilder builder = new ProcessBuilder('/bin/sh', '-e', '-c',
                stubs + '\n' + commands.join('\n'))
            builder.directory(workspace)
            builder.environment().put('ARGV_LOG', log.absolutePath)
            builder.redirectErrorStream(true)
            builder.redirectOutput(output)
            Process process = builder.start()
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), 'shell probe timed out')
                assertFalse(new File(workspace, 'injected').exists(),
                    'repository data executed an injected command: ' + commands)
                assertEquals(0, process.exitValue(), output.text)
            } finally {
                if (process.isAlive()) {
                    process.destroyForcibly()
                    process.waitFor(10, TimeUnit.SECONDS)
                }
            }
            List<String> tokens = log.text.split('\u0000', -1).toList()
            List<List<String>> calls = []
            int cursor = 0
            while (cursor < tokens.size() - 1) {
                int count = tokens[cursor++].toInteger()
                calls.add(new ArrayList<String>(tokens.subList(cursor, cursor + count)))
                cursor += count
            }
            verify.call(calls, workspace)
        } finally {
            assertTrue(workspace.deleteDir(), 'temporary shell workspace cleanup failed')
        }
    }

    private void checkCiPath(String step, String name) {
        files = [[path: "charts/ci/${name}", name: name]]
        checkShell(step) { List calls, File workspace ->
            if (step == 'renderChart') {
                assertEquals(['helm', 'template', 'demo', 'charts', '-n', 'team',
                    '-f', "charts/ci/${name}".toString()], calls.last())
                File rendered = new File(workspace, ".pipeline/render/ci-${name - '.yaml'}.yaml")
                assertTrue(rendered.isFile(), 'render output path was split or interpreted')
                assertEquals('rendered\n', rendered.text)
            } else {
                assertEquals(['helm', 'lint', '--strict', 'charts', '-f',
                    "charts/ci/${name}".toString()], calls.last())
            }
        }
    }

    @Test void renderPathsWithSpacesAndApostrophesStaySingleArguments() {
        checkCiPath('renderChart', "owner's space-values.yaml")
    }

    @Test void renderPathsCannotExecuteSemicolonCommands() {
        checkCiPath('renderChart', 'x; : > injected; #-values.yaml')
    }

    @Test void renderPathsCannotExecuteCommandSubstitutions() {
        checkCiPath('renderChart', 'x$(: > injected)` : > injected`-values.yaml')
    }

    @Test void lintPathsWithSpacesAndApostrophesStaySingleArguments() {
        checkCiPath('helmLint', "owner's space-values.yaml")
    }

    @Test void lintPathsCannotExecuteSemicolonCommands() {
        checkCiPath('helmLint', 'x; : > injected; #-values.yaml')
    }

    @Test void lintPathsCannotExecuteCommandSubstitutions() {
        checkCiPath('helmLint', 'x$(: > injected)` : > injected`-values.yaml')
    }

    private void checkRenderedPath(String name) {
        String path = ".pipeline/render/${name}"
        files = [[path: path, name: name]]
        checkShell('kubeconformCheck') { List calls, File workspace ->
            assertEquals([['kubeconform', '-strict', '-summary', '-schema-location', 'default',
                '-schema-location',
                'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json',
                '-skip', 'InfisicalSecret,InfisicalStaticSecret,InfisicalPushSecret,apiextensions.k8s.io/v1/CustomResourceDefinition',
                path]], calls)
        }
    }

    @Test void renderedPathsWithSpacesAndApostrophesStaySingleArguments() {
        checkRenderedPath("ci-owner's space-values.yaml")
    }

    @Test void renderedPathsCannotExecuteSemicolonCommands() {
        checkRenderedPath('ci-x; : > injected; #-values.yaml')
    }

    @Test void renderedPathsCannotExecuteCommandSubstitutions() {
        checkRenderedPath('ci-x$(: > injected)` : > injected`-values.yaml')
    }

    private void checkRepository(String step, String repository) {
        chart.dependencies = [[repository: 'oci://registry.example.org/charts'], [repository: repository]]
        checkShell(step) { List calls, File workspace ->
            assertEquals(['helm', 'repo', 'add', 'dep1', repository], calls.first())
            assertEquals(1, calls.count { it[1] == 'repo' })
        }
    }

    @Test void renderRepositoriesWithSpacesAndApostrophesStaySingleArguments() {
        checkRepository('renderChart', "https://charts.example.org/owner's space")
    }

    @Test void renderRepositoriesCannotExecuteSemicolonCommands() {
        checkRepository('renderChart', 'https://charts.example.org; : > injected; #')
    }

    @Test void renderRepositoriesCannotExecuteCommandSubstitutions() {
        checkRepository('renderChart', 'https://charts.example.org/$(: > injected)` : > injected`')
    }

    @Test void dependencyRepositoriesWithSpacesAndApostrophesStaySingleArguments() {
        checkRepository('helmDependencyBuild', "https://charts.example.org/owner's space")
    }

    @Test void dependencyRepositoriesCannotExecuteSemicolonCommands() {
        checkRepository('helmDependencyBuild', 'https://charts.example.org; : > injected; #')
    }

    @Test void dependencyRepositoriesCannotExecuteCommandSubstitutions() {
        checkRepository('helmDependencyBuild', 'https://charts.example.org/$(: > injected)` : > injected`')
    }

    @Test void projectNameAndNamespaceStaySingleArguments() {
        cfg['project-name'] = "owner's project"
        cfg['project-namespace'] = "owner's namespace"
        checkShell('renderChart') { List calls, File workspace ->
            assertEquals([['helm', 'dependency', 'build', 'charts'],
                ['helm', 'template', "owner's project", 'charts', '-n', "owner's namespace"]], calls)
            assertTrue(new File(workspace, '.pipeline/render/default.yaml').isFile())
        }
    }

    @Test void projectNameAndFallbackNamespaceCannotExecuteCommands() {
        cfg['project-name'] = 'demo; : > injected; #'
        cfg['project-namespace'] = null
        checkShell('renderChart') { List calls, File workspace ->
            assertEquals(['helm', 'template', cfg['project-name'], 'charts', '-n',
                cfg['project-name']], calls.last())
        }
    }
}

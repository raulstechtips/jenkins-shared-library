package dev.raulstechtips.jenkins.config

import org.junit.jupiter.api.Test
import org.yaml.snakeyaml.Yaml

import static org.junit.jupiter.api.Assertions.*

class ConfigValidatorTest {
    private Object fixture(String name) {
        getClass().getResourceAsStream("/fixtures/config/${name}.yaml").withCloseable {
            new Yaml().load(it)
        }
    }

    private Map valid(String name, String type) {
        Object raw = fixture(name)
        Map cfg = ConfigValidator.validate(raw, type)
        Set expected = ['project-name', 'project-description', 'project-namespace',
            'publish-targets', 'test-command', 'helm-overrides', 'trivy-fail-on',
            'opengrep-fail-on', 'helm-image-tag-path', 'docker-image', 'pipeline-steps'] as Set
        if (type == 'java') {
            expected.add('build-command')
        }
        assertEquals(expected, cfg.keySet())
        assertEquals(['name', 'dockerfile', 'context'] as Set, cfg['docker-image'].keySet())
        Set flags = ['helm-test', 'trivy-scan', 'opengrep-scan', 'unit-test',
            'build-docker-image', 'helm-package-upload', 'helm-install',
            'helm-clean-install', 'argo-deploy'] as Set
        if (type == 'java') {
            flags.add('build')
        }
        assertEquals(flags, cfg['pipeline-steps'].keySet())
        assertTrue(cfg['pipeline-steps'].values().every { it instanceof Boolean })
        assertNotSame(raw['project-config'], cfg)
        cfg
    }

    private String invalid(String name, String path, String type = 'cluster') {
        def ex = assertThrows(IllegalArgumentException) {
            ConfigValidator.validate(fixture(name), type)
        }
        assertTrue(ex.message.contains(path), ex.message)
        ex.message
    }

    @Test void argocdExampleIsValidAndNormalized() { valid('argocd', 'cluster') }
    @Test void jenkinsExampleIsValidAndNormalized() { valid('jenkins', 'cluster') }
    @Test void libraryExampleIsValidAndNormalized() { valid('jenkins-shared-library', 'java') }
    @Test void suggestsNearestFlagForTypo() {
        assertEquals("Unknown key 'project-config.pipeline-steps.trivy-scna'. Did you mean 'trivy-scan'?",
            invalid('unknown-flag', 'project-config.pipeline-steps.trivy-scna'))
    }
    @Test void quotedTrueIsNotBoolean() {
        String message = invalid('quoted-true', 'project-config.pipeline-steps.helm-test')
        assertTrue(message.contains('boolean'), message)
        assertTrue(message.contains('string'), message)
    }
    @Test void projectNameIsRequired() { invalid('missing-project-name', 'project-config.project-name') }
    @Test void installNeedsNamespace() { invalid('install-without-namespace', 'project-config.project-namespace') }
    @Test void unitTestNeedsCommand() { invalid('unit-test-without-command', 'project-config.test-command') }
    @Test void clusterRejectsBuildFlag() { invalid('cluster-build', 'project-config.pipeline-steps.build') }
    @Test void tagPathNeedsImageBuild() { invalid('tag-without-image', 'project-config.helm-image-tag-path') }
    @Test void targetsMustBeUnique() { invalid('duplicate-targets', 'project-config.publish-targets') }
    @Test void chartPublishNeedsTargets() { invalid('chart-without-targets', 'project-config.publish-targets') }
    @Test void trivySeverityMustBeKnown() { invalid('trivy-severity', 'project-config.trivy-fail-on') }
    @Test void collectsEveryErrorOnSeparateLines() {
        String message = invalid('two-errors', 'project-config.project-name')
        assertTrue(message.contains('project-config.pipeline-steps.helm-test'), message)
        assertEquals(2, message.readLines().size())
    }

    @Test void rejectsUnknownRootKey() { invalid('unknown-root', 'project-confgi') }
    @Test void rejectsUnknownProjectKey() { invalid('unknown-project-key', 'project-config.project-nam') }
    @Test void rejectsUnknownDockerKey() { invalid('unknown-docker-key', 'project-config.docker-image.dockerfil') }
    @Test void omitsSuggestionBeyondThreeEdits() {
        assertFalse(invalid('distant-key', 'project-config.unrecognizable').contains('Did you mean'))
    }
    @Test void rootKeyIsRequired() { invalid('missing-root', 'project-config') }
    @Test void projectNameMustMatchPattern() { invalid('invalid-name', 'project-config.project-name') }
    @Test void projectNameHasHelmLengthLimit() { invalid('long-name', 'project-config.project-name') }
    @Test void namespaceMustMatchPattern() { invalid('invalid-namespace', 'project-config.project-namespace') }
    @Test void targetsMustBeKnown() { invalid('unknown-target', 'project-config.publish-targets') }
    @Test void imagePublishNeedsTargets() { invalid('image-without-targets', 'project-config.publish-targets') }
    @Test void cleanInstallNeedsNamespace() { invalid('clean-without-namespace', 'project-config.project-namespace') }
    @Test void javaBuildNeedsCommand() { invalid('build-without-command', 'project-config.build-command', 'java') }
    @Test void clusterRejectsBuildCommand() { invalid('cluster-build-command', 'project-config.build-command') }
    @Test void opengrepSeverityMustBeKnown() { invalid('opengrep-severity', 'project-config.opengrep-fail-on') }
    @Test void enabledImageNameMustMatchPattern() { invalid('invalid-image-name', 'project-config.docker-image.name') }
    @Test void disabledImageNameDoesNotApplyPattern() {
        Map cfg = ConfigValidator.validate(['project-config': ['project-name': 'demo',
            'docker-image': [name: 'Ignored/WHEN-disabled']]], 'cluster')
        assertEquals('Ignored/WHEN-disabled', cfg['docker-image'].name)
    }
    @Test void tagPathMustStartWithDot() { invalid('invalid-tag-path', 'project-config.helm-image-tag-path') }
    @Test void overridePathsMustBeRelative() { invalid('absolute-override', 'project-config.helm-overrides') }
    @Test void dockerfileMustBeRelative() { invalid('absolute-dockerfile', 'project-config.docker-image.dockerfile') }
    @Test void contextMustStayUnderRepositoryRoot() { invalid('parent-context', 'project-config.docker-image.context') }
    @Test void emptyRequiredCommandIsRejected() { invalid('empty-command', 'project-config.test-command') }

    @Test void malformedRootIsNotMap() { invalid('root-list', 'root') }
    @Test void malformedProjectConfigIsList() { invalid('project-list', 'project-config') }
    @Test void malformedPipelineStepsIsString() { invalid('steps-string', 'project-config.pipeline-steps') }
    @Test void malformedEmptyFile() { invalid('empty', 'root') }
    @Test void malformedNullValuesAreRejected() {
        String message = invalid('null-values', 'project-config.project-name')
        assertTrue(message.contains('project-config.pipeline-steps'), message)
        assertTrue(message.contains('null'), message)
    }
    @Test void wrongStringTypeNamesExpectedAndFoundTypes() {
        String message = invalid('name-number', 'project-config.project-name')
        assertTrue(message.contains('string'), message)
        assertTrue(message.contains('number'), message)
    }
    @Test void listMustBeList() { invalid('targets-string', 'project-config.publish-targets') }
    @Test void dockerImageMustBeMap() { invalid('docker-list', 'project-config.docker-image') }
    @Test void listItemsMustBeStrings() { invalid('override-number', 'project-config.helm-overrides[0]') }
    @Test void allOptionalScalarTypesAreChecked() {
        ['project-description', 'project-namespace', 'test-command', 'helm-image-tag-path'].each { key ->
            def ex = assertThrows(IllegalArgumentException) {
                ConfigValidator.validate(['project-config': ['project-name': 'demo', (key): false]], 'cluster')
            }
            assertTrue(ex.message.contains("project-config.${key}"), ex.message)
        }
    }
    @Test void everySuppliedNullIsRejectedRatherThanDefaulted() {
        ConfigSchema.tree('java')['project-config'].children.keySet().each { String key ->
            def ex = assertThrows(IllegalArgumentException) {
                ConfigValidator.validate(['project-config': ['project-name': 'demo', (key): null]], 'java')
            }
            assertTrue(ex.message.contains("project-config.${key}"), ex.message)
            assertTrue(ex.message.contains('found null'), ex.message)
        }
    }
    @Test void malformedNestedNullValuesNeverCauseNullPointerExceptions() {
        ['docker-image': ['name', 'dockerfile', 'context'], 'pipeline-steps': ['helm-test']].each { key, fields ->
            fields.each { field ->
                def ex = assertThrows(IllegalArgumentException) {
                    ConfigValidator.validate(['project-config': ['project-name': 'demo', (key): [(field): null]]], 'java')
                }
                assertTrue(ex.message.contains("project-config.${key}.${field}"), ex.message)
            }
        }
    }
    @Test void listEnumsRejectNonStringItems() {
        ['publish-targets', 'trivy-fail-on', 'opengrep-fail-on'].each { String key ->
            def ex = assertThrows(IllegalArgumentException) {
                ConfigValidator.validate(['project-config': ['project-name': 'demo', (key): [true]]], 'cluster')
            }
            assertTrue(ex.message.contains("project-config.${key}[0]"), ex.message)
            assertTrue(ex.message.contains('found boolean'), ex.message)
        }
    }
    @Test void acceptsEveryEnumAndPreservesOverrideOrder() {
        Map cfg = ConfigValidator.validate(['project-config': [
            'project-name': 'a',
            'project-namespace': 'ns-1',
            'publish-targets': ['ghcr', 'harbor'],
            'trivy-fail-on': ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'UNKNOWN'],
            'opengrep-fail-on': ['ERROR', 'WARNING', 'INFO'],
            'helm-overrides': ['charts/values.yaml', 'charts/ci/all-on-values.yaml'],
            'test-command': './test.sh',
            'build-command': './build.sh',
            'helm-image-tag-path': '.image.tag',
            'pipeline-steps': ['helm-install': true, 'helm-clean-install': true,
                'unit-test': true, build: true, 'build-docker-image': true, 'helm-package-upload': true]
        ]], 'java')
        assertEquals(['charts/values.yaml', 'charts/ci/all-on-values.yaml'], cfg['helm-overrides'])
        assertEquals(['ghcr', 'harbor'], cfg['publish-targets'])
    }
    @Test void acceptsMaximumProjectNameLength() {
        assertEquals('a' * 53, ConfigValidator.validate(['project-config': ['project-name': 'a' * 53]], 'cluster')['project-name'])
    }
    @Test void allDockerScalarTypesAreChecked() {
        ['name', 'dockerfile', 'context'].each { key ->
            def ex = assertThrows(IllegalArgumentException) {
                ConfigValidator.validate(['project-config': ['project-name': 'demo',
                    'docker-image': [(key): []]]], 'cluster')
            }
            assertTrue(ex.message.contains("project-config.docker-image.${key}"), ex.message)
        }
    }
    @Test void defaultsAreCompleteAndIndependentOfInputsAndOtherCalls() {
        Map raw = ['project-config': ['project-name': 'demo', 'publish-targets': ['ghcr'],
            'docker-image': [context: 'image'], 'pipeline-steps': ['trivy-scan': true]]]
        Map cfg = ConfigValidator.validate(raw, 'java')
        assertEquals('', cfg['project-description'])
        assertNull(cfg['project-namespace'])
        assertNull(cfg['test-command'])
        assertNull(cfg['build-command'])
        assertEquals([], cfg['helm-overrides'])
        assertEquals([], cfg['trivy-fail-on'])
        assertEquals([], cfg['opengrep-fail-on'])
        assertEquals('', cfg['helm-image-tag-path'])
        assertEquals([name: 'demo', dockerfile: 'Dockerfile', context: 'image'], cfg['docker-image'])
        assertEquals(false, cfg['pipeline-steps'].build)
        cfg['publish-targets'].add('harbor')
        cfg['docker-image'].context = 'changed'
        cfg['pipeline-steps']['trivy-scan'] = false
        cfg['helm-overrides'].add('values.yaml')
        assertEquals(['ghcr'], raw['project-config']['publish-targets'])
        assertEquals('image', raw['project-config']['docker-image'].context)
        assertTrue(raw['project-config']['pipeline-steps']['trivy-scan'])
        assertEquals([], ConfigValidator.validate(['project-config': ['project-name': 'next']], 'java')['helm-overrides'])
    }
    @Test void nearestKeyUsesSmallestDistanceAndThreshold() {
        assertEquals('trivy-scan', NearestKey.suggest('trivy-scna', ['unit-test', 'trivy-scan']))
        assertNull(NearestKey.suggest('zzzzzzzzzz', ['unit-test', 'trivy-scan']))
        assertEquals('abc', NearestKey.suggest('abcdef', ['abc']))
        assertNull(NearestKey.suggest('abcdefg', ['abc']))
    }
    @Test void unsupportedPipelineTypeIsClearError() {
        def ex = assertThrows(IllegalArgumentException) {
            ConfigValidator.validate(['project-config': ['project-name': 'demo']], 'python')
        }
        assertTrue(ex.message.contains('type'), ex.message)
    }
}

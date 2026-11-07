package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory

import static org.junit.jupiter.api.Assertions.*

class PipelineStepsTest {
    // These are the original stage expressions at df97fff, not expectations derived from RULES.
    // Shared stage names have the same expression in both pipeline vars.
    private static final Map OLD_CONDITIONS = [
        // Render: old when expression, beforeAgent absent.
        'render': { Map cfg, Map env ->
            !cfg.skipAll && cfg.chartPresent
        },
        // Early checks: old when expression, beforeAgent absent.
        'early-checks': { Map cfg, Map env ->
            !cfg.skipAll
        },
        // Trivy secret scan: old when expression, beforeAgent absent.
        'trivy-secret-scan': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['trivy-scan']
        },
        // Trivy Dockerfile scan: old when expression, beforeAgent absent.
        'trivy-dockerfile-scan': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['trivy-scan']
        },
        // Opengrep scan: old when expression, beforeAgent absent.
        'opengrep-scan': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['opengrep-scan']
        },
        // Helm test: old when expression, beforeAgent absent.
        'helm-test': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['helm-test']
        },
        // Build image: old when expression, beforeAgent true.
        'build-docker-image': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['build-docker-image']
        },
        // Unit test: old when expression, beforeAgent absent.
        'unit-test': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['unit-test']
        },
        // Trivy image scan: old when expression, beforeAgent absent.
        'trivy-image-scan': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['trivy-scan'] &&
                (cfg.digest || cfg.upstreamImages)
        },
        // Package and upload chart: old when expression, beforeAgent absent.
        'helm-package-upload': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['helm-package-upload']
        },
        // Trivy chart scan: old when expression, beforeAgent absent.
        'trivy-chart-scan': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['trivy-scan'] &&
                cfg['pipeline-steps']['helm-package-upload']
        },
        // Helm clean install: old when expression, beforeAgent true.
        'helm-clean-install': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['helm-clean-install'] &&
                env.BRANCH_NAME != 'main' && !cfg.releaseVersion
        },
        // Helm install: old when expression, beforeAgent true.
        'helm-install': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['helm-install'] &&
                !cfg['pipeline-steps']['helm-clean-install'] &&
                env.BRANCH_NAME != 'main' && !cfg.releaseVersion
        },
        // Argo deploy: old when expression, beforeAgent absent.
        'argo-deploy': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['argo-deploy'] &&
                ((env.BRANCH_NAME == 'main' && !env.CHANGE_ID) || cfg.releaseVersion)
        },
        // Release: old when expression, beforeAgent absent.
        'release': { Map cfg, Map env ->
            !cfg.skipAll && cfg.releaseVersion
        },
        // Trivy scan: old when expression, beforeAgent absent.
        'trivy-scan': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['trivy-scan']
        },
        // Build: old when expression, beforeAgent absent.
        'build': { Map cfg, Map env ->
            !cfg.skipAll && cfg['pipeline-steps']['build']
        }
    ]

    // Independent input dimensions from the old expressions, including both combined flags.
    private static final Map FLAGS = [
        'trivy-secret-scan': ['trivy-scan'],
        'trivy-dockerfile-scan': ['trivy-scan'],
        'trivy-scan': ['trivy-scan'],
        'opengrep-scan': ['opengrep-scan'],
        'build': ['build'],
        'unit-test': ['unit-test'],
        'helm-test': ['helm-test'],
        'build-docker-image': ['build-docker-image'],
        'trivy-image-scan': ['trivy-scan'],
        'helm-package-upload': ['helm-package-upload'],
        'trivy-chart-scan': ['trivy-scan', 'helm-package-upload'],
        'helm-clean-install': ['helm-clean-install'],
        'helm-install': ['helm-install', 'helm-clean-install'],
        'argo-deploy': ['argo-deploy']
    ]

    @TestFactory
    List<DynamicTest> everyStepMatchesItsOldCondition() {
        List<DynamicTest> tests = []
        List<Map> builds = [
            [kind: 'branch', branch: 'feature/DEVSECOPS-34-x', change: null, release: ''],
            [kind: 'main', branch: 'main', change: null, release: ''],
            [kind: 'pr', branch: 'PR-42', change: '42', release: ''],
            // The old install condition excludes this PR despite its build kind being pr.
            [kind: 'pr', branch: 'main', change: '42', release: ''],
            [kind: 'release', branch: 'main', change: null, release: '1.3.0']
        ]
        OLD_CONDITIONS.each { String step, Closure oldCondition ->
            List<String> flags = FLAGS[step] ?: []
            List<Map> inputs = [[chartPresent: false, digest: null, upstreamImages: []]]
            if (step == 'render') {
                inputs = [false, true].collect { [chartPresent: it] }
            } else if (step == 'trivy-image-scan') {
                inputs = []
                for (String digest : [null, 'sha256:' + ('a' * 64)]) {
                    for (List images : [[], ['quay.io/example/service:stable']]) {
                        inputs.add([digest: digest, upstreamImages: images])
                    }
                }
            }
            for (Map build : builds) {
                for (int bits = 0; bits < (1 << flags.size()); bits++) {
                    Map enabled = [:]
                    for (int index = 0; index < flags.size(); index++) {
                        enabled[flags[index]] = (bits & (1 << index)) != 0
                    }
                    for (boolean skip : [false, true]) {
                        for (Map input : inputs) {
                            for (String type : ['cluster', 'java']) {
                                Map cfg = [type: type, buildKind: build.kind,
                                    nonMainBranch: build.branch != 'main', releaseVersion: build.release,
                                    skipAll: skip, 'pipeline-steps': enabled] + input
                                Map env = [BRANCH_NAME: build.branch, CHANGE_ID: build.change]
                                boolean expected = oldCondition.call(cfg, env) as boolean
                                String name = "${step} ${type} ${build} flags=${enabled} skipAll=${skip} inputs=${input}"
                                tests.add(decisionCase(name, expected, cfg, step))
                            }
                        }
                    }
                }
            }
        }
        assertEquals(OLD_CONDITIONS.keySet(), PipelineSteps.RULES.keySet())
        return tests
    }

    @TestFactory
    List<DynamicTest> buildKindUsesReleaseThenPrThenMainThenBranch() {
        List<DynamicTest> tests = []
        for (String branch : [null, '', 'main', 'feature/DEVSECOPS-34-x']) {
            for (String change : [null, '', '42']) {
                for (String release : [null, '', '1.3.0']) {
                    String expected = release ? 'release' : change ? 'pr' : branch == 'main' ? 'main' : 'branch'
                    tests.add(buildKindCase(branch, change, release, expected))
                }
            }
        }
        return tests
    }

    private static DynamicTest decisionCase(String name, boolean expected, Map cfg, String step) {
        return DynamicTest.dynamicTest(name, {
            assertEquals(expected, PipelineSteps.shouldRun(cfg, step))
        })
    }

    private static DynamicTest buildKindCase(String branch, String change, String release, String expected) {
        return DynamicTest.dynamicTest("${branch}/${change}/${release}", {
            assertEquals(expected, PipelineSteps.buildKind(branch, change, release))
        })
    }

    @Test
    void unknownStepThrowsEvenWhenAllStepsAreSkipped() {
        for (boolean skip : [false, true]) {
            def failure = assertThrows(IllegalArgumentException) {
                PipelineSteps.shouldRun([skipAll: skip], 'unknown')
            }
            assertEquals("Unknown pipeline step 'unknown'", failure.message)
        }
        assertThrows(IllegalArgumentException) { PipelineSteps.plan([:], ['unknown']) }
    }

    @Test
    void planPreservesSuppliedOrderAndDoesNotMutateInputs() {
        Map cfg = [buildKind: 'main', 'pipeline-steps': ['unit-test': true, build: false]]
        List<String> steps = ['unit-test', 'build', 'early-checks', 'unit-test']
        assertEquals(['unit-test', 'early-checks', 'unit-test'], PipelineSteps.plan(cfg, steps))
        assertEquals(['unit-test', 'build', 'early-checks', 'unit-test'], steps)
        assertEquals([buildKind: 'main', 'pipeline-steps': ['unit-test': true, build: false]], cfg)
        assertEquals([], PipelineSteps.plan(cfg, []))
        cfg.skipAll = true
        assertEquals([], PipelineSteps.plan(cfg, steps))
    }

    @Test
    void planAndDecisionsReadImageInputsAtTheTimeOfTheCall() {
        Map cfg = [buildKind: 'branch', 'pipeline-steps': ['trivy-scan': true]]
        assertEquals([], PipelineSteps.plan(cfg, ['trivy-image-scan']))
        cfg.upstreamImages = ['quay.io/example/service:stable']
        assertEquals(['trivy-image-scan'], PipelineSteps.plan(cfg, ['trivy-image-scan']))
        cfg.upstreamImages = []
        cfg.digest = 'sha256:' + ('a' * 64)
        assertTrue(PipelineSteps.shouldRun(cfg, 'trivy-image-scan'))
    }

    @Test
    void rulesAreImmutableAtEveryLevelAndContainOnlyAllowedDataFields() {
        assertTrue(new PipelineSteps() instanceof Serializable)
        assertThrows(UnsupportedOperationException) { PipelineSteps.RULES.put('other', [:]) }
        PipelineSteps.RULES.each { String step, Map row ->
            assertTrue(['flag', 'alsoFlag', 'unlessFlag', 'on', 'needs'].containsAll(row.keySet()))
            assertThrows(UnsupportedOperationException) { row.put('flag', 'other') }
            row.values().each { Object value ->
                assertTrue(value instanceof String || value instanceof List)
                if (value instanceof List) {
                    assertTrue(value.every { it instanceof String })
                    assertThrows(UnsupportedOperationException) { value.add('other') }
                }
            }
        }
    }
}

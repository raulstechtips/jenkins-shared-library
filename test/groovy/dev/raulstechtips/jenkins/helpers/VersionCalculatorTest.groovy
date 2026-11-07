package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test
import static org.junit.jupiter.api.Assertions.*

class VersionCalculatorTest {
    private final VersionCalculator calculator = new VersionCalculator()

    @Test
    void mainVector() {
        assertEquals('1.1.0-main-0067101-13', calculator.calculate(
            '1.0.0', [BRANCH_NAME: 'main', BUILD_NUMBER: '13'], [head: '0067101']))
    }

    @Test
    void noReleaseStartsAtZeroAndIncreasesMinor() {
        assertEquals('0.1.0-main-0067101-1', calculator.calculate(
            '0.0.0', [BRANCH_NAME: 'main', BUILD_NUMBER: '1'], [head: '0067101']))
    }

    @Test
    void minorIncreaseResetsPatch() {
        assertEquals('1.3.0-main-0067101-2', calculator.calculate(
            '1.2.3', [BRANCH_NAME: 'main', BUILD_NUMBER: '2'], [head: '0067101']))
    }

    @Test
    void branchVector() {
        assertEquals('1.3.0-feature-devsecops-70-e4f5a6b-3', calculator.calculate(
            '1.2.0', [BRANCH_NAME: 'feature/DEVSECOPS-70-x', BUILD_NUMBER: '3'], [head: 'e4f5a6b']))
    }

    @Test
    void pullRequestVector() {
        assertEquals('1.3.0-bugfix-abc-1-a1b2c3d-9f8e7d6-2', calculator.calculate(
            '1.2.0', [BRANCH_NAME: 'PR-1', CHANGE_ID: '1', CHANGE_BRANCH: 'bugfix/ABC-1', BUILD_NUMBER: '2'],
            [head: 'a1b2c3d012345', base: '9f8e7d6012345']))
    }

    @Test
    void missingPullRequestRevisionVector() {
        def error = assertThrows(IllegalArgumentException) {
            calculator.calculate('1.2.0', [CHANGE_ID: '1', CHANGE_BRANCH: 'bugfix/ABC-1', BUILD_NUMBER: '2'], [:])
        }
        assertEquals('Cannot read the PR head and base commits from the build.', error.message)
    }

    @Test
    void invalidBranchVector() {
        def error = assertThrows(IllegalArgumentException) {
            calculator.calculate('1.2.0', [BRANCH_NAME: 'wip/test', BUILD_NUMBER: '2'], [head: 'abcdef0'])
        }
        assertEquals('Branch "wip/test" must be <type>/<KEY>-<number>[-text]. The type must be feature, bugfix, hotfix or release. Example: feature/DEVSECOPS-54-argocd-chart.', error.message)
    }

    @Test
    void invalidLatestReleaseVector() {
        def error = assertThrows(IllegalArgumentException) {
            calculator.calculate('1.0', [BRANCH_NAME: 'main', BUILD_NUMBER: '1'], [head: 'abcdef0'])
        }
        assertEquals('Latest release 1.0 must be X.Y.Z', error.message)
    }

    @Test
    void releaseVector() {
        assertEquals('1.3.0', calculator.calculate(null, [:], [:], '1.3.0'))
    }

    @Test
    void acceptsEveryBranchTypeAndNumericProjectKey() {
        ['feature', 'bugfix', 'hotfix', 'release'].each { type ->
            assertEquals("0.2.0-${type}-a9-12-abcdef0-8".toString(), calculator.calculate(
                '0.1.0', [BRANCH_NAME: "${type}/A9-12-text".toString(), BUILD_NUMBER: '8'],
                [head: 'abcdef012345']))
        }
    }
}

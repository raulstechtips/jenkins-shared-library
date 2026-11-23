package dev.raulstechtips.jenkins.helpers

import org.junit.jupiter.api.Test

import static org.junit.jupiter.api.Assertions.*

class AptUnpinTest {
    // Shape of the final stage of Infisical v0.162.21 Dockerfile.standalone-infisical.
    private static final String STAGE = '''FROM base AS production
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y \\
    ca-certificates \\
    wget \\
    && curl -1sLf 'https://artifacts-cli.infisical.com/setup.deb.sh' | bash \\
    && apt-get update && apt-get install -y infisical=0.43.90 \\
    # Patch CVEs in base image packages (pinned versions for reproducibility)
    && apt-get install -y --only-upgrade \\
    libgnutls30t64=3.8.9-3+deb13u4 \\
    libc6=2.41-12+deb13u3 \\
    libssl3t64=3.5.6-1~deb13u2 \\
    dpkg=1.22.22 \\
    && rm -rf /var/lib/apt/lists/*
ENV NODE_OPTIONS="--max-old-space-size=2048"'''

    @Test
    void removesDebianPinsAndKeepsListedPackages() {
        Map result = AptUnpin.unpin(STAGE, ['infisical'])
        assertEquals(STAGE
            .replace('libgnutls30t64=3.8.9-3+deb13u4 ', 'libgnutls30t64 ')
            .replace('libc6=2.41-12+deb13u3 ', 'libc6 ')
            .replace('libssl3t64=3.5.6-1~deb13u2 ', 'libssl3t64 ')
            .replace('dpkg=1.22.22 ', 'dpkg '), result.text)
        assertEquals([
            'libgnutls30t64=3.8.9-3+deb13u4 -> libgnutls30t64',
            'libc6=2.41-12+deb13u3 -> libc6',
            'libssl3t64=3.5.6-1~deb13u2 -> libssl3t64',
            'dpkg=1.22.22 -> dpkg'
        ], result.changes)
        assertTrue(result.text.contains('infisical=0.43.90'))
    }

    @Test
    void keepsVariablePinOfKeptPackage() {
        String text = 'RUN apt-get install -y infisical=${INFISICAL_CLI_VERSION} libc6=2.41-12+deb13u4'
        Map result = AptUnpin.unpin(text, ['infisical'])
        assertEquals('RUN apt-get install -y infisical=${INFISICAL_CLI_VERSION} libc6', result.text)
    }

    @Test
    void emptyKeepListUnpinsEverything() {
        Map result = AptUnpin.unpin('RUN apt-get install -y infisical=0.43.90', [])
        assertEquals('RUN apt-get install -y infisical', result.text)
    }

    @Test
    void readsOnlyArgumentsOfAptGetInstall() {
        String text = 'RUN version=1 apt-get install -y curl=8.14.1-2 && echo build=2 ; npm install -g npm@10.9.0'
        Map result = AptUnpin.unpin(text, [])
        assertEquals('RUN version=1 apt-get install -y curl && echo build=2 ; npm install -g npm@10.9.0', result.text)
        assertEquals(['curl=8.14.1-2 -> curl'], result.changes)
    }

    @Test
    void leavesOtherInstructionsAndOptionsUnchanged() {
        String text = '''ARG libc6=2.41-12
ENV pkg=1.0
RUN apt-get -o Acquire::Retries=3 install -y --no-install-recommends \\
    -o Dpkg::Options::=--force-confold \\
    signed-by=/usr/share/keyrings/key.gpg \\
    DEBIAN_FRONTEND=noninteractive \\
    git=1:2.47.3-0+deb13u1
RUN echo libc6=2.41-12'''
        Map result = AptUnpin.unpin(text, [])
        assertEquals(text.replace('git=1:2.47.3-0+deb13u1', 'git'), result.text)
        assertEquals(['git=1:2.47.3-0+deb13u1 -> git'], result.changes)
    }

    @Test
    void commentAndBlankLinesStayInsideTheContinuation() {
        String text = 'RUN apt-get install -y \\\n    # comment && not a separator\n\n    libc6=2.41-12+deb13u3\nLABEL x=1'
        Map result = AptUnpin.unpin(text, [])
        assertEquals(text.replace('libc6=2.41-12+deb13u3', 'libc6'), result.text)
    }

    @Test
    void stopsAtTheEndOfTheRunInstruction() {
        String text = 'RUN apt-get install -y curl\nLABEL pkg=1.0\nRUN true'
        assertEquals([text: text, changes: []], AptUnpin.unpin(text, []))
    }

    @Test
    void dockerfileWithoutPinsIsUnchanged() {
        String text = 'FROM debian:trixie-slim\nRUN apt-get update && apt-get install -y curl\n'
        assertEquals([text: text, changes: []], AptUnpin.unpin(text, ['infisical']))
    }
}

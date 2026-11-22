import dev.raulstechtips.jenkins.helpers.AptUnpin

/**
 * Removes the apt version pins from the image Dockerfile in the workspace.
 *
 * It rewrites docker-image.dockerfile with AptUnpin, so each package=version argument of an
 * apt-get install command becomes package, except for the packages in keep. Only the workspace
 * copy changes; nothing is committed. Each removed pin is logged and written to
 * .pipeline/reports/apt-unpin.txt.
 *
 * @param cfg the loaded config. Reads docker-image.dockerfile.
 * @param keep the package names whose pin stays
 * @return the removed pins, one 'name=version -> name' line each
 */
List<String> call(Map cfg, List<String> keep) {
    String path = cfg['docker-image'].dockerfile
    Map result = AptUnpin.unpin(readFile(path), keep)
    writeFile(file: path, text: result.text)
    List<String> changes = result.changes
    String report = changes ? changes.join('\n') + '\n' : 'No apt version pins removed.\n'
    writeFile(file: '.pipeline/reports/apt-unpin.txt', text: report)
    echo("Apt version pins removed from ${path}: ${changes.size()}")
    changes.each { String change -> echo(change) }
    return changes
}

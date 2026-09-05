// Loaded from the same SCM revision as the consuming Jenkinsfile.
def call() {
    String current = env.JOB_NAME
    int slash = current.lastIndexOf('/')
    String folder = slash < 0 ? '' : current.substring(0, slash + 1)
    String basename = current.substring(slash + 1)
    String requestedJob = params.COMPILE_JOB?.trim() ?: basename.replaceFirst(/-test(?:-cloud)?-pipeline$/, '-compile-pipeline')
    String producer = requestedJob.contains('/') ? requestedJob.replaceFirst(/^\//, '') : folder + requestedJob
    String sibling = producer.startsWith(folder) ? producer.substring(folder.length()) : ''
    if (!(sibling ==~ /[A-Za-z0-9_.-]+/) || sibling in ['.', '..'] || producer == current) {
        error('COMPILE_JOB must identify a compile producer in this exact folder')
    }
    String requestedBuild = params.USE_BINARIES_FROM_BUILD_ID?.trim() ?: 'lastSuccessfulBuild'
    if (requestedBuild != 'lastSuccessfulBuild' && !(requestedBuild ==~ /[1-9][0-9]{0,8}/)) {
        error('Choose lastSuccessfulBuild or a positive compile build number')
    }
    String selectedBuild
    timeout(time: 2, unit: 'MINUTES') {
        dir('compile-input') { deleteDir() }
        copyArtifacts projectName: '/' + producer, filter: '**/COMPILE_BUILD_TAG,**/PIPELINE_BUILD_NUMBER',
            target: 'compile-input', flatten: false, optional: false,
            includeBuildNumberInTargetPath: true,
            selector: requestedBuild == 'lastSuccessfulBuild' ? lastSuccessful(stable: true) : specific(requestedBuild)
        // Copy Artifact's result env action is not exposed to every Pipeline version.
        // The native numbered target is persistent and comes from this exact copy.
        selectedBuild = sh(returnStdout: true, script: '''#!/bin/sh
            set -eu
            find compile-input -mindepth 1 -maxdepth 1 -type d -printf '%f\\n'
        ''').trim()
        if (!(selectedBuild ==~ /[1-9][0-9]{0,8}/) ||
            (requestedBuild != 'lastSuccessfulBuild' && selectedBuild != requestedBuild)) {
            error('Artifact copy did not return the exact requested compile build number')
        }
        def completed = waitForBuild(runId: producer + '#' + selectedBuild, propagate: false, propagateAbort: false)
        if (completed.result != 'SUCCESS') {
            error("Compile input '${producer}' #${selectedBuild} did not complete successfully")
        }
    }
    env.PXB_COMPILE_JOB = producer
    env.PXB_COMPILE_BUILD = selectedBuild
    echo "Compile input: ${producer} #${selectedBuild}"
    return [job: producer, build: selectedBuild]
}

return this

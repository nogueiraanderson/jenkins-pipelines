// Exercise the actual loaded helper through its Pipeline-step boundary.
def environment = [JOB_NAME: 'review/pr/percona-xtrabackup-8.0-test-pipeline']
def copied = []
def waited = []
def latest = '41'
def copiedDirectory
def bindings = new Binding([
    params: [:], env: environment,
    error: { message -> throw new IllegalArgumentException(message.toString()) },
    echo: { ignored -> },
    dir: { String path, Closure body -> assert path == 'compile-input'; body() },
    deleteDir: { -> },
    timeout: { Map options, Closure body -> assert options.time == 2; body() },
    lastSuccessful: { Map options -> assert options.stable; [latest: true] },
    specific: { String number -> [number: number] },
    copyArtifacts: { Map options ->
        copied << options
        copiedDirectory = options.selector.number ?: latest
        latest = '42'
    },
    sh: { Map options ->
        assert options.returnStdout && options.script.contains('find compile-input')
        copiedDirectory + '\n'
    },
    waitForBuild: { Map options -> waited << options; [result: 'SUCCESS'] },
])
def helper = new GroovyShell(bindings).evaluate(new File('pxb/v2/ci/copyCompileInput.groovy'))
assert helper.call() == [job: 'review/pr/percona-xtrabackup-8.0-compile-pipeline', build: '41']
assert copied.size() == 1
assert copied[0].projectName == '/review/pr/percona-xtrabackup-8.0-compile-pipeline'
assert copied[0].target == 'compile-input' && !copied[0].optional
assert copied[0].includeBuildNumberInTargetPath
assert waited[0].runId == 'review/pr/percona-xtrabackup-8.0-compile-pipeline#41'
assert !waited[0].propagateAbort
println 'PASS: Standalone copy resolves one exact sibling build and checks that same build result'

['../production', '/production/compile', 'review/other/compile', '.', '..', 'compile\njob'].each { name ->
    bindings.setVariable('params', [COMPILE_JOB: name])
    int before = copied.size()
    try {
        helper.call()
        assert false : "Accepted a producer outside the exact sibling namespace: ${name}"
    } catch (IllegalArgumentException expected) {
        assert expected.message.contains('COMPILE_JOB')
    }
    assert copied.size() == before
}
println 'PASS: Producer overrides cannot escape the consuming folder'

['0', '-1', 'lastBuild', 'bad', '9999999999'].each { value ->
    bindings.setVariable('params', [USE_BINARIES_FROM_BUILD_ID: value])
    int before = copied.size()
    try { helper.call(); assert false : "Accepted invalid build ${value}" }
    catch (IllegalArgumentException expected) { assert expected.message.contains('build') }
    assert copied.size() == before
}
println 'PASS: Invalid and moving build overrides fail before artifact copying'

bindings.setVariable('params', [COMPILE_JOB: 'review/pr/percona-xtrabackup-8.0-compile-param', USE_BINARIES_FROM_BUILD_ID: '7'])
assert helper.call() == [job: 'review/pr/percona-xtrabackup-8.0-compile-param', build: '7']
assert copied[-1].selector.number == '7'
assert waited[-1].runId == 'review/pr/percona-xtrabackup-8.0-compile-param#7'
println 'PASS: An explicit matrix producer and old build number remain exact'

bindings.setVariable('waitForBuild', { Map options -> [result: 'FAILURE'] })
try { helper.call(); assert false : 'Accepted artifacts from a failed producer' }
catch (IllegalArgumentException expected) { assert expected.message.contains('did not complete successfully') }
println 'PASS: Copied artifacts from a failed producer are rejected'

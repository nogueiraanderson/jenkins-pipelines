// Render the actual Jenkinsfile shell strings and execute the actual helper,
// substituting only Jenkins steps. Real job runs remain a separate gate.
def root = new File('.').canonicalFile
['percona-xtrabackup-8.0-compile-pipeline.groovy', 'percona-xtrabackup-8.0-test-pipeline.groovy', 'percona-xtrabackup-8.0-test-cloud-pipeline.groovy'].each { filename ->
    boolean compile = filename.contains('-compile-')
    def shells = []
    def events = []
    def copied = []
    def archived = []
    def env = [JOB_NAME: 'review/percona-xtrabackup-8.0-test-pipeline']
    def bindings = new Binding([
        params: [ARCH: 'x86_64', CLOUD: 'Hetzner', LABEL: 'docker-32gb', COMPILE_JOB: 'percona-xtrabackup-8.0-compile-param', USE_BINARIES_FROM_BUILD_ID: '7'],
        env: env, currentBuild: [:], scm: 'selected-scm', BUILD_NUMBER: '1',
        CMAKE_BUILD_TYPE: 'RelWithDebInfo', DOCKER_OS: 'oraclelinux:9', USE_BINARIES_FROM_BUILD_ID: '7',
        pipeline: { Closure body -> body() }, stages: { Closure body -> body() },
        parameters: { Closure ignored -> }, agent: { Closure ignored -> }, options: { Closure ignored -> },
        post: { Closure ignored -> }, stage: { String name, Closure body -> if (name == (compile ? 'Build' : 'Test')) body() },
        steps: { Closure body -> body() }, script: { Closure body -> body() },
        timeout: { Map options, Closure body -> body() },
        dir: { String path, Closure body -> body() }, deleteDir: { -> },
        withCredentials: { List credentials, Closure body -> body() },
        checkout: { value -> assert value == 'selected-scm'; events << 'checkout' }, echo: { ignored -> },
        error: { message -> throw new IllegalStateException(message.toString()) },
        sh: { value ->
            shells << (value instanceof Map ? value.script : value.toString())
            events << shells.last()
            if (value instanceof Map && value.returnStdout) return '7\n'
        },
        archiveArtifacts: { Map options -> archived << options },
        specific: { number -> [number: number] },
        lastSuccessful: { -> [latest: true] },
        copyArtifacts: { Map options -> copied << options },
        waitForBuild: { Map options -> assert options.runId.endsWith('#7'); [result: 'SUCCESS'] },
    ])
    bindings.setVariable('load', { String path -> new GroovyShell(bindings).evaluate(new File(root, path)) })
    new GroovyShell(bindings).evaluate(new File(root, 'pxb/v2/jenkins/' + filename))
    assert shells.count { it == 'python3 pxb/v2/ci/verify_worker.py' } == 1
    assert events[events.indexOf('checkout') + 1] == 'python3 pxb/v2/ci/verify_worker.py'
    if (!compile) {
        assert copied.size() == 1 && copied[0].projectName == '/review/percona-xtrabackup-8.0-compile-param'
        assert copied[0].selector.number == '7'
        assert copied[0].includeBuildNumberInTargetPath
        assert shells.count { it.contains('fetch_compile_artifact.py') } == 1
        assert shells.find { it.contains('fetch_compile_artifact.py') }.contains('set -euo pipefail')
        assert archived.any { it.artifacts == 'pxb/v2/sources/results/compile-input.json' }
    }
    shells.each { shell ->
        if (!compile) assert !shell.contains('until aws')
        def process = new ProcessBuilder('bash', '-n').start()
        process.outputStream.withWriter { it.write(shell) }
        assert process.waitFor() == 0 : process.errorStream.text
    }
    println "PASS: ${filename} checks its worker after SCM checkout and has valid rendered shell${compile ? '' : ', exact producer input, bounded fetch and archived provenance'}"
}

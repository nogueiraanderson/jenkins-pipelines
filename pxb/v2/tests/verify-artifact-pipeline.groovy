// Render the actual Jenkinsfile shell strings and execute the actual helper,
// substituting only Jenkins steps. Real job runs remain a separate gate.
def root = new File('.').canonicalFile
def filenames = ['2.4', '8.0', '8.1', '9.x'].collectMany { family ->
    def kinds = ['compile-pipeline', 'test-pipeline']
    if (family in ['8.0', '8.1']) kinds << 'test-cloud-pipeline'
    kinds.collect { kind -> "percona-xtrabackup-${family}-${kind}.groovy".toString() }
}
(args ? args.toList() : filenames).each { filename ->
    String family = filename.replaceFirst(/^percona-xtrabackup-/, '').replaceFirst(/-(?:compile|test).*$/, '')
    boolean compile = filename.contains('-compile-')
    def shells = []
    def events = []
    def copied = []
    def archived = []
    def declared = [:]
    def env = [JOB_NAME: 'review/' + filename.replaceFirst(/\.groovy$/, '')]
    def bindings = new Binding([
        params: [ARCH: 'x86_64', CLOUD: 'Hetzner', LABEL: 'docker-32gb', COMPILE_JOB: "percona-xtrabackup-${family}-compile-param", USE_BINARIES_FROM_BUILD_ID: '7'],
        env: env, currentBuild: [:], scm: 'selected-scm', BUILD_NUMBER: '1',
        CMAKE_BUILD_TYPE: 'RelWithDebInfo', DOCKER_OS: 'oraclelinux:9', USE_BINARIES_FROM_BUILD_ID: '7',
        pipeline: { Closure body -> body() }, stages: { Closure body -> body() },
        parameters: { Closure body -> body() }, agent: { Closure ignored -> }, options: { Closure ignored -> },
        string: { Map options -> declared[options.name] = options },
        choice: { Map options -> declared[options.name] = options },
        booleanParam: { Map options -> declared[options.name] = options },
        post: { Closure ignored -> }, stage: { String name, Closure body -> if (name == (compile ? 'Build' : 'Test')) body() },
        steps: { Closure body -> body() }, script: { Closure body -> body() },
        timeout: { Map options, Closure body -> body() },
        dir: { String path, Closure body -> body() }, deleteDir: { -> },
        withCredentials: { List credentials, Closure body -> body() },
        checkout: { value -> assert value == 'selected-scm'; events << 'checkout'; [GIT_COMMIT: 'b' * 40] }, echo: { ignored -> },
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
        assert declared.COMPILE_JOB.defaultValue == ''
        assert declared.USE_BINARIES_FROM_BUILD_ID.defaultValue == 'lastSuccessfulBuild'
        assert env.PXB_PIPELINE_REVISION == 'b' * 40
        assert copied.size() == 1 && copied[0].projectName == "/review/percona-xtrabackup-${family}-compile-param"
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
    if (!compile) {
        [null, [:], [GIT_COMMIT: null], [GIT_COMMIT: ''], [GIT_COMMIT: 'abc123'], [GIT_COMMIT: 'z' * 40]].each { invalidCheckout ->
            copied.clear()
            archived.clear()
            shells.clear()
            env.remove('PXB_PIPELINE_REVISION')
            bindings.setVariable('checkout', { ignored -> invalidCheckout })
            try {
                new GroovyShell(bindings).evaluate(new File(root, 'pxb/v2/jenkins/' + filename))
                assert false : 'Unverified checkout must not reach the artifact or product steps'
            } catch (IllegalStateException rejected) {
                assert rejected.message == 'Checkout did not return a full pipeline commit SHA'
            }
            assert !env.containsKey('PXB_PIPELINE_REVISION')
            assert copied.isEmpty() && archived.isEmpty()
            assert !shells.any { it.contains('fetch_compile_artifact.py') || it.contains('./docker/run-test') }
        }
        println "PASS: ${filename} rejects all six invalid checkout results before copying or testing"
    }
}

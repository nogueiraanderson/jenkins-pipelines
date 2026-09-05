// Jenkins model objects are the external boundary. Execute the actual resolver.
def loader = new GroovyClassLoader()
loader.parseClass('package hudson.matrix; class MatrixRun { def parentBuild }')
loader.parseClass('package org.jenkinsci.plugins.envinject; class EnvInjectPluginAction {}')
def source = new File('pxb/v2/ci/pin-matrix-input.groovy')
def first = new Expando(number: 1, building: false, result: 'SUCCESS')
def second = new Expando(number: 2, building: false, result: 'SUCCESS')
def producer = new Expando(fullName: 'review/compile', lastSuccessfulBuild: first,
    getBuildByNumber: { n -> [1: first, 2: second][n] })
def owner = new Expando(getItem: { name -> name == 'compile' ? producer : null })
def job = new Expando(name: 'test', parent: owner)
def run = { input ->
    new GroovyShell(loader, new Binding(currentJob: job,
        currentBuild: new Expando(getBuildVariables: { input }))).evaluate(source)
}
assert run([COMPILE_JOB: 'compile']) == [PXB_COMPILE_JOB: 'review/compile', PXB_COMPILE_BUILD: '1']
assert run([COMPILE_JOB: 'compile', USE_BINARIES_FROM_BUILD_ID: '2']).PXB_COMPILE_BUILD == '2'
def snapshot = run([COMPILE_JOB: 'compile'])
producer.lastSuccessfulBuild = second
def child = loader.loadClass('hudson.matrix.MatrixRun').newInstance()
child.parentBuild = new Expando(getAction: { type -> new Expando(envMap: snapshot) })
def result = new GroovyShell(loader, new Binding(currentBuild: child)).evaluate(source)
assert result.PXB_COMPILE_BUILD == '1' : 'A newer producer must not change the snapshot'
['../compile', '/compile', 'missing'].each { name ->
    try { run([COMPILE_JOB: name]); assert false : 'Escaping or missing producer accepted' }
    catch (IllegalArgumentException expected) {}
}
['0', '-1', 'bogus', '1,2', '1000000000'].each { value ->
    try { run([COMPILE_JOB: 'compile', USE_BINARIES_FROM_BUILD_ID: value]); assert false : 'Invalid build accepted' }
    catch (IllegalArgumentException expected) {}
}
try { run([COMPILE_JOB: 'compile', USE_BINARIES_FROM_BUILD_ID: '3']); assert false : 'Missing build accepted' }
catch (IllegalStateException expected) {}
second.result = 'FAILURE'
try { run([COMPILE_JOB: 'compile']); assert false : 'Failed producer accepted' }
catch (IllegalStateException expected) {}
println 'PASS: latest snapshot, explicit override, later producer isolation, scope and failure guards'

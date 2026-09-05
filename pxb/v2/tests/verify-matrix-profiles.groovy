import groovy.json.JsonSlurper

// Axes and filters come from real JJB XML, not a transcription of the YAML.
def specs = new JsonSlurper().parse(System.in)
specs.each { spec ->
    def combinations = [[:]]
    spec.axes.each { axis, values ->
        combinations = combinations.collectMany { combination ->
            values.collect { value -> combination + [(axis): value] }
        }
    }
    def selected = { expression ->
        combinations.findAll { combination ->
            new GroovyShell(new Binding(combination)).evaluate("(${spec.projectFilter}) && (${expression})")
        }
    }
    def routine = selected(spec.routineFilter)
    def release = selected('true')
    assert routine.size() == spec.routineCount : "${spec.name}: routine=${routine.size()}, expected ${spec.routineCount}"
    assert release.size() == spec.releaseCount : "${spec.name}: release=${release.size()}, expected ${spec.releaseCount}"
    assert !routine.any { it.DOCKER_OS == 'asan' }
    assert !release.any { it.DOCKER_OS == 'centos:8' && it.ARCH == 'aarch64' }
    if (!spec.name.contains('-9.x-')) {
        assert !release.any { it.DOCKER_OS == 'amazonlinux:2023' }
    }
    assert routine.count { it.ARCH == 'aarch64' } == routine.size() / 2
    println "PASS ${spec.name}: routine=${routine.size()}, release=${release.size()}"
}

// EnvInject runs this reviewed script once on the matrix parent before fan-out.
// Children inherit the parent's persisted snapshot, never another latest lookup.
// Approve the complete script through Jenkins Script Approval, not broad methods.
def keys = ['PXB_COMPILE_JOB', 'PXB_COMPILE_BUILD']
if (currentBuild instanceof hudson.matrix.MatrixRun) {
    def snapshot = currentBuild.parentBuild.getAction(
        org.jenkinsci.plugins.envinject.EnvInjectPluginAction)?.envMap
    if (!snapshot || !keys.every { snapshot[it] }) {
        throw new IllegalStateException('The matrix parent has no pinned compile input')
    }
    return keys.collectEntries { [(it): snapshot[it]] }
}

def input = currentBuild.getBuildVariables()
def sourceName = input.COMPILE_JOB?.trim() ?: currentJob.name.replaceFirst(/-test-param$/, '-compile-param')
if (!(sourceName ==~ /[A-Za-z0-9_.-]+/)) {
    throw new IllegalArgumentException('COMPILE_JOB must name a producer in this folder')
}
// ItemGroup.getItem is deliberately exact. Jenkins.getItem can search ancestors.
def producer = currentJob.parent.getItem(sourceName)
if (producer == null || producer == currentJob) {
    throw new IllegalArgumentException("No compile producer '${sourceName}' in this folder")
}
def requested = input.USE_BINARIES_FROM_BUILD_ID?.trim() ?: 'lastSuccessfulBuild'
def selected
if (requested == 'lastSuccessfulBuild') {
    selected = producer.lastSuccessfulBuild
} else if (requested ==~ /[1-9][0-9]{0,8}/) {
    selected = producer.getBuildByNumber(requested.toInteger())
} else {
    throw new IllegalArgumentException('Choose lastSuccessfulBuild or a positive compile build number')
}
if (selected == null || selected.building || selected.result?.toString() != 'SUCCESS') {
    throw new IllegalStateException("Compile input '${sourceName}' #${requested} is not a completed successful build")
}
println "Pinned compile input: ${producer.fullName} #${selected.number}"
return [PXB_COMPILE_JOB: producer.fullName, PXB_COMPILE_BUILD: selected.number.toString()]

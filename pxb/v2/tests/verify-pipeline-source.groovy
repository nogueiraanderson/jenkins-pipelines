import org.codehaus.groovy.ast.CodeVisitorSupport
import org.codehaus.groovy.ast.ClassNode
import org.codehaus.groovy.ast.builder.AstBuilder
import org.codehaus.groovy.ast.expr.MapExpression
import org.codehaus.groovy.ast.expr.MethodCallExpression
import org.codehaus.groovy.ast.expr.TupleExpression
import org.codehaus.groovy.ast.expr.VariableExpression
import org.codehaus.groovy.ast.stmt.BlockStatement
import org.codehaus.groovy.control.CompilePhase

// Parse the actual Jenkinsfiles, including every stage and nested closure.
// This is a source contract check, not a substitute for a Jenkins canary.
class SourceContract extends CodeVisitorSupport {
    int scmCheckouts = 0
    List<Integer> unpinnedLines = []

    @Override
    void visitMethodCallExpression(MethodCallExpression call) {
        if (call.methodAsString == 'checkout' && call.arguments instanceof TupleExpression) {
            def args = call.arguments.expressions
            if (args.size() == 1 && args[0] instanceof VariableExpression && args[0].name == 'scm') {
                scmCheckouts++
            }
        }
        if (call.methodAsString == 'git' && call.arguments instanceof TupleExpression) {
            call.arguments.expressions.findAll { it instanceof MapExpression }.each { map ->
                def url = map.mapEntryExpressions.find { it.keyExpression.text == 'url' }?.valueExpression?.text
                if (url?.toLowerCase()?.contains('jenkins-pipelines')) {
                    unpinnedLines << call.lineNumber
                }
            }
        }
        super.visitMethodCallExpression(call)
    }
}

def files = new File('pxb/v2/jenkins').listFiles().findAll { it.name.endsWith('.groovy') }.sort { it.name }
files << new File('pxc/jenkins/prepare-pxc-build-docker.groovy')
assert files.size() == 12 : "Expected eleven PXB Jenkinsfiles and one PXC image Jenkinsfile, found ${files.size()}"
int failed = 0
files.each { file ->
    def visitor = new SourceContract()
    def nodes = new AstBuilder().buildFromString(CompilePhase.CONVERSION, false, file.text)
    nodes.findAll { it instanceof BlockStatement }.each { it.visit(visitor) }
    nodes.findAll { it instanceof ClassNode }.each { node ->
        node.methods.findAll { it.name != 'run' && it.lineNumber > 0 }.each { it.code?.visit(visitor) }
    }
    int expected = file.name == 'percona-xtrabackup-9.x-single-platform.groovy' ? 2 : 1
    boolean ok = visitor.unpinnedLines.empty && visitor.scmCheckouts == expected
    println "${ok ? 'PASS' : 'FAIL'} ${file.path}: SCM checkouts=${visitor.scmCheckouts}/${expected}, unpinned lines=${visitor.unpinnedLines}"
    if (!ok) failed++
}
assert failed == 0 : "${failed} Jenkinsfiles can execute sources outside their selected SCM revision"

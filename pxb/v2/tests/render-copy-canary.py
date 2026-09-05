"""Render sandboxed jobs that exercise the actual helper through native Jenkins steps."""
import argparse
import base64
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET


parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('kind', choices=['producer', 'consumer'])
parser.add_argument('--output', type=Path, required=True)
args = parser.parse_args()
helper = (Path(__file__).resolve().parents[1] / 'ci/copyCompileInput.groovy').read_bytes()
digest = hashlib.sha256(helper).hexdigest()
root = ET.Element('flow-definition')
ET.SubElement(root, 'description').text = (
    'PXB-3745 native Copy Artifact and waitForBuild canary. '
    'No PXB binary or S3 transfer is tested. Intentional failures are labeled in each build. '
    f'Exact helper SHA256: {digest}. Retain all builds for Satya.'
)
ET.SubElement(root, 'disabled').text = 'false'
properties = ET.SubElement(root, 'properties')
ET.SubElement(properties, 'org.jenkinsci.plugins.workflow.job.properties.DisableConcurrentBuildsJobProperty')
parameters = ET.SubElement(ET.SubElement(properties, 'hudson.model.ParametersDefinitionProperty'), 'parameterDefinitions')
values = {'EXHIBIT': 'Native copy canary, no product validation'}
if args.kind == 'producer':
    values['RESULT'] = 'SUCCESS'
else:
    values.update(COMPILE_JOB='', USE_BINARIES_FROM_BUILD_ID='lastSuccessfulBuild', EXPECTED_BUILD='1')
for name, default in values.items():
    parameter = ET.SubElement(parameters, 'hudson.model.StringParameterDefinition')
    ET.SubElement(parameter, 'name').text = name
    ET.SubElement(parameter, 'defaultValue').text = default
definition = ET.SubElement(root, 'definition', {'class': 'org.jenkinsci.plugins.workflow.cps.CpsFlowDefinition'})
ET.SubElement(definition, 'sandbox').text = 'true'
script = '''currentBuild.keepLog = true
currentBuild.description = params.EXHIBIT
timeout(time: 15, unit: 'MINUTES') {
    node('launcher-x64') {
        deleteDir()
'''
if args.kind == 'producer':
    script += '''        writeFile file: 'COMPILE_BUILD_TAG', text: env.BUILD_TAG + '\\n'
        archiveArtifacts artifacts: 'COMPILE_BUILD_TAG', fingerprint: true
        if (params.RESULT != 'SUCCESS') {
            error('Intentional failed producer with archived marker')
        }
'''
else:
    script += f"        writeFile file: 'copyCompileInput.groovy', encoding: 'Base64', text: '{base64.b64encode(helper).decode()}'\n"
    script += f"        sh 'echo {digest}  copyCompileInput.groovy | sha256sum -c -'\n"
    script += '''        try {
            def selected = load('copyCompileInput.groovy').call()
            if (selected.build != params.EXPECTED_BUILD) {
                error("Expected #${params.EXPECTED_BUILD}, received #${selected.build}")
            }
            def marker = readFile('compile-input/' + selected.build + '/COMPILE_BUILD_TAG').trim()
            def expected = 'jenkins-' + selected.job.replace('/', '-') + '-' + selected.build
            if (marker != expected) {
                error("Marker does not match selected producer: ${marker}")
            }
            writeFile file: 'selected-producer.txt', text: selected.job + '#' + selected.build + '\\n'
            echo "PASS: Exact copied marker and completed producer agree: ${selected.job} #${selected.build}"
        } finally {
            archiveArtifacts artifacts: 'copyCompileInput.groovy,compile-input/**,selected-producer.txt',
                allowEmptyArchive: false, followSymlinks: false, fingerprint: true
        }
'''
script += '''    }
}
'''
ET.SubElement(definition, 'script').text = script
ET.SubElement(root, 'triggers')
ET.indent(root)
with args.output.open('x') as output:
    output.write(ET.tostring(root, encoding='unicode', xml_declaration=True) + '\n')
print(f'{args.kind}: {args.output}, helper SHA256={digest}')

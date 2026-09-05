"""Render harmless producer/consumer jobs to test the real matrix snapshot hook."""

from copy import deepcopy
import sys
import xml.etree.ElementTree as ET

from test_job_contracts import JOBS, render_job


kind = sys.argv[1]
assert kind in ("producer", "consumer")
root = ET.Element("project" if kind == "producer" else "matrix-project")
ET.SubElement(root, "description").text = "Artifact-pinning logic canary. No PXB or ARM binaries are executed."
ET.SubElement(root, "disabled").text = "false"
ET.SubElement(root, "concurrentBuild").text = "false"
ET.SubElement(root, "assignedNode").text = "launcher-x64"
ET.SubElement(root, "canRoam").text = "false"
ET.SubElement(root, "scm", {"class": "hudson.scm.NullSCM"})
properties = ET.SubElement(root, "properties")
definitions = ET.SubElement(ET.SubElement(properties, "hudson.model.ParametersDefinitionProperty"), "parameterDefinitions")
params = {"MARKER": "evidence"} if kind == "producer" else {
    "COMPILE_JOB": "pin-producer-20260905", "USE_BINARIES_FROM_BUILD_ID": "lastSuccessfulBuild",
    "EXPECTED_BUILD": "1", "PAUSE_SECONDS": "0",
}
for name, value in params.items():
    parameter = ET.SubElement(definitions, "hudson.model.StringParameterDefinition")
    ET.SubElement(parameter, "name").text = name
    ET.SubElement(parameter, "defaultValue").text = value
if kind == "consumer":
    source, = render_job(JOBS / "percona-xtrabackup-8.0-test-param.yml")
    properties.append(deepcopy(source.find("properties/EnvInjectJobProperty")))
    strategy = ET.SubElement(root, "executionStrategy", {"class": "hudson.matrix.DefaultMatrixExecutionStrategyImpl"})
    ET.SubElement(strategy, "runSequentially").text = "true"
    ET.SubElement(strategy, "touchStoneCombinationFilter").text = "CELL == 'first'"
    ET.SubElement(strategy, "touchStoneResultCondition").text = "SUCCESS"
    axes = ET.SubElement(root, "axes")
    for tag, name, values in (("TextAxis", "CELL", ["first", "second"]),
                              ("LabelAxis", "CANARY_NODE", ["launcher-x64"])):
        axis = ET.SubElement(axes, "hudson.matrix." + tag)
        ET.SubElement(axis, "name").text = name
        axis_values = ET.SubElement(axis, "values")
        for value in values:
            ET.SubElement(axis_values, "string").text = value
builders = ET.SubElement(root, "builders")
shell = ET.SubElement(builders, "hudson.tasks.Shell")
ET.SubElement(shell, "command").text = (
    '#!/bin/sh\nset -eu\nprintf "%s\\n" "$BUILD_NUMBER" > producer.txt\n'
    if kind == "producer" else
    '#!/bin/sh\nset -eu\n'
    'printf "PINNED %s #%s CELL=%s\\n" "$PXB_COMPILE_JOB" "$PXB_COMPILE_BUILD" "$CELL"\n'
    'test "$PXB_COMPILE_BUILD" = "$EXPECTED_BUILD"\n'
    'if [ "$CELL" = first ]; then sleep "$PAUSE_SECONDS"; fi\n'
)
publishers = ET.SubElement(root, "publishers")
if kind == "producer":
    archive = ET.SubElement(publishers, "hudson.tasks.ArtifactArchiver")
    ET.SubElement(archive, "artifacts").text = "producer.txt"
    ET.SubElement(archive, "allowEmptyArchive").text = "false"
ET.SubElement(root, "buildWrappers")
ET.indent(root)
print(ET.tostring(root, encoding="unicode", xml_declaration=True))

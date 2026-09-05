"""Print a selection-only canary using the actual rendered 8.0 matrix contract."""

from copy import deepcopy
import xml.etree.ElementTree as ET

from test_job_contracts import JOBS, render_job


source, = render_job(JOBS / "percona-xtrabackup-8.0-compile-param.yml")
canary = ET.Element("matrix-project")
for tag in ("executionStrategy", "combinationFilter", "axes"):
    element = source.find(tag)
    if element is not None:
        canary.append(deepcopy(element))
# Matrix children do not inherit the parent's assignedNode. Keep this
# selection-only job on a launcher even when the logical ARCH is aarch64.
label_axis = ET.SubElement(canary.find("axes"), "hudson.matrix.LabelAxis")
ET.SubElement(label_axis, "name").text = "CANARY_NODE"
ET.SubElement(ET.SubElement(label_axis, "values"), "string").text = "launcher-x64"
ET.SubElement(canary, "description").text = (
    "Selection-only canary for the PXB routine matrix parameter. "
    "Uses actual rendered axes and parameters. Does not build or test PXB."
)
ET.SubElement(canary, "disabled").text = "false"
ET.SubElement(canary, "concurrentBuild").text = "false"
ET.SubElement(canary, "assignedNode").text = "launcher-x64"
ET.SubElement(canary, "canRoam").text = "false"
properties = ET.SubElement(canary, "properties")
properties.append(deepcopy(source.find("properties/hudson.model.ParametersDefinitionProperty")))
ET.SubElement(canary, "scm", {"class": "hudson.scm.NullSCM"})
builder = ET.SubElement(ET.SubElement(canary, "builders"), "hudson.tasks.Shell")
ET.SubElement(builder, "command").text = (
    '#!/bin/sh\nset -eu\n'
    'printf "selection-only OS=%s ARCH=%s TYPE=%s\\n" '
    '"${DOCKER_OS:?}" "${ARCH:?}" "${CMAKE_BUILD_TYPE:?}"\n'
)
ET.SubElement(canary, "publishers")
ET.SubElement(canary, "buildWrappers")
ET.indent(canary)
print(ET.tostring(canary, encoding="unicode", xml_declaration=True))

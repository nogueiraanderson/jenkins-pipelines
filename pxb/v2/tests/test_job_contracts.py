"""Job contracts tested against XML produced by the real JJB renderer."""

from pathlib import Path
import json
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[3]
JOBS = ROOT / "pxb/v2/jenkins"


def render_job(source: Path) -> list[ET.Element]:
    with tempfile.TemporaryDirectory(prefix="pxb-jjb-test-") as output:
        result = subprocess.run(
            ["jenkins-jobs", "test", str(source), "--config-xml", "-o", output],
            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            timeout=30, check=False,
        )
        if result.returncode:
            raise AssertionError(f"JJB failed for {source.name}:\n{result.stdout}")
        rendered = sorted(Path(output).rglob("config.xml"))
        if not rendered:
            raise AssertionError(f"JJB rendered no jobs for {source.name}")
        return [ET.parse(path).getroot() for path in rendered]


class JobContracts(unittest.TestCase):
    def test_80_producer_identity_reaches_declared_consumers(self):
        for suffix in ('test-param', 'test-pipeline', 'test-cloud-pipeline'):
            job, = render_job(JOBS / f'percona-xtrabackup-8.0-{suffix}.yml')
            names = {node.text for node in job.findall('.//parameterDefinitions/*/name')}
            self.assertTrue({'COMPILE_JOB', 'USE_BINARIES_FROM_BUILD_ID'} <= names, suffix)
        for filename, variable in (
            ('percona-xtrabackup-8.0.yml', 'PERCONA_XTRABACKUP_8_0_COMPILE_PARAM_BUILD_NUMBER'),
            ('percona-xtrabackup-8.0-trunk.yml', 'TRIGGERED_BUILD_NUMBERS_percona_xtrabackup_8_0_compile_param'),
        ):
            job, = render_job(JOBS / filename)
            text = ET.tostring(job, encoding='unicode')
            self.assertIn('USE_BINARIES_FROM_BUILD_ID=${' + variable + '}', text)
            self.assertIn('COMPILE_JOB=percona-xtrabackup-8.0-compile-param', text)

    def test_pinning_canary_orders_first_cell_before_second(self):
        result = subprocess.run(
            ["uv", "run", "--no-project", "python",
             str(ROOT / "pxb/v2/tests/render-pinning-canary.py"), "consumer"],
            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=30, check=True,
        )
        job = ET.fromstring(result.stdout)
        strategy = job.find("executionStrategy")
        self.assertEqual(strategy.findtext("runSequentially"), "true")
        self.assertEqual(strategy.findtext("touchStoneCombinationFilter"), "CELL == 'first'")
        self.assertEqual(strategy.findtext("touchStoneResultCondition"), "SUCCESS")
        self.assertEqual(job.findtext("assignedNode"), "launcher-x64")
        self.assertEqual(job.findtext("axes/hudson.matrix.LabelAxis/values/string"), "launcher-x64")
        self.assertEqual(job.find("scm").get("class"), "hudson.scm.NullSCM")
        self.assertEqual(
            job.findtext("properties/EnvInjectJobProperty/info/secureGroovyScript/script"),
            (ROOT / "pxb/v2/ci/pin-matrix-input.groovy").read_text(),
        )

    def test_test_matrices_snapshot_the_producer(self):
        for family in ("2.4", "8.0", "8.1", "9.x"):
            job, = render_job(JOBS / f"percona-xtrabackup-{family}-test-param.yml")
            injection = job.find("properties/EnvInjectJobProperty/info")
            self.assertIsNotNone(injection, f"{family} must pin before matrix fan-out")
            script = injection.findtext("secureGroovyScript/script")
            self.assertEqual(script, (ROOT / "pxb/v2/ci/pin-matrix-input.groovy").read_text())
            values = job.findtext(".//hudson.plugins.parameterizedtrigger.PredefinedBuildParameters/properties")
            self.assertIn("COMPILE_JOB=${PXB_COMPILE_JOB}", values)
            self.assertIn("USE_BINARIES_FROM_BUILD_ID=${PXB_COMPILE_BUILD}", values)

    def test_selection_canary_pins_parent_and_children(self):
        result = subprocess.run(
            ["uv", "run", "--no-project", "python",
             str(ROOT / "pxb/v2/tests/render-matrix-canary.py")],
            cwd=ROOT, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            timeout=30, check=True,
        )
        job = ET.fromstring(result.stdout)
        self.assertEqual(job.findtext("assignedNode"), "launcher-x64")
        self.assertEqual(job.findtext("axes/hudson.matrix.LabelAxis/values/string"), "launcher-x64")
        self.assertEqual(job.find("scm").get("class"), "hudson.scm.NullSCM")
        self.assertEqual(len(job.find("builders")), 1)
        self.assertEqual(job.find("builders")[0].tag, "hudson.tasks.Shell")
        self.assertEqual(len(job.find("publishers")), 0)

    def test_single_platform_script_exists(self):
        for job in render_job(JOBS / "percona-xtrabackup-9.x-single-platform.yml"):
            script = job.findtext("definition/scriptPath")
            self.assertTrue(script, "Rendered SCM pipeline must declare a scriptPath")
            self.assertTrue((ROOT / script).is_file(), f"Missing rendered scriptPath: {script}")

    def test_legacy_24_jobs_render(self):
        for suffix in ("compile-param", "test-param", "trunk"):
            with self.subTest(job=suffix):
                render_job(JOBS / f"percona-xtrabackup-2.4-{suffix}.yml")

    def test_complete_family_renders_with_valid_scripts(self):
        jobs = render_job(JOBS)
        self.assertEqual(len(jobs), 27)
        for job in jobs:
            script = job.findtext("definition/scriptPath")
            if script:
                self.assertTrue((ROOT / script).is_file(), f"Missing rendered scriptPath: {script}")

    def test_legacy_arch_parameter_survives_rendering(self):
        for stage in ("compile", "test"):
            job, = render_job(JOBS / f"percona-xtrabackup-2.4-{stage}-param.yml")
            arch = next(axis for axis in job.find("axes") if axis.findtext("name") == "ARCH")
            self.assertEqual([v.text for v in arch.findall("values/string")], ["x86_64"])
            values = job.findtext(".//hudson.plugins.parameterizedtrigger.PredefinedBuildParameters/properties")
            self.assertIn("ARCH=${ARCH}", values)
            self.assertNotIn("${{", values)

    def test_routine_and_release_matrix_counts(self):
        specs = []
        for family in ("8.0", "8.1", "9.x"):
            for stage in ("compile", "test"):
                name = f"percona-xtrabackup-{family}-{stage}-param"
                job, = render_job(JOBS / f"{name}.yml")
                parameter = job.find(
                    ".//hudson.plugins.matrix__configuration__parameter."
                    "MatrixCombinationsParameterDefinition"
                )
                self.assertIsNotNone(parameter, f"{name} needs a routine default selection")
                multiplier = 2 if stage == "test" else 1
                specs.append({
                    "name": name,
                    "axes": {axis.findtext("name"): [v.text for v in axis.findall("values/string")]
                             for axis in job.find("axes")},
                    "projectFilter": job.findtext("combinationFilter") or "true",
                    "routineFilter": parameter.findtext("defaultCombinationFilter"),
                    "routineCount": (16 if family == "9.x" else 12) * multiplier,
                    "releaseCount": (28 if family == "9.x" else 30) * multiplier,
                })
        result = subprocess.run(
            ["groovy", str(ROOT / "pxb/v2/tests/verify-matrix-profiles.groovy")],
            input=json.dumps(specs), text=True, stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT, cwd=ROOT, timeout=45, check=False,
        )
        self.assertEqual(result.returncode, 0, result.stdout)


if __name__ == "__main__":
    unittest.main(verbosity=2)

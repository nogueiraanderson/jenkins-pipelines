"""Run the real fetch CLI against a fake external AWS boundary."""
import hashlib
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import tarfile
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
FETCH = ROOT / 'pxb/v2/ci/fetch_compile_artifact.py'


class CompileArtifact(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='pxb-artifact-test-')
        self.addCleanup(self.tmp.cleanup)
        self.work = Path(self.tmp.name)
        self.input = self.work / 'input'
        self.input.mkdir()
        self.tag = 'jenkins-review-pr-percona-xtrabackup-8.0-compile-pipeline-41'
        (self.input / 'COMPILE_BUILD_TAG').write_text(self.tag + '\n')
        self.key = self.tag + '/percona-xtrabackup-8.0.35-36-Linux-x86_64-oraclelinux-9.tar.gz'
        self.archive = self.work / 'producer.tar.gz'
        self.make_archive(62)
        self.fixture = {'objects': {self.key: str(self.archive)}}
        self.env = dict(os.environ, PATH=str(ROOT / 'pxb/v2/tests/fixtures') + os.pathsep + os.environ['PATH'],
                        PXB_AWS_FIXTURE=str(self.work / 'aws.json'), PXB_AWS_RECORD=str(self.work / 'calls.jsonl'),
                        PXB_COMPILE_JOB='review/pr/percona-xtrabackup-8.0-compile-pipeline', PXB_COMPILE_BUILD='41',
                        ARCH='x86_64', DOCKER_OS='oraclelinux:9', CMAKE_BUILD_TYPE='RelWithDebInfo',
                        GIT_COMMIT='a' * 40, PXB_PIPELINE_REVISION='b' * 40)

    def make_archive(self, machine):
        header = bytearray(64)
        header[:6] = b'\x7fELF\x02\x01'
        header[18:20] = machine.to_bytes(2, 'little')
        with tarfile.open(self.archive, 'w:gz') as archive:
            member = tarfile.TarInfo('percona-xtrabackup/bin/xtrabackup')
            member.size = len(header)
            archive.addfile(member, io.BytesIO(header))

    def run_fetch(self):
        Path(self.env['PXB_AWS_FIXTURE']).write_text(json.dumps(self.fixture))
        return subprocess.run([sys.executable, str(FETCH), '--input', str(self.input),
                               '--output', str(self.work / 'results/binary.tar.gz'),
                               '--provenance', str(self.work / 'results/compile-input.json')],
                              env=self.env, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                              timeout=10, check=False)

    def test_exact_platform_archive_is_downloaded_and_recorded(self):
        self.fixture['objects'][self.key.replace('x86_64', 'aarch64')] = str(self.archive)
        result = self.run_fetch()
        self.assertEqual(result.returncode, 0, result.stderr)
        provenance = json.loads((self.work / 'results/compile-input.json').read_text())
        self.assertEqual(provenance['key'], self.key)
        self.assertEqual(provenance['producer_build'], 41)
        self.assertEqual(provenance['consumer_pipeline_revision'], 'b' * 40)
        self.assertEqual(provenance['sha256'], hashlib.sha256(self.archive.read_bytes()).hexdigest())
        self.assertEqual((self.work / 'results/binary.tar.gz').read_bytes(), self.archive.read_bytes())

    def test_wrong_elf_architecture_is_rejected_despite_matching_filename(self):
        self.make_archive(183)
        result = self.run_fetch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('ELF architecture', result.stderr)
        self.assertFalse((self.work / 'results/binary.tar.gz').exists())
        self.assertFalse((self.work / 'results/compile-input.json').exists())

    def test_unverified_pipeline_revision_is_rejected_before_aws(self):
        for revision in ('', 'master', 'b' * 39, 'G' * 40, None):
            with self.subTest(revision=revision):
                if revision is None:
                    self.env.pop('PXB_PIPELINE_REVISION', None)
                else:
                    self.env['PXB_PIPELINE_REVISION'] = revision
                result = self.run_fetch()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn('PXB_PIPELINE_REVISION', result.stderr)
                self.assertFalse((self.work / 'calls.jsonl').exists())
                self.assertFalse((self.work / 'results/compile-input.json').exists())

    def test_another_producer_marker_in_the_same_folder_is_rejected(self):
        other = 'jenkins-review-pr-other-compile-pipeline-900'
        (self.input / 'COMPILE_BUILD_TAG').write_text(other + '\n')
        self.fixture['objects'] = {self.key.replace(self.tag, other): str(self.archive)}
        result = self.run_fetch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('does not match the exact producer', result.stderr)
        self.assertFalse((self.work / 'calls.jsonl').exists())

    def test_matrix_input_links_aggregate_build_to_exact_child(self):
        self.env['PXB_COMPILE_JOB'] = 'review/pr/percona-xtrabackup-8.0-compile-param'
        self.env['PXB_COMPILE_BUILD'] = '7'
        cell = self.input / 'ARCH=x86_64,DOCKER_OS=oraclelinux:9'
        cell.mkdir()
        (self.input / 'COMPILE_BUILD_TAG').rename(cell / 'COMPILE_BUILD_TAG')
        (cell / 'PIPELINE_BUILD_NUMBER').write_text('41\n')
        result = self.run_fetch()
        self.assertEqual(result.returncode, 0, result.stderr)
        provenance = json.loads((self.work / 'results/compile-input.json').read_text())
        self.assertEqual(provenance['producer_build'], 7)
        self.assertEqual(provenance['artifact_producer_build'], 41)
        self.assertEqual(provenance['artifact_producer_job'], self.env['PXB_COMPILE_JOB'].replace('-param', '-pipeline'))

    def test_missing_or_ambiguous_platform_archives_fail_before_download(self):
        for objects, count in (({}, 0), (
            {self.key: str(self.archive), self.key.replace('8.0.35-36', '8.0.35-37'): str(self.archive)}, 2
        )):
            with self.subTest(count=count):
                self.fixture['objects'] = objects
                result = self.run_fetch()
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(f'archive, found {count}', result.stderr)
                self.assertFalse((self.work / 'results/binary.tar.gz').exists())
                calls = [json.loads(line) for line in (self.work / 'calls.jsonl').read_text().splitlines()]
                self.assertTrue(all(call[:2] == ['s3api', 'list-objects-v2'] for call in calls))

    def test_asan_debug_selects_only_the_exact_filename_suffix(self):
        self.env.update(DOCKER_OS='asan', CMAKE_BUILD_TYPE='Debug', ARCH='aarch64')
        self.make_archive(183)
        self.key = self.tag + '/percona-xtrabackup-8.0.35-36-Linux-aarch64-asan-asan-debug.tar.gz'
        self.fixture['objects'] = {
            self.key: str(self.archive),
            self.key.replace('-debug', ''): str(self.archive),
            self.key.replace('-asan-asan', '-oraclelinux-9'): str(self.archive),
            self.key.replace('aarch64', 'x86_64'): str(self.archive),
        }
        result = self.run_fetch()
        self.assertEqual(result.returncode, 0, result.stderr)
        provenance = json.loads((self.work / 'results/compile-input.json').read_text())
        self.assertEqual(provenance['key'], self.key)
        self.assertEqual(provenance['elf_machine'], 183)

    def test_aws_failure_is_not_retried_by_an_unbounded_outer_loop(self):
        self.fixture['fail'] = True
        result = self.run_fetch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Compile artifact rejected', result.stderr)
        self.assertEqual(len((self.work / 'calls.jsonl').read_text().splitlines()), 1)
        self.assertFalse((self.work / 'results/binary.tar.gz').exists())

    def test_matrix_marker_must_match_its_paired_child_number(self):
        self.env['PXB_COMPILE_JOB'] = 'review/pr/percona-xtrabackup-8.0-compile-param'
        self.env['PXB_COMPILE_BUILD'] = '7'
        (self.input / 'PIPELINE_BUILD_NUMBER').write_text('42\n')
        result = self.run_fetch()
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('does not match the exact producer', result.stderr)
        self.assertFalse((self.work / 'calls.jsonl').exists())


if __name__ == '__main__':
    unittest.main(verbosity=2)

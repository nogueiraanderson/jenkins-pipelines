"""Execute the worker preflight, substituting only host metadata boundaries."""
import contextlib
import io
import json
import os
from pathlib import Path
import runpy
import unittest
from unittest.mock import patch


SCRIPT = Path(__file__).resolve().parents[1] / 'ci/verify_worker.py'


class WorkerPreflight(unittest.TestCase):
    def run_preflight(self, requested, actual, version=(3, 9, 0)):
        output = io.StringIO()
        with patch.dict(os.environ, ARCH=requested), \
                patch('platform.machine', return_value=actual), \
                patch('sys.version_info', version), contextlib.redirect_stdout(output):
            runpy.run_path(str(SCRIPT), run_name='__main__')
        return json.loads(output.getvalue())

    def test_accepts_both_native_architectures(self):
        for arch in ('x86_64', 'aarch64'):
            with self.subTest(arch=arch):
                result = self.run_preflight(arch, arch)
                self.assertEqual(result, {'worker_arch': arch, 'python': '3.9.0'})

    def test_rejects_wrong_worker_and_invalid_arch(self):
        for requested, actual in (('aarch64', 'x86_64'), ('x86_64', 'aarch64'),
                                  ('arm64', 'arm64'), ('', 'x86_64')):
            with self.subTest(requested=requested, actual=actual):
                with self.assertRaisesRegex(SystemExit, 'Worker architecture'):
                    self.run_preflight(requested, actual)

    def test_rejects_python_before_39(self):
        with self.assertRaisesRegex(SystemExit, 'Python 3.9'):
            self.run_preflight('x86_64', 'x86_64', (3, 8, 20))


if __name__ == '__main__':
    unittest.main(verbosity=2)

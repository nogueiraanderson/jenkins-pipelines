#!/usr/bin/env python3
"""Fail before product work when the worker cannot consume native artifacts."""
import json
import os
import platform
import sys


requested = os.environ.get('ARCH', '')
actual = platform.machine()
if requested not in ('x86_64', 'aarch64') or actual != requested:
    sys.exit('Worker architecture {} does not match supported ARCH={!r}'.format(actual, requested))
if sys.version_info < (3, 9):
    sys.exit('Python 3.9 or newer is required for artifact verification')
print(json.dumps({'worker_arch': actual, 'python': '.'.join(map(str, sys.version_info[:3]))}))

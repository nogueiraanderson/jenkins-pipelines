#!/usr/bin/env python3
"""Fetch exactly one platform archive from an already pinned Jenkins producer."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
import tempfile
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--provenance', type=Path, required=True)
    args = parser.parse_args()
    arch = os.environ['ARCH']
    platform = os.environ['DOCKER_OS']
    build_type = os.environ['CMAKE_BUILD_TYPE']
    producer = os.environ['PXB_COMPILE_JOB']
    build = os.environ['PXB_COMPILE_BUILD']
    pipeline_revision = os.environ['PXB_PIPELINE_REVISION']
    if not re.fullmatch(r'[a-f0-9]{40}', pipeline_revision):
        raise ValueError('PXB_PIPELINE_REVISION must be the full checked-out commit SHA')
    if arch not in ('x86_64', 'aarch64') or build_type not in ('RelWithDebInfo', 'Debug'):
        raise ValueError('Unsupported ARCH or CMAKE_BUILD_TYPE')
    if not re.fullmatch(r'[a-z][a-z0-9]*(?::[a-z0-9.]+)?', platform):
        raise ValueError('Unsupported DOCKER_OS')
    if not re.fullmatch(r'[1-9][0-9]{0,8}', build):
        raise ValueError('Compile build must already be pinned to a positive number')
    suffix = '-Linux-' + arch + '-' + platform.replace(':', '-')
    if platform == 'asan':
        suffix += '-asan'
    if build_type == 'Debug':
        suffix += '-debug'
    suffix += '.tar.gz'
    tags = {}
    folder = producer.rpartition('/')[0]
    for marker in args.input.rglob('COMPILE_BUILD_TAG'):
        tag = marker.read_text().strip()
        if not re.fullmatch(r'jenkins-[A-Za-z0-9_.-]+-[1-9][0-9]*', tag):
            raise ValueError('Malformed COMPILE_BUILD_TAG')
        if folder and not tag.startswith('jenkins-' + folder.replace('/', '-') + '-'):
            raise ValueError('Compile marker points outside the producer folder')
        child_job, child_build = producer, build
        if producer.endswith('-compile-param'):
            child_job = producer.removesuffix('-compile-param') + '-compile-pipeline'
            child_build = marker.with_name('PIPELINE_BUILD_NUMBER').read_text().strip()
            if not re.fullmatch(r'[1-9][0-9]{0,8}', child_build):
                raise ValueError('Matrix artifact has no exact compile child build number')
        if tag != 'jenkins-' + child_job.replace('/', '-') + '-' + child_build:
            raise ValueError('Compile marker does not match the exact producer job and build')
        tags[tag] = (child_job, int(child_build))
    if not tags or len(tags) > 64:
        raise ValueError('Expected 1 to 64 compile markers in the dedicated input directory')
    deadline = time.monotonic() + 600
    aws_env = dict(os.environ, AWS_MAX_ATTEMPTS='3', AWS_RETRY_MODE='standard', AWS_PAGER='')

    def aws(*command):
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError('Artifact retrieval exceeded 600 seconds')
        return subprocess.run(['aws', *command, '--cli-connect-timeout', '10', '--cli-read-timeout', '30'],
                              env=aws_env, check=True, text=True, stdout=subprocess.PIPE,
                              stderr=subprocess.PIPE, timeout=min(remaining, 120)).stdout

    candidates = {}
    for tag in sorted(tags):
        listing = json.loads(aws('s3api', 'list-objects-v2', '--bucket', 'pxb-build-cache',
                                 '--prefix', tag + '/', '--output', 'json'))
        for item in listing.get('Contents', []):
            key = item['Key']
            name = key.removeprefix(tag + '/')
            if '/' not in name and name.startswith('percona-xtrabackup-') and name.endswith(suffix):
                candidates[key] = item['Size']
    if len(candidates) != 1:
        raise ValueError(f'Expected exactly one {arch}/{platform}/{build_type} archive, found {len(candidates)}')
    key, expected_size = next(iter(candidates.items()))
    child_job, child_build = tags[key.partition('/')[0]]
    if args.output.exists() or args.provenance.exists():
        raise ValueError('Refusing to reuse existing binary or provenance output')
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.pxb-download-', dir=args.output.parent) as temporary:
        binary = Path(temporary) / 'binary.tar.gz'
        aws('s3', 'cp', 's3://pxb-build-cache/' + key, str(binary), '--no-progress', '--only-show-errors')
        if binary.stat().st_size != expected_size or expected_size <= 0:
            raise ValueError('Downloaded archive size differs from the selected object')
        digest = hashlib.sha256()
        with binary.open('rb') as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b''):
                digest.update(chunk)
        executables = 0
        expected_machine = {'x86_64': 62, 'aarch64': 183}[arch]
        with tarfile.open(binary, 'r|gz') as archive:
            for member in archive:
                if time.monotonic() > deadline:
                    raise TimeoutError('Artifact verification exceeded 600 seconds')
                if member.name.endswith('/bin/xtrabackup') or member.name == 'bin/xtrabackup':
                    executables += 1
                    if not member.isfile():
                        raise ValueError('Archive xtrabackup must be a regular ELF executable')
                    header = archive.extractfile(member).read(20)
                    if len(header) != 20 or header[:6] != b'\x7fELF\x02\x01' or int.from_bytes(header[18:20], 'little') != expected_machine:
                        raise ValueError(f'Archive ELF architecture does not match {arch}')
        if executables != 1:
            raise ValueError(f'Expected exactly one bin/xtrabackup executable, found {executables}')
        provenance = dict(schema=1, producer_job=producer, producer_build=int(build),
                          artifact_producer_job=child_job, artifact_producer_build=child_build,
                          bucket='pxb-build-cache', key=key, sha256=digest.hexdigest(), size=expected_size,
                          arch=arch, docker_os=platform, build_type=build_type,
                          elf_machine=expected_machine,
                          consumer_pipeline_revision=pipeline_revision)
        binary.replace(args.output)
        args.provenance.parent.mkdir(parents=True, exist_ok=True)
        args.provenance.write_text(json.dumps(provenance, indent=2) + '\n')
        print(f"Selected {producer} #{build}: {key} SHA256={provenance['sha256']}")


if __name__ == '__main__':
    try:
        main()
    except (KeyError, ValueError, OSError, EOFError, tarfile.TarError, subprocess.SubprocessError) as error:
        raise SystemExit(f'Compile artifact rejected: {error}')

# PXB job contract checks

Run from the repository root with Groovy 2.4+, Python 3.9+ and JJB 6.5.0 available.
The checks need no Jenkins credentials and do not run Docker, AWS or product builds.

```sh
bash pxb/v2/tests/check
```

The checks parse actual Jenkinsfiles and reject runtime repository fetches that
bypass `checkout scm`. They execute the actual ARCH guards, matrix snapshot hook,
copy helper and 8.0 consumer DSL through their Jenkins-step boundaries. The actual
rendered shell strings pass `bash -n`. Python tests run the real JJB renderer and
execute the artifact fetcher and Docker runner with only their external commands
replaced by isolated fixtures.

These are local contract checks, not a complete Jenkins or product validation.
Run a pinned Jenkins canary as well. Do not treat an unpublished local fix as
validated by a build of a different remote revision.

The 8.0 compile and test pipelines check native worker architecture and Python
3.9+ immediately after their pinned SCM checkout. The repository preview set
publishes only the 8.0 compile and regular test pipelines. Run each architecture
with its exact completed compile build number, then inspect the archived input
provenance and require a named JUnit smoke test with zero failures or errors.

## Routine and release selection

The six 8.0, 8.1 and 9.x matrix parents use the installed Matrix Combinations
plugin. Routine defaults select OL9, Ubuntu Jammy and Debian Bookworm, plus
AL2023 for 9.x. Debug and RelWithDebInfo remain available on both architectures.
The complete axes and unsupported-platform exclusions remain unchanged.

| Family | Routine compile | Routine test | Full compile | Full test |
|--------|-----------------|--------------|--------------|-----------|
| 8.0 | 12 | 24 | 30 | 60 |
| 8.1 | 12 | 24 | 30 | 60 |
| 9.x | 16 | 32 | 28 | 56 |

Counts include both architectures. Per architecture, routine compile has six
platform/build combinations, or eight on 9.x. Tests include two server targets.
ASAN is in full coverage, not the routine default. PXB 2.4 remains x86-only.

For full release coverage, select **All** in the matrix parent's build form.
For API builds of that parent, `MATRIX_COMBINATIONS=true` selects all combinations
allowed by its permanent filter. Select the same coverage on the test parent,
and use the exact compile-parent build number for `USE_BINARIES_FROM_BUILD_ID`.
An automated trigger that supplies no selection receives the routine default.
This does not add a new release orchestrator.

See the [plugin documentation](https://plugins.jenkins.io/matrix-combinations-parameter/).
The contract tests check counts for the rendered filters. The canary renderer
prints a harmless job containing the real axes and parameter definitions, with
only a logging shell step. Run it on a uniquely named job to verify that Jenkins
uses the routine default and honors an explicit full selection. It tests matrix
selection only, not execution of ARM binaries.

Never publish this selection canary under an existing job name. Do not copy or
delete existing MultiJob phase names, the plugin's folder-blind deletion listener
can affect production jobs with matching basenames.

## Pinned compile input, 8.0 integration

The 8.0 regular and cloud consumers accept `COMPILE_JOB` plus
`USE_BINARIES_FROM_BUILD_ID`. Normal 8.0 launchers pass their just-completed
compile matrix build. The test matrix snapshots its producer before fan-out.
A standalone pipeline defaults to its sibling compile pipeline and snapshots
the latest successful build once. An explicit positive build number overrides
that default. Producers outside the consumer's exact folder are rejected.

Copy Artifact writes its selected build number into a clean numbered target
directory. This is persistent evidence, unlike the plugin's transient result
environment action, which is not exposed by all Pipeline/plugin combinations.
The same selected build must be completed and successful before S3 is queried.
Matrix markers must match the sibling compile child and its paired build number.

Artifact retrieval has a 10-minute outer Jenkins limit and bounded native AWS
retries. Exactly one archive must match ARCH, DOCKER_OS and build type. The
fetcher rejects a mismatched native ELF architecture without running the binary.
The archived `compile-input.json` records aggregate and child producer IDs, S3
key, downloaded-byte SHA-256, size and platform. The checksum records the bytes
consumed, it is not a comparison against an upstream signed checksum.

The copy canary renderer embeds the actual helper and checks its SHA-256 on the
worker. Run it under unique producer/test pipeline names in the same folder.
It validates native Copy Artifact and waitForBuild behavior, not S3 retrieval
or PXB binaries. Retain positive and intentional failure builds for review.

The other PXB consumer families are not yet integrated with the new fetcher.
Do not deploy their matrix snapshot changes independently of their consumers.

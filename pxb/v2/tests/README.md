# PXB job contract checks

Run from the repository root with Groovy 2.4+, Python 3.9+ and JJB 6.5.0 available.
The checks need no Jenkins credentials and do not run Docker, AWS or product builds.

```sh
groovy pxb/v2/tests/verify-pipeline-source.groovy &&
groovy pxb/v2/tests/verify-arch-preflight.groovy &&
uv run --no-project python pxb/v2/tests/test_job_contracts.py
```

The first check parses actual Jenkinsfiles and rejects runtime pipeline-repository
fetches that bypass `checkout scm`. The second executes the actual pre-pipeline
code with valid, missing and unsupported ARCH values, stopping at the Declarative
pipeline boundary. The Python tests run the real JJB renderer and evaluate its
resulting script paths, variable expansion, axes and Groovy matrix filters.

These are local contract checks, not a complete Jenkins or product validation.
Run a pinned Jenkins canary as well. Do not treat an unpublished local fix as
validated by a build of a different remote revision.

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
This does not add a new release orchestrator or change artifact selection code.

See the [plugin documentation](https://plugins.jenkins.io/matrix-combinations-parameter/).
The contract tests check counts for the rendered filters. The canary renderer
prints a harmless job containing the real axes and parameter definitions, with
only a logging shell step. Run it on a uniquely named job to verify that Jenkins
uses the routine default and honors an explicit full selection. It tests matrix
selection only, not execution of ARM binaries.

Never publish this selection canary under an existing job name. Do not copy or
delete existing MultiJob phase names, the plugin's folder-blind deletion listener
can affect production jobs with matching basenames.

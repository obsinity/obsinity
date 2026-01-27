# Obsinity E2E Harness (Maven)

Standalone, removable API-only end-to-end (E2E) harness for the Obsinity reference service.
It builds the reference-service JAR, spins up fresh containers + volumes, runs the tests,
and writes artifacts for debugging.

## Quick Start

```bash
cd e2e-groovy
./scripts/run_e2e.sh
```

Per-test output prints as:
```
TEST START: <methodName>
```

## What It Does

1. Cleans previous compose resources for this project
2. Builds the reference-service JAR (Maven)
3. Builds Docker images (no cache by default)
4. Starts Postgres + reference service
5. Waits for health endpoint
6. Runs API tests (publish -> query)
7. Collects artifacts
8. Tears down containers (unless told to keep them)

## Requirements

- Docker + Docker Compose
- JDK 17+
- Maven
- curl

## Environment Variables

Core:
- `OBSINITY_RUN_ID` (optional): run identifier for isolation (default: generated UUID).
- `OBSINITY_BASE_URL` (optional): override base URL for API tests (default: `http://localhost:18086`).
- `OBSINITY_E2E_PORT` (optional): host port for the reference service (default: `18086`).
- `OBSINITY_E2E_PARALLEL` (optional): set to `1` to suffix the compose project name with the run id.

Health checks:
- `OBSINITY_HEALTH_URL` (optional): health endpoint URL (default: `http://localhost:<port>/api/admin/config/ready`).
- `OBSINITY_HEALTH_TIMEOUT_SECONDS` (optional): health wait timeout in seconds (default: `120`).
- `OBSINITY_HEALTH_INTERVAL_SECONDS` (optional): health polling interval in seconds (default: `2`).

Docker:
- `OBSINITY_DOCKER_NOCACHE` (optional): set to `0` to allow cached builds (default: `1` for `--no-cache`).
- `OBSINITY_KEEP_CONTAINERS` (optional): set to `1` to keep containers running after tests.

Test output:
- `OBSINITY_TEST_QUIET` (optional): set to `1` to suppress Maven test output (default: `0`).
- `OBSINITY_SUREFIRE_ARGS` (optional): override Surefire console args
  (default: `-Dsurefire.useFile=false -Dsurefire.reportFormat=brief -Dsurefire.printSummary=true`).

## Artifacts

Artifacts are written to:

```
e2e-groovy/artifacts/<runId>/
```

Files include:
- `summary.json` (always)
- `junit.xml` (always, if tests executed)
- `failures/<testName>.json` (on failure)
- `docker.log`
- `docker.ps.txt`
- `docker.inspect.json`
- `reference-config-snapshot/`

## Tests

Tests live in:
- `e2e-groovy/src/test/java/com/obsinity/e2e/ApiE2EJUnitTest.java`
- `e2e-groovy/src/test/java/com/obsinity/e2e/Junit4SmokeTest.java`

Test descriptions (what each test proves and why it exists):
- `Junit4SmokeTest.junit4DiscoveryWorks`:
  run a minimal JUnit4 test.
  verify Maven+Surefire are executing JUnit4 tests (no “green build but zero tests run”).
- `ApiE2EJUnitTest.immediateTransitionCounts_simple`:
  send NEW -> ACTIVE.
  verify immediate transition count NEW->ACTIVE is 1.
- `ApiE2EJUnitTest.immediateTransitionCounts_backAndForth`:
  send NEW -> ACTIVE -> NEW -> ACTIVE.
  verify NEW->ACTIVE=2 and ACTIVE->NEW=1 (no accidental dedupe).
- `ApiE2EJUnitTest.configuredTransition_countsWithIntermediates`:
  send NEW -> ACTIVE -> ARCHIVED.
  verify configured NEW->ARCHIVED counter increments despite intermediate state.
- `ApiE2EJUnitTest.configuredTransition_countsDespiteBacktracking`:
  send NEW -> ACTIVE -> NEW -> ARCHIVED.
  verify configured NEW->ARCHIVED increments once despite backtracking.
- `ApiE2EJUnitTest.ratio_finishedVsAbandoned_usesConfiguredTransitions`:
  send one NEW->ARCHIVED and one NEW->BLOCKED.
  verify counts match and ratio splits 50/50 using configured counters.
- `ApiE2EJUnitTest.ratio_zeroDenominator`:
  query ratios with no matching data.
  verify counts and ratios are zero (no divide-by-zero).

All tests are API-only (no DB access) and tolerate eventual consistency via polling.
Each run uses a unique `runId` and isolates events by `obsinity.run_id`.

## Troubleshooting

- Port already in use:
  - set `OBSINITY_E2E_PORT=18087` (or any free port)
- Health check timeout:
  - set `OBSINITY_HEALTH_TIMEOUT_SECONDS=240`
  - check `artifacts/<runId>/docker.log`
- Need to inspect running containers:
  - set `OBSINITY_KEEP_CONTAINERS=1`

## Feeding Failures to Codex

Attach these files:
- `summary.json`
- `junit.xml`
- `failures/*.json`
- `docker.log`

## Notes

- Config lives in `e2e-groovy/reference-config/` and is mounted read-only into the container.
- Transition counters are defined in `reference-config/service-definitions/services/payments/state-extractors.yaml`.

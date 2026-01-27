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
- `Junit4SmokeTest.junit4DiscoveryWorks`: verifies Maven+Surefire are actually executing JUnit4 tests. This is a guardrail against “green build but zero tests run.”
- `ApiE2EJUnitTest.immediateTransitionCounts_simple`: sends a single NEW -> ACTIVE transition and expects a count of 1. This confirms the basic ingest → state-extract → transition-count pipeline works end-to-end.
- `ApiE2EJUnitTest.immediateTransitionCounts_backAndForth`: sends NEW -> ACTIVE -> NEW -> ACTIVE and expects NEW->ACTIVE=2 and ACTIVE->NEW=1. This ensures repeated transitions and reversals are counted correctly, not deduped away.
- `ApiE2EJUnitTest.configuredTransition_countsWithIntermediates`: sends NEW -> ACTIVE -> ARCHIVED and expects the configured NEW->ARCHIVED counter to increment. This proves configured transitions count across intermediate states.
- `ApiE2EJUnitTest.configuredTransition_countsDespiteBacktracking`: sends NEW -> ACTIVE -> NEW -> ARCHIVED and expects NEW->ARCHIVED to increment once. This confirms backtracking does not break configured transition counting.
- `ApiE2EJUnitTest.ratio_finishedVsAbandoned_usesConfiguredTransitions`: sends one NEW->ARCHIVED and one NEW->BLOCKED and expects both counts and a 50/50 ratio. This validates ratio math and that ratios use configured counters (not raw immediate transitions).
- `ApiE2EJUnitTest.ratio_zeroDenominator`: queries ratios with no matching data and expects zeros. This protects against divide-by-zero and makes “no data” behavior explicit.

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

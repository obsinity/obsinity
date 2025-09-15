# Repository Guidelines

## Project Structure & Module Organization
- Maven multi‑module project (Java 17). Root `pom.xml` lists modules like `obsinity-core`, `obsinity-client-*`, `obsinity-controller-*`, and `obsinity-reference-service`.
- Source code: `MODULE/src/main/java`. Tests: `MODULE/src/test/java`.
- Documentation: `documentation/` (architecture, comparisons, migration, schema).
- Docker: root `docker-compose.yml` (Postgres). Reference service has its own `docker-compose.yml` and helper scripts under `obsinity-reference-service/`.

## Build, Test, and Development Commands
- Build all modules: `mvn clean verify`
- Faster local build (skip tests): `mvn -DskipTests clean install`
- Format check/fix (Spotless): `mvn spotless:check` / `mvn spotless:apply`
- Build a module and its deps: `mvn -pl <module> -am clean verify`
- Run reference service:
  - Scripts: `cd obsinity-reference-service && ./build.sh && ./run.sh`
  - Maven: `mvn -pl obsinity-reference-service spring-boot:run`
- Start local DB only: `docker compose up -d` (root `docker-compose.yml`).

## Coding Style & Naming Conventions
- Formatting enforced by Spotless + Palantir Java Format (CI runs `spotless:check`).
- Java 17, UTF‑8; unused imports are removed automatically.
- Naming: packages `lowercase`; classes `PascalCase`; methods/fields `camelCase`; constants `UPPER_SNAKE_CASE`.
- Keep modules focused; shared types live in `obsinity-core`.

## Testing Guidelines
- JUnit 5 via Surefire. Place tests under `MODULE/src/test/java` named `*Test.java`.
- Run all tests: `mvn test` (or `mvn clean verify`). Module‑only: `mvn -pl <module> -am test`.
- Add tests for new logic and bug fixes; prefer fast, deterministic unit tests.

## Commit & Pull Request Guidelines
- Commits: imperative mood, focused, tidy history. Conventional Commits style is welcome (e.g., `feat(ingest): ...`).
- PRs: clear description, linked issues, scope of change, and test coverage. Include run notes for service changes (e.g., how to verify with the reference service).
- CI must pass (formatting and builds). No unrelated reformatting; run `mvn spotless:apply` before pushing.

## Security & Configuration Tips
- Do not commit secrets; `.env` is used for the reference service. Default local Postgres is defined in root `docker-compose.yml`.
- Enforced Java version via Maven Enforcer: use JDK 17.

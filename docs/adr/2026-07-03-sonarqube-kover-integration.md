# 2026-07-03: SonarQube + Kover Integration

## Context

The project needs static analysis and a Quality Gate on `main` and on pull requests. SonarCloud's default **Automatic Analysis** mode does not compile the code or run tests, it can only see raw source, so it cannot measure test coverage. Without coverage data, the Sonar Way Quality Gate's "Coverage on New Code" condition would always evaluate against 0%, failing every analysis regardless of actual test quality.

## Options Considered

1. **SonarCloud Automatic Analysis**: zero CI setup, but no coverage data and no control over the JDK/toolchain used to build the code, so `Long`/KSP-specific compile-time signals from the KSP annotation processor pipeline aren't reflected accurately.
2. **CI-based analysis with JaCoCo applied directly**: the JaCoCo Gradle plugin is the most common choice for JVM coverage, but it instruments raw bytecode and does not understand Kotlin-specific constructs (inline functions, coroutines, `when` exhaustiveness branches) as precisely as a Kotlin-aware tool, leading to noisy or misleading line/branch counts.
3. **CI-based analysis with Kover**: JetBrains' official coverage plugin for Kotlin/JVM and Kotlin Multiplatform, built on top of the JVM bytecode instrumentation but Kotlin-aware. It can emit a JaCoCo-format XML report, which Sonar already knows how to consume via `sonar.coverage.jacoco.xmlReportPaths`, so no Sonar-side plugin or config beyond that property is needed.

## Decision

Use **GitHub Actions** to run `./gradlew build koverXmlReport sonar` instead of SonarCloud's Automatic Analysis, and use **Kover** (`org.jetbrains.kotlinx.kover`) instead of JaCoCo directly to produce the coverage report Sonar reads.

- `org.sonarqube` and `org.jetbrains.kotlinx.kover` are applied at the root project in `build.gradle.kts`.
- Kover is also applied to `:processor` (the only module with tests today) and declared as a merge target from the root via `dependencies { kover(project(":processor")) }`, since Kover does not auto-merge subproject coverage just by being applied at the root.
- `sonar.coverage.jacoco.xmlReportPaths` points at `build/reports/kover/report.xml`, the default output location of the root's `koverXmlReport` task.
- `.github/workflows/build.yml` runs on push to `main` and on PR open/sync/reopen, with `fetch-depth: 0` (required for Sonar's "New Code" relevancy) and both `GITHUB_TOKEN` and `SONAR_TOKEN` available to the Gradle run.

## Consequences

- **CI time increases.** Every push to `main` and every PR now runs a full `build koverXmlReport sonar` cycle instead of relying on SonarCloud's out-of-band Automatic Analysis, which added no time to the developer's own CI.
- **Coverage is currently scoped to `:processor`.** It is the only module with tests today. If `:annotations` or `:gradle-plugin` gain tests in the future, they must be added explicitly to the root's `dependencies { kover(project(...)) }` block or their coverage will silently be excluded from the merged report.
- Automatic Analysis must stay **disabled** in the SonarCloud project settings; running both Automatic Analysis and CI-based analysis on the same project causes conflicting/duplicate analyses.
- `processor-tests.yml` remains a separate, faster test-only workflow (no Sonar step) that runs on every push/PR to any branch, giving quick feedback without waiting on the full Sonar cycle.

## Migration Plan

No migration needed for existing consumers; this only affects the project's own CI. Follow-up: if `:annotations` or `:gradle-plugin` gain tests, add them to the root `dependencies { kover(project(...)) }` block in the same PR that adds the tests.

## Owner
Thierry Simões

## Date
2026-07-03

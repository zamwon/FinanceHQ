---
bootstrapped_at: 2026-05-19T14:08:40Z
starter_id: spring
starter_name: Spring Boot
project_name: finance-hq
language_family: java
package_manager: maven
cwd_strategy: subdir-then-move
bootstrapper_confidence: verified
phase_3_status: ok
audit_command: "null"
---

## Hand-off

```yaml
starter_id: spring
package_manager: maven
project_name: finance-hq
hints:
  language_family: java
  team_size: solo
  deployment_target: fly
  ci_provider: github-actions
  ci_default_flow: auto-deploy-on-merge
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: false
  has_ai: false
  has_background_jobs: true
```

## Why this stack

Solo developer shipping a personal finance obligation tracker in 3 weeks (after-hours).
The PRD requires auth (FR-001, FR-002) and scheduled email notifications one business day before each due date (FR-007),
making background job support a must-have. Spring Boot is the recommended default for `(web-app, java)` and passes all four agent-friendly
quality gates — typed (Java's static type system), convention-based (Spring's auto-configuration and opinionated project layout), well-represented in training data,
and thoroughly documented. The auth requirement is met by Spring Security; the scheduled notification need maps naturally to Spring's `@Scheduled` or a Quartz-backed cron job.
Fly is the starter's default deployment target; GitHub Actions with auto-deploy-on-merge is the standard CI shape for a solo project with a hard July 2026 deadline.
Scaffolding confidence is verified — bootstrapper has been run end-to-end on this stack.

## Pre-scaffold verification

| Signal      | Value                                                           | Severity | Notes                                                                                 |
| ----------- | --------------------------------------------------------------- | -------- | ------------------------------------------------------------------------------------- |
| npm package | not run                                                         | —        | Spring starter uses `curl \| tar`, not an npm CLI; no package name to resolve         |
| GitHub repo | not run                                                         | —        | `docs_url` (`https://docs.spring.io/spring-boot/`) is not a GitHub URL; no recency signal available |

## Scaffold log

**Resolved invocation**: `mkdir -p .bootstrap-scaffold && cd .bootstrap-scaffold && curl -s 'https://start.spring.io/starter.tgz' -d dependencies=web,devtools -d type=maven-project -d javaVersion=21 -d groupId=com.example -d artifactId=finance-hq | tar -xzf -`

**Strategy**: subdir-then-move (scaffolded into a temp directory, then moved files up into cwd)

**Exit code**: 0

**Files moved**: 10

- `.gitattributes`
- `.gitignore`
- `HELP.md`
- `mvnw`
- `mvnw.cmd`
- `.mvn/wrapper/maven-wrapper.properties`
- `pom.xml`
- `src/main/java/com/example/finance_hq/FinanceHqApplication.java`
- `src/main/resources/application.properties`
- `src/test/java/com/example/finance_hq/FinanceHqApplicationTests.java`

**Conflicts (.scaffold siblings)**: none

**.gitignore handling**: moved silently (no root-level `.gitignore` existed in cwd)

**.bootstrap-scaffold cleanup**: deleted

**Note on `{name}` substitution**: The Spring Initializr `artifactId` parameter (used in `pom.xml`) was set to `finance-hq` (the hand-off `project_name`) rather than `.bootstrap-scaffold`. The `.bootstrap-scaffold` temp directory served as the extraction target; the artifactId in the generated `pom.xml` correctly reflects the project name.

## Post-scaffold audit

**Tool**: skipped — no built-in audit tool for `java`

**Recommended external tools**:
- [OWASP Dependency-Check](https://owasp.org/www-project-dependency-check/) — scans Maven/Gradle dependency trees for known CVEs; integrates as a Maven plugin (`org.owasp:dependency-check-maven`).
- [Snyk](https://snyk.io) — hosted SCA with IDE and CI integrations; free tier available for open-source projects.

## Hints recorded but not acted on

| Hint                    | Value              |
| ----------------------- | ------------------ |
| bootstrapper_confidence | verified           |
| quality_override        | false              |
| path_taken              | standard           |
| self_check_answers      | null               |
| team_size               | solo               |
| deployment_target       | fly                |
| ci_provider             | github-actions     |
| ci_default_flow         | auto-deploy-on-merge |
| has_auth                | true               |
| has_payments            | false              |
| has_realtime            | false              |
| has_ai                  | false              |
| has_background_jobs     | true               |

These flags were read from the hand-off and logged here for the future M1L4 skill ("Memory Architecture"), which will use them to generate `CLAUDE.md`, `AGENTS.md`, and CI/CD scaffolding. Bootstrapper v1 surfaces but does not act on them.

## Next steps

Next: a future skill will set up agent context (CLAUDE.md, AGENTS.md). For now, your project is scaffolded and verified — happy hacking.

Useful manual steps in the meantime:
- `git add .` and `git commit` to capture the scaffold as the initial commit.
- Review `HELP.md` (Spring Initializr's standard getting-started doc) — rename or delete it once you've read it.
- Add Spring Security and Spring Boot Starter Mail dependencies to `pom.xml` for the `has_auth` and `has_background_jobs` requirements from the PRD.
- Run `./mvnw spring-boot:run` to confirm the scaffold starts cleanly before adding features.
- Address audit findings per your project's risk tolerance — configure OWASP Dependency-Check or Snyk for ongoing Maven dependency scanning.

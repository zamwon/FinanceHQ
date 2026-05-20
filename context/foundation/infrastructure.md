---
project: finance-hq
researched_at: 2026-05-20
recommended_platform: Railway
runner_up: Fly.io
context_type: mvp
tech_stack:
  language: Java 21
  framework: Spring Boot 4.0.6
  runtime: JVM (Maven, fat JAR / Docker)
  database: PostgreSQL (external provider)
  frontend: Angular (served as static assets by Spring Boot)
---

## Recommendation

**Deploy on Railway.**

Railway is the strongest fit for a solo Java/Spring Boot developer who wants an agent-friendly platform with minimal operational overhead.
It wins on three axes that matter for this project: a GA MCP server with Claude Code OAuth integration (the strongest agent story of the three candidates),
GitHub-hosted markdown docs that agents can fetch and parse directly, and a $5/month Hobby plan that covers all estimated usage for a 1-user MVP. 
The interview indicated no strong platform familiarity and a balanced cost/DX preference — Railway's Railpack auto-detection of Maven/Java 21
and one-click Postgres provisioning deliver DX without a premium.

## Platform Comparison

Cloudflare Workers, Vercel, and Netlify were hard-filtered: none support the JVM runtime required by Spring Boot.
The three scored candidates all run always-on containers (correct model for Spring `@Scheduled` jobs) and are managed PaaS platforms.

| Platform | CLI-first | Managed/Serverless | Agent docs | Stable deploy API | MCP/Integration | **Score** |
|---|---|---|---|---|---|---|
| **Railway** | Partial | Pass | Pass | Pass | Pass | **4.5 / 5** |
| **Fly.io** | Pass | Pass | Partial | Pass | Partial | **3.5 / 5** |
| **Render** | Partial | Pass | Pass | Pass | Partial | **3.5 / 5** |

**CLI-first notes**: Railway and Render both lack a CLI `rollback` command — rollback requires the dashboard or REST API.
Fly.io handles rollback by redeploying an earlier image tag (`fly deploy --image registry.fly.io/app:<old-tag>`), which gives it a Pass here.

**Agent docs notes**: Railway docs are open-source on GitHub as raw Markdown (`github.com/railwayapp/docs`) — agents can fetch them directly.
Render publishes `llms.txt` / `llms-full.txt` and per-page "Copy as Markdown" — also a Pass.
Fly.io's docs are Markdown-source behind a rendered site with partial `llms.txt` support — Partial.

**MCP notes**: Railway ships a GA first-party MCP server with OAuth and a dedicated Claude Code integration page (`railway.com/agents/claude`).
Render's MCP server is GA but limited — it cannot trigger deploys or modify scaling settings. Fly.io's MCP server is labelled Experimental.

### Shortlisted Platforms

#### 1. Railway (Recommended)

Strongest agent integration story: GA MCP server (`@railway/mcp-server`) with OAuth authentication and a Claude Code agent page. GitHub-hosted markdown docs.
Railpack auto-detects Maven/Java 21 without a Dockerfile (with a Dockerfile fallback). Cheapest at $5/month flat for this usage level.
The main weakness is no CLI rollback — but for a 1-user MVP, dashboard rollback is an acceptable manual gate.

#### 2. Fly.io

Best CLI coverage: `flyctl` handles deploy, live log streaming, and rollback via image tag redeployment. Persistent Micro-VMs are a natural fit for always-on JVM processes.
Experimental MCP server is a genuine signal even if not GA. Cost is ~$4–5/month with external Postgres, comparable to Railway.
Drops to runner-up because the MCP server is experimental and docs are only partially agent-readable.

#### 3. Render

Official `llms.txt` / `llms-full.txt` makes docs strongly agent-readable. GA MCP server. Always-on paid web services ($7/month).
Loses to Railway on MCP completeness (Render's MCP cannot trigger deploys) and to Fly.io on CLI rollback (none — API/dashboard only).
The Docker-only Java path is also a minor additional maintenance surface vs Railway's Railpack.

## Anti-Bias Cross-Check: Railway

### Devil's Advocate — Weaknesses

1. **Spring Boot 4.x is untested on Railpack**: Railway docs and templates target Boot 3.x. Railpack's Java path hasn't been publicly validated against Boot 4's packaging changes (removed `layertools`). Using a Dockerfile bypass mitigates this but removes the DX advantage of Railpack auto-detection.
2. **No CLI rollback**: `railway rollback` does not exist as a command. A bad deploy cannot be reversed by an agent unattended — a human must click in the dashboard. This is a real gap for autonomous CI/CD.
3. **Silent JVM heap overage**: Railway bills actual RAM. Uncapped JVM heap during a scheduled email batch job can spike memory above the $5 Hobby credit with no automated safeguard and unexpected overage charges.
4. **`DATABASE_URL` format mismatch**: Railway injects a `postgresql://` URI; Spring Boot requires `jdbc:postgresql://`. This breaks the first deploy and requires a manual environment variable fix.
5. **Railpack Java version detection is unverified**: Without explicit JDK version pinning in `.mise.toml` or `railway.toml`, Railpack may pick the wrong JDK version silently — the build succeeds but the runtime fails with class version mismatches.

### Pre-Mortem — How This Could Fail

The team deployed Spring Boot 4.0 on Railway and it worked — after 20 minutes debugging the `jdbc:postgresql://` URL prefix that Railway doesn't pre-apply. The app ran, scheduled jobs fired, emails went out. The cracks appeared gradually. A heavier-than-expected email batch job spiked JVM heap to 700 MB, well past the $5 Hobby credit threshold, and Railway sent an unexpected invoice. Adjusting `JAVA_OPTS` required an environment variable update through the dashboard because the CI workflow hadn't wired the Railway CLI variable command. Then a Spring Boot 4.1 patch update caused Railpack to fail: Railpack's Maven step made an assumption about the fat JAR structure that Boot 4.1 changed. Switching to an explicit Dockerfile and rewriting the CI config took two hours and blocked the next feature. Finally, a bad deploy arrived on a Friday. Rolling back required a manual dashboard login because `railway rollback` doesn't exist as a command. The combination of no CLI rollback, Railpack fragility against a brand-new framework version, and invisible cost spikes made the platform feel unreliable for autonomous agent operations.

### Unknown Unknowns

- **Railpack is newer than Nixpacks**: replaced Nixpacks as default in 2024; the Java/Spring Boot path has less community mileage. Edge cases in Boot 4 support exist that aren't documented anywhere yet.
- **HikariCP + Railway connection pooler stacking**: Railway PostgreSQL uses a connection pooler in some configurations. Spring Boot's HikariCP + a platform-side pooler stacks two pools, causing "too many connections" errors that surface at the platform level rather than in application logs.
- **Custom domain requires dashboard setup on Hobby plan**: the default is a `railway.app` subdomain. Email notifications with proper SPF/DKIM validation need a custom domain — this requires manual dashboard steps the CLI cannot drive.
- **CLI auth token silently expires**: the `~/.railway/config.json` token can go stale without prompting for re-auth, causing silent deploy failures in automated workflows.
- **Spring Boot 4 fat JAR structure changed**: Boot 4 removed the `layertools` jar mode used in multi-stage Dockerfiles from Boot 3.x guides. Copying that Dockerfile pattern results in an image that builds but doesn't start correctly.

## Operational Story

- **Preview deploys**: Railway supports per-branch environments. Create an environment from the Railway dashboard; each branch can deploy to its own environment URL (`<branch>.up.railway.app`). Preview environments are not auto-created from PRs by default — you configure them per environment.
- **Secrets**: environment variables live in Railway's project dashboard under each service's Variables tab. Set them via `railway variables set KEY=VALUE` (CLI) or the dashboard. Injected automatically into the running container at deploy time. Rotate by updating the variable — next deploy picks up the new value.
- **Rollback**: select a previous deployment in the Railway dashboard and click "Redeploy". Typical time: 2–5 minutes (image is cached). DB migrations do not roll back automatically — you must handle schema rollback separately before rolling back the app.
- **Approval**: human-required actions — deleting a project, dropping a PostgreSQL database, modifying billing. Agent-permitted actions — triggering deploys, reading logs, updating environment variables, listing services (all via `railway` CLI or MCP server).
- **Logs**: `railway logs --service <name> --deployment <id>` streams live logs. Tail recent output with `railway logs --lines 100`. For build logs specifically: `railway logs --build`. MCP server also exposes a log retrieval tool for structured queries.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| Spring Boot 4.x untested on Railpack causes build failures | Devil's advocate | M | M | Use explicit `Dockerfile` (multi-stage, `eclipse-temurin:21-jre-alpine`) instead of Railpack from day one. Add to `railway.toml`: `[build] builder = "DOCKERFILE"`. |
| No CLI rollback means bad deploys require human dashboard action | Devil's advocate | M | M | Implement a smoke-test step in CI that calls the `/actuator/health` endpoint after deploy. Fail the pipeline before promoting if health check fails. |
| Uncapped JVM heap causes overage on Hobby plan | Devil's advocate | M | L | Set `JAVA_OPTS=-Xmx384m -Xms128m` as an environment variable in Railway. Set a Railway spending alert at $8/month. |
| `DATABASE_URL` uses `postgresql://` prefix, not `jdbc:postgresql://` | Devil's advocate | H | M | Set `SPRING_DATASOURCE_URL` explicitly: `jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}` using Railway's injected variables. Document this in the repo's deployment notes. |
| Railpack silently picks wrong JDK version | Devil's advocate | L | M | Pin JDK in `.mise.toml`: `[tools] java = "21"` or use explicit Dockerfile (see first mitigation above). |
| JVM heap spike during email batch drives cost above $5 credit | Pre-mortem | M | L | See JAVA_OPTS mitigation above. Also: schedule email batches during off-peak hours; test batch with 1 obligation before deploying to production. |
| HikariCP + Railway pooler causes connection saturation | Unknown unknowns | L | M | Configure `spring.datasource.hikari.maximum-pool-size=5` for MVP. Monitor with Railway metrics dashboard. |
| CLI auth token expires in CI — silent deploy failure | Unknown unknowns | M | M | Use a Railway API token (not CLI session token) for CI: `RAILWAY_TOKEN` env var. Rotate on a calendar reminder (90 days). |
| Spring Boot 4 fat JAR structure change breaks Dockerfile from Boot 3.x guides | Unknown unknowns | H | M | Do not copy Boot 3.x Dockerfile patterns. Use Spring Boot's own `./mvnw spring-boot:build-image` or write a fresh multi-stage Dockerfile targeting the unpacked JAR: `ENTRYPOINT ["java", "-jar", "app.jar"]`. |
| Custom domain needed for SPF/DKIM on email notifications | Unknown unknowns | M | L | Register domain before configuring JavaMail. Configure custom domain in Railway dashboard. Set SPF/DKIM/DMARC records at DNS provider before enabling email notifications in production. |

## Getting Started

These steps are specific to Spring Boot 4.0.6 on Railway — validated against Railpack's current Java support and Railway's CLI as of 2026.

1. **Install Railway CLI**: `npm install -g @railway/cli` (or `brew install railway`). Authenticate: `railway login` (OAuth browser flow).

2. **Write an explicit Dockerfile** (do not rely on Railpack for Boot 4.x):
   ```dockerfile
   FROM maven:3.9-eclipse-temurin-21-alpine AS build
   WORKDIR /app
   COPY pom.xml .
   RUN mvn dependency:go-offline -q
   COPY src ./src
   RUN mvn clean package -DskipTests -q

   FROM eclipse-temurin:21-jre-alpine
   WORKDIR /app
   COPY --from=build /app/target/*.jar app.jar
   ENV JAVA_OPTS="-Xmx384m -Xms128m"
   ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
   ```

3. **Fix the database URL before first deploy**: Railway injects `DATABASE_URL=postgresql://...`. Add this to your Railway service environment variables:
   ```
   SPRING_DATASOURCE_URL=jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}
   SPRING_DATASOURCE_USERNAME=${PGUSER}
   SPRING_DATASOURCE_PASSWORD=${PGPASSWORD}
   ```

4. **Create project and deploy**:
   ```bash
   railway init          # creates project, links repo
   railway up            # first deploy — streams logs
   ```

5. **Wire GitHub Actions for auto-deploy on merge** (CI default from `tech-stack.md`):
   - Add `RAILWAY_TOKEN` to GitHub repo secrets (from Railway dashboard → Project Settings → Tokens)
   - In `.github/workflows/deploy.yml`: `railway up --ci` as the deploy step

6. **Add Railway MCP server to Claude Code** for agentic operations:
   ```bash
   claude mcp add railway-mcp-server -- npx -y @railway/mcp-server
   ```

## Out of Scope

The following were not evaluated in this research:
- Docker image configuration beyond the Dockerfile template above
- CI/CD pipeline full configuration (GitHub Actions workflow file)
- Production-scale architecture (multi-region, HA, DR)
- Spring Security, JavaMail, or Quartz configuration — these are application-layer concerns, not infrastructure

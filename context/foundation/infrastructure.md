---
project: finance-hq
researched_at: 2026-05-20
recommended_platform: Railway
runner_up: Render
context_type: mvp
tech_stack:
  language: Java 21
  framework: Spring Boot 4.0.6
  runtime: JVM (Docker container)
  database: PostgreSQL via Supabase (external)
  frontend: Angular (SPA, served as static assets)
---

## Recommendation

**Deploy on Railway.**

Railway runs always-on Docker containers (persistent JVM process), which is required for Spring Boot's `@Scheduled` email notification jobs. At $5/month flat (Hobby plan includes $5 usage credit), it is the most cost-effective viable option for this single-user MVP. The platform ships a functional MCP server with Claude Code OAuth integration — an asset for an AI-assisted solo development workflow. Two pre-deploy steps are mandatory before first launch: write an explicit Dockerfile (Railpack has a Boot 4.x validation gap) and configure Supabase via the Session Pooler URL (Railway is IPv4-only; Supabase direct connections resolve to IPv6 only).

## Platform Comparison

**Hard filters applied first** — three platforms were eliminated before scoring:

- **Cloudflare Workers**: Java/JVM cannot run on V8 isolates. 128MB RAM cap, 10MB bundle size limit, and 1-second startup limit are all hard architectural mismatches with Spring Boot (~60–100MB JAR, 2–10s startup). No configuration workaround exists. (Angular SPA could separately use Cloudflare Pages as CDN, but splitting frontend/backend across platforms adds complexity with no cohesive deployment story.)
- **Vercel**: No Java/JVM runtime at any tier. Compute model is exclusively serverless microVM functions — no persistent process support. `@Scheduled` background jobs are impossible.
- **Netlify**: Java runtime explicitly unsupported (JS/TS/Go only). Same structural mismatch as Vercel for persistent JVM processes.

| Platform | CLI-first | Managed | Agent docs | Deploy API | MCP/Integration | Score |
|---|---|---|---|---|---|---|
| **Railway** | Partial | Pass | Pass | Pass | Pass (WIP) | 4/5 |
| **Render** | Pass | Pass | Pass | Pass | Pass (GA) | 5/5 |
| **Fly.io** | Pass | Pass | Partial | Pass | Partial (experimental) | 3.5/5 |
| Cloudflare | — | — | — | — | — | DROPPED (JVM incompatible) |
| Vercel | — | — | — | — | — | DROPPED (no JVM runtime) |
| Netlify | — | — | — | — | — | DROPPED (no JVM runtime) |

**Scoring notes:**

- Railway CLI-first scored Partial: `railway rollback` does not exist — reverting a bad deploy requires a dashboard click or API scripting with a manual deploy ID.
- Railway MCP scored Pass with caveat: the Claude Code integration page is production-quality with OAuth, but Railway's own docs flag the server as "work in progress."
- Render scored 5/5 on agent-friendly criteria: GA MCP server (launched August 2025), `llms.txt` + `llms-full.txt`, first-party Claude Code skills (`render-deploy`, `render-debug`, `render-monitor`). Render was the initial top scorer but dropped to runner-up after the anti-bias cross-check surfaced that 512MB Starter RAM is tight for Spring Boot 4.x JVM (Standard at $25/month may be needed), and the user opted for Railway's $5/month pricing.
- Fly.io docs are GitHub-hosted Markdown (`.html.markerb` format, accessible via raw URLs) but have no `llms.txt` and no dedicated Java language guide — Partial. MCP server (`fly mcp server`) is explicitly marked experimental as of 2026-05-20.

### Shortlisted Platforms

#### 1. Railway (Recommended)

Always-on Docker containers with `@Scheduled` support, $5/month flat, functional OAuth-backed MCP server with Claude Code integration, public GitHub docs as raw Markdown. Two mandatory pre-deploy steps (Dockerfile + Supabase pooler URL) mitigate the Boot 4.x and IPv6 risks before first deploy. Total expected cost at MVP scale: $5/month with no overage if JVM heap is capped at 384MB.

#### 2. Render

The strongest agent-friendly profile overall: GA MCP server, `llms.txt` + `llms-full.txt`, first-party Claude Code skills, deterministic `render deploys create --wait` CI command. Selected against by the user after cross-check surfaced that 512MB Starter RAM is tight for Spring Boot 4.x JVM — Standard tier at $25/month may be needed — making total cost $25–32/month vs Railway's $5/month.

#### 3. Fly.io

Most mature Docker-JVM community story, comprehensive `flyctl` CLI, GitHub-hosted Markdown docs. Critical requirement: must set `auto_stop_machines = "off"` in `fly.toml` or `@Scheduled` tasks stop firing when no HTTP traffic arrives. MCP server is experimental. Slightly higher first-time operational overhead than Railway; no official Java language guide (community resources fill the gap).

## Anti-Bias Cross-Check: Railway

### Devil's Advocate — Weaknesses

1. **Supabase IPv6 is a silent hard blocker**: Railway is IPv4-only; Supabase direct connection strings resolve to IPv6 only. The app deploys successfully and then crashes at startup with a generic "Connection refused" error — no IPv6 mention in the logs. Hours of debugging without prior knowledge of this incompatibility.
2. **Railpack Boot 4.x validation gap**: Railpack auto-detects Java/Maven but Boot 4.0 changed the fat JAR structure. Railpack may silently generate an incorrect start command, causing a `ClassNotFoundException` at container startup. The zero-config appeal disappears — a Dockerfile is required for reliable Boot 4.x deployment.
3. **No CLI rollback**: `railway rollback` does not exist. Reverting a bad deploy at midnight requires either a dashboard click or manually constructing an API call with the correct deploy ID.
4. **JVM heap overage risk**: Railway's $5/month includes $5 usage credit at $10/GB RAM. An uncapped JVM during a scheduled email batch can spike to 600MB+, generating overage on the first month. Requires explicit `JAVA_TOOL_OPTIONS=-Xmx384m` and a Railway spend alert.
5. **MCP server self-flagged as "work in progress"**: The Claude integration page presents as production-ready, but Railway's official MCP docs include a WIP disclaimer — potential undocumented breaking changes without deprecation notice.

### Pre-Mortem — How This Could Fail

The Spring Boot app deployed on Railway after a frustrating first hour: Railpack silently broke the Boot 4.x fat JAR start command, causing the container to exit immediately with a `ClassNotFoundException`. A Dockerfile fixed it. Month two: Supabase stopped connecting. The developer had used the direct Supabase connection string, not the Session Pooler. Railway's IPv4-only network couldn't resolve Supabase's IPv6 address. Three hours of debugging — Railway logs said "Connection refused" with no IPv6 indication. The fix was a one-line URL change to the pooler endpoint, but finding it required a GitHub community thread. Month three: a surprise bill. No JVM heap cap had been set. The daily notification job loaded all obligations, fired JavaMail, and peaked at 600MB RAM. The $5 usage credit ran out in week two; the month-end invoice was $13. Setting `-Xmx384m` in `JAVA_TOOL_OPTIONS` resolved it, but the developer only found the cause after manually reading Railway billing metrics. Three separate failure modes — each fixable in under an hour with foreknowledge, but together they consumed an entire evening without it.

### Unknown Unknowns

- **Supabase IPv6 failure looks like a Spring Boot networking bug**: The error message is "Connection refused," not "IPv6 address not reachable." Easy to spend hours adjusting Spring Boot datasource config before discovering the cause is Railway's IPv4-only infrastructure.
- **`SPRING_DATASOURCE_URL` is never auto-injected for external databases**: Railway auto-injects `DATABASE_URL` in `postgresql://` format only for its own Postgres add-on. For external Supabase, you must manually set `SPRING_DATASOURCE_URL=jdbc:postgresql://[pooler-host]:5432/postgres`. There is no automatic `postgresql://` → `jdbc:postgresql://` transformation.
- **MCP build log gap**: Railway's MCP server surfaces service runtime logs and deploy status, but Maven/Docker build-time failures may not be accessible via MCP tools — the agent falls back to `railway logs --build`.
- **Spring Boot 4.x Jakarta EE namespace migration**: Boot 4.0 uses `jakarta.*` packages exclusively. Any transitive dependency still importing `javax.*` causes `ClassNotFoundException` at runtime — easy to misattribute to Railway infrastructure when it is a dependency issue. Run `./mvnw dependency:tree | grep javax` before first deploy.

## Operational Story

- **Preview deploys**: Railway supports per-branch environments configured manually from the dashboard. Each environment gets its own URL (`<branch>.up.railway.app`). PR preview URLs are not auto-created — you configure environments per branch. No protection gate on preview URLs by default.
- **Secrets**: Environment variables are stored in Railway's project per-service Variables tab. Set via `railway variable set KEY=value` (CLI) or dashboard. Encrypted at rest by Railway; not readable in plaintext after setting. Use project-scoped `RAILWAY_TOKEN` (from Project Settings → Tokens) for CI/CD — not the interactive session token.
- **Rollback**: No `railway rollback` CLI command. To revert: go to Railway dashboard → Deployments tab → find the last known-good deploy → click "Redeploy". Alternatively: `railway up` with a known-good Git commit. Typical time to revert: 2–4 minutes (Maven build + container start). Database migrations do not roll back automatically — use Flyway undo scripts if schema changes need reverting.
- **Approval**: Agent may perform unattended: `railway up` (deploy), `railway logs` (tail logs), `railway variable set/list` (env vars), `railway redeploy` (redeploy latest). Human required for: deleting a service or project, modifying billing plan, rotating Railway tokens, executing database schema destructive operations.
- **Logs**: `railway logs` (tail live runtime logs); `railway logs --build` (build-time logs); `railway logs -n 100` (last N lines). MCP server also exposes log retrieval as a structured tool call for agent use.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| Supabase IPv6 connection failure on Railway | Unknown unknowns | High | High | Use Supabase **Session Pooler** host (`[ref].pooler.supabase.com:5432`) in `SPRING_DATASOURCE_URL`; set `jdbc:postgresql://` prefix manually before first deploy |
| Railpack Boot 4.x start command mismatch | Devil's advocate | High | High | Write explicit Dockerfile with `ENTRYPOINT ["java", "-jar", "/app.jar"]`; do not rely on Railpack auto-detection for Boot 4.x |
| JVM heap overage charges | Devil's advocate | Medium | Medium | Set `JAVA_TOOL_OPTIONS=-Xmx384m -Xms128m` in Railway env vars; configure Railway spend alert at $8/month before first deploy |
| No CLI rollback for bad deploys | Devil's advocate | Medium | Medium | Implement post-deploy health check in CI (`/actuator/health`); document manual rollback procedure (dashboard → Deployments → Redeploy) in repo before first deploy |
| MCP server breaking change (WIP status) | Devil's advocate | Low | Low | Pin `@railway/mcp-server` to a specific version in Claude Code MCP config; test MCP tools after each Railway CLI update |
| Jakarta EE `javax.*` transitive dependency | Unknown unknowns | Low | High | Run `./mvnw dependency:tree \| grep javax` before deploy; exclude any `javax.*` transitive dependencies found |
| `SPRING_DATASOURCE_URL` format not auto-injected | Research finding | High | High | Explicitly set `SPRING_DATASOURCE_URL=jdbc:postgresql://...` in Railway Variables — do not rely on Railway's injected `DATABASE_URL` for external Supabase connections |
| HikariCP + Supabase pooler double-pool | Research finding | Low | Medium | Set `spring.datasource.hikari.maximum-pool-size=5` for MVP; monitor Supabase connection dashboard for pool exhaustion |

## Getting Started

1. **Install Railway CLI** (GA, verified 2026-05-20):
   ```bash
   npm i -g @railway/cli
   # or: brew install railway (macOS)
   railway login
   ```

2. **Write an explicit Dockerfile** at project root — do not rely on Railpack for Boot 4.x:
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
   EXPOSE 8080
   ENTRYPOINT ["java", "-jar", "app.jar"]
   ```

3. **Set environment variables** in Railway project settings (Variables tab) — set these before first deploy:
   ```
   SPRING_DATASOURCE_URL=jdbc:postgresql://[ref].pooler.supabase.com:5432/postgres
   SPRING_DATASOURCE_USERNAME=postgres.[ref]
   SPRING_DATASOURCE_PASSWORD=[your-supabase-db-password]
   JAVA_TOOL_OPTIONS=-Xmx384m -Xms128m
   SERVER_PORT=8080
   ```
   Use the Supabase **Session Pooler** hostname (`[ref].pooler.supabase.com`), not the direct connection hostname. Find it in Supabase dashboard → Project Settings → Database → Connection string → Session mode.

4. **Add `server.port` binding** to `src/main/resources/application.properties`:
   ```properties
   server.port=${SERVER_PORT:8080}
   ```

5. **Deploy**:
   ```bash
   railway link          # link local directory to Railway project
   railway up            # first deploy (builds Docker image, streams logs)
   railway logs          # verify startup — look for "Started FinanceHqApplication"
   ```

6. **Add Railway MCP server** to Claude Code for agent-driven operations (optional, WIP):
   ```bash
   claude mcp add railway-mcp-server -- npx -y @railway/mcp-server
   ```
   Authenticate via OAuth when prompted. No API key required — uses Railway account session.

## Out of Scope

The following were not evaluated in this research:
- Docker image layer caching optimization
- CI/CD pipeline setup (GitHub Actions `RAILWAY_TOKEN` integration)
- Production-scale architecture (multi-region, HA, DR)
- Angular frontend CDN strategy (Cloudflare Pages in front of Railway is a viable future addition)

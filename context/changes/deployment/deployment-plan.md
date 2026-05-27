# FinanceHQ — Railway First Deploy Plan

## Context

The Spring Boot 4.0.6 skeleton is scaffolded with a Dockerfile, `railway.toml`, and a GitHub Actions workflow, but it has never been successfully deployed. Three code-level bugs will cause the first deploy to fail without fixes: Railway injects `PORT` (not `SERVER_PORT`), the health check timeout is too low for a cold JVM, and the Dockerfile uses a `JAVA_OPTS` expansion pattern that conflicts with Railway's recommended `JAVA_TOOL_OPTIONS` env var. Database: Supabase (external) via Session Pooler to avoid Railway's IPv4/Supabase IPv6 incompatibility.

---

## Phase 0 — Pre-deploy code fixes

> These are local file changes committed before any Railway interaction. No Railway account needed yet.

### 0.1 Fix `src/main/resources/application.properties`

- [OK ] Add `server.port=${PORT:8080}` — Railway injects `PORT`, not `SERVER_PORT`; without this the app binds on 8080 but Railway health-checks a different port, causing deploy failure
- [OK ] Add `management.endpoints.web.exposure.include=health` — Spring Boot 4.x actuator is locked down by default; `/actuator/health` must be explicitly exposed
- [OK ] Add `management.endpoint.health.show-details=never` — avoid leaking env var values in health response

**Final file:**
```properties
spring.application.name=finance-hq
server.port=${PORT:8080}
management.endpoints.web.exposure.include=health
management.endpoint.health.show-details=never
```

> Datasource properties (`spring.datasource.*`) are intentionally omitted — no JDBC driver is in pom.xml yet. Add them when JPA dependency is wired.

### 0.2 Fix `railway.toml`

- [OK ] Change `healthcheckTimeout` from `60` → `300` — JVM cold start (classloading + Spring context init) regularly exceeds 60 s on Railway's shared compute; 300 s is safe without burning the maximum (3600 s)
- [OK ] No other changes needed; `builder = "DOCKERFILE"` and `restartPolicyType = "ON_FAILURE"` are correct

**Final file:**
```toml
[build]
builder = "DOCKERFILE"
dockerfilePath = "Dockerfile"

[deploy]
healthcheckPath = "/actuator/health"
healthcheckTimeout = 300
restartPolicyType = "ON_FAILURE"
restartPolicyMaxRetries = 3
```

### 0.3 Fix `Dockerfile`

- [OK ] Remove `ENV JAVA_OPTS=...` line — this creates a fixed baked-in value that cannot be overridden from Railway's Variables tab
- [OK ] Change `ENTRYPOINT` from `["sh", "-c", "java $JAVA_OPTS -jar app.jar"]` → `["java", "-jar", "app.jar"]` — with no shell expansion needed, a direct exec ENTRYPOINT is safer (no shell PID 1, signals propagate correctly)
- [OK ] Heap will be controlled via `JAVA_TOOL_OPTIONS` environment variable set in Railway (Phase 2) — the JVM reads this automatically

**Final Dockerfile:**
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
ENV JAVA_OPTS="-Xmx384m -Xms128m"                                                                                                                                         
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]   
```

### 0.4 Fix `.github/workflows/deploy.yml`

- [OK ] Replace fixed `sleep 30` with a retry loop — `railway up --ci` exits after build completes, not after Railway finishes health-checking; 30 s is routinely insufficient for JVM startup
- [OK ] Use `--retry` curl flag to make health check resilient

**Fixed health check step:**
```yaml
- name: Health check
  run: |
    echo "Waiting for app to be ready..."
    for i in $(seq 1 20); do
      STATUS=$(curl -s -o /dev/null -w "%{http_code}" https://${{ secrets.RAILWAY_PUBLIC_URL }}/actuator/health || echo "000")
      echo "Attempt $i: HTTP $STATUS"
      if [ "$STATUS" = "200" ]; then
        echo "Health check passed"
        exit 0
      fi
      sleep 15
    done
    echo "Health check failed after 5 minutes"
    exit 1
  env:
    RAILWAY_PUBLIC_URL: ${{ secrets.RAILWAY_PUBLIC_URL }}
```

### 0.5 Jakarta EE sanity check

- [OK ] Run locally before first deploy: `./mvnw dependency:tree | grep javax`
- [OK ] Expected output: empty (no `javax.*` packages). If any appear, exclude them from the offending transitive dependency — Boot 4.x uses `jakarta.*` exclusively; a stray `javax.*` causes `ClassNotFoundException` at runtime that looks like a Railway issue

### 0.6 Commit & push

- [OK ] Commit all changes on `master`/`main` with message: `fix: pre-deploy Railway config corrections`
- [OK ] Do NOT push yet — push happens in Phase 4 after Railway and GitHub secrets are configured

---

## Phase 1 — Railway account & project setup (human gate)

> Everything in this phase requires a browser. Agent cannot perform these steps.

- [OK ] **Create Railway account** at https://railway.app (or log in if existing)
- [OK] **Upgrade to Hobby plan** ($5/month) — free tier does not support always-on containers; `@Scheduled` email jobs require persistent process
- [OK] **Create a new project** via Railway dashboard → "New Project" → "Empty project"
- [OK ] **Create a service** inside the project → "Deploy from GitHub repo" → select `FinanceHQ`
  - Set deploy branch: `master` (or `main` — match your default branch)
- [OK ] **Set a spend alert**: Railway dashboard → Account Settings → Billing → Spend Alert → set at **$8/month** (catches JVM heap overage before the $10 monthly credit runs out)
- [OK ] **Generate a project token**: Project Settings → Tokens → "New Token" → scope to this project → copy value
  - This becomes `RAILWAY_TOKEN` in GitHub Secrets (Phase 4)
- [OK ] **Copy the public URL**: Service → Settings → Networking → Public URL
  - This becomes `RAILWAY_PUBLIC_URL` in GitHub Secrets (Phase 4) — format: `your-service.up.railway.app` (no `https://`)

---

## Phase 2 — Supabase setup (human gate)

> Railway is IPv4-only. Supabase direct connections resolve to IPv6. **Session Pooler is mandatory.**

- [OK ] **Create Supabase project** at https://supabase.com → "New project" (or use existing)
- [OK ] **Get Session Pooler connection string**: Supabase dashboard → Project Settings → Database → "Connection string" tab → select **"Session mode"**
  - Format: `postgresql://postgres.[ref]:[password]@[ref].pooler.supabase.com:5432/postgres`
- [OK ] **Note the three values** for Railway env vars:
  - Pooler host: `[ref].pooler.supabase.com`
  - Username: `postgres.[ref]`
  - Password: your Supabase DB password (Project Settings → Database → Database password)

> ⚠️ **Edge case — Supabase connection refused with no IPv6 mention in logs**: If the app deploys and then logs show `Connection refused` or
> `ConnectException`, the root cause is almost certainly IPv4/IPv6 mismatch. Verify you are using the Session Pooler hostname (`[ref].pooler.supabase.com`),
> NOT the direct host (`db.[ref].supabase.co`). The direct host resolves to IPv6 only; Railway cannot reach it.

---

## Phase 3 — Railway environment variables

> Set in Railway dashboard: Service → Variables tab. Or via CLI after `railway link`.

- [OK] `JAVA_TOOL_OPTIONS` = `-Xmx384m -Xms128m`
  - Caps JVM heap; prevents overage charges on Railway's $10/GB billing
- [OK ] `SPRING_DATASOURCE_URL` = `jdbc:postgresql://[ref].pooler.supabase.com:5432/postgres`
  - **Must** use `jdbc:postgresql://` prefix — Railway's auto-injected `DATABASE_URL` uses `postgresql://` which Spring Boot rejects without a custom converter
- [OK ] `SPRING_DATASOURCE_USERNAME` = `postgres.[ref]`
- [OK ] `SPRING_DATASOURCE_PASSWORD` = `[your-supabase-db-password]`

> ⚠️ **Edge case — HikariCP + Supabase pooler double-pool**: Supabase Session Pooler already pools connections server-side. Keep HikariCP pool small. Add to `application.properties` when JPA is wired: `spring.datasource.hikari.maximum-pool-size=5`

> ⚠️ **Edge case — `SPRING_DATASOURCE_URL` ignored at startup**: If the app starts and logs `DataSource not configured` or ignores the env var, verify Railway staged the variable change (click "Deploy" after adding variables — Railway does not auto-redeploy on variable changes unless auto-deploy is enabled for that environment).

---

## Phase 4 — GitHub Actions secrets

- [OK ] In your GitHub repo → Settings → Secrets and variables → Actions → "New repository secret":
  - `RAILWAY_TOKEN` = the project token from Phase 1
  - `RAILWAY_PUBLIC_URL` = the public URL from Phase 1 (no `https://` prefix)
- [OK ] Verify the workflow file path is `.github/workflows/deploy.yml`

---

## Phase 5 — First deploy

```bash
# From project root
npm install -g @railway/cli          # or: brew install railway
railway login                         # opens browser OAuth
railway link                          # select project + environment (production)
railway up                            # first deploy — streams Docker build + deploy logs
```

- [OK] Run `railway login`
- [OK ] Run `railway link` — select the project created in Phase 1
- [OK] Run `railway up` (not `--ci`) — watch logs in terminal; confirm you see `Started FinanceHqApplication`
- [OK] Run `railway logs` — tail live logs; confirm no `Connection refused` or startup errors

> ⚠️ **Edge case — `ClassNotFoundException` at startup**: If the container starts and immediately exits with `ClassNotFoundException`, the JAR was built incorrectly. Verify locally: `./mvnw clean package -DskipTests && java -jar target/*.jar`. If it fails locally too, it is a pom.xml issue, not a Railway issue. Check for `javax.*` transitive deps (Phase 0.5). If it only fails on Railway, check Docker build logs: `railway logs --build`.

> ⚠️ **Edge case — `railway up` exits with "no service found"**: The local directory is not linked. Run `railway link` again and select the correct project and environment.

> ⚠️ **Edge case — health check timeout hit**: If Railway logs show the container starting but the deploy fails with "health check timed out", the app is taking longer than 300 s to be ready. Increase `healthcheckTimeout` in `railway.toml` to `600` and redeploy. Also verify `/actuator/health` is exposed (Phase 0.1).

---

## Phase 6 — Smoke test

- [OK] `curl https://<railway-url>/actuator/health` → expected: `{"status":"UP"}`
- [OK ] Check Railway dashboard → Deployments → confirm status is "Active"
- [ ] Check Railway billing → confirm no unexpected usage spike

---

## Phase 7 — CI/CD pipeline activation

- [ OK] Push the Phase 0 commit to `master`/`main` (now that secrets are set)
- [OK ] Watch GitHub Actions → "Deploy to Railway" workflow run
- [ ] Confirm: build step passes, health check retry loop exits with "Health check passed"
- [ ] Confirm: next push to main auto-deploys without manual intervention

> ⚠️ **Edge case — GitHub Actions `railway up --ci` exits 0 but deploy fails**: `--ci` exits after the build succeeds, before Railway completes the deploy and health check. The retry loop in Phase 0.4 compensates for this gap — it polls for up to 5 minutes. If the health check loop times out in CI, check Railway's own deploy status in the dashboard to distinguish a build failure from a slow startup.

---

## Phase 8 — Optional: Railway MCP server

> Adds structured agent-driven log access and deploy status queries to Claude Code. WIP status — Railway flags this as "work in progress".

- [ ] `claude mcp add railway-mcp-server -- npx -y @railway/mcp-server`
- [ ] Authenticate via OAuth when prompted
- [ ] Test: ask Claude Code to "check the latest Railway deploy status" — should return structured response
- [ ] **If broken after a Railway CLI update**: pin the version — `npx -y @railway/mcp-server@<last-working-version>`

---

## Future integration notes (not blocking current deploy)

### SMTP / email notifications (FR-007)

Research found Railway may block outbound ports 25, 465, and 587 by default. When implementing email notifications (`JavaMail` + `@Scheduled`):

- **Do not** use direct SMTP to Gmail/Outlook from Railway
- **Use a transactional email API** instead: Mailgun, SendGrid, or Resend all provide HTTP APIs (not SMTP) that bypass port restrictions and are more reliable for single-user notification volume
- Add the API key as a Railway environment variable when implementing FR-007
- Spring Boot 4.x compatible: `spring-boot-starter-mail` still works if Railway doesn't actually block 587 — verify with a test send before committing to an API-based approach

---

## Files modified

| File | Change |
|------|--------|
| `src/main/resources/application.properties` | Add `PORT` binding + actuator exposure |
| `railway.toml` | Increase `healthcheckTimeout` 60 → 300 |
| `Dockerfile` | Remove `JAVA_OPTS` env, simplify `ENTRYPOINT` |
| `.github/workflows/deploy.yml` | Replace `sleep 30` with retry loop |

## Verification checklist

- [ ] `curl https://<railway-url>/actuator/health` returns `{"status":"UP"}`
- [ ] Railway dashboard shows deployment as "Active"
- [ ] GitHub Actions workflow passes end-to-end on push to main
- [ ] Railway spend alert is configured at $8/month
- [ ] No `javax.*` packages in `./mvnw dependency:tree` output

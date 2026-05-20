---
project: finance-hq
deployed_at: 2026-05-20
platform: Railway
environment: production
---

## Deployment Summary

First production deployment of FinanceHQ to Railway.

**Stack**: Spring Boot 4.0.6 / Java 21 / PostgreSQL  
**Builder**: Explicit Dockerfile (not Railpack — Boot 4.x unverified on Railpack)  
**CI/CD**: GitHub Actions → `railway up --ci` on push to master/main

## What's deployed

- Spring Boot skeleton with `/actuator/health` endpoint
- PostgreSQL service provisioned on Railway
- GitHub Actions auto-deploy pipeline wired

## Environment variables set on Railway

- `SPRING_DATASOURCE_URL` — `jdbc:postgresql://${PGHOST}:${PGPORT}/${PGDATABASE}`
- `SPRING_DATASOURCE_USERNAME` — `${PGUSER}`
- `SPRING_DATASOURCE_PASSWORD` — `${PGPASSWORD}`
- `JAVA_OPTS` — `-Xmx384m -Xms128m`

## GitHub Secrets required

- `RAILWAY_TOKEN` — Railway API token (Project Settings → Tokens)
- `RAILWAY_PUBLIC_URL` — Railway public URL without `https://`

## Verification

```bash
curl https://<railway-url>/actuator/health
# Expected: {"status":"UP"}
```

## Risk mitigations applied

| Risk | Mitigation |
|------|------------|
| Railpack + Boot 4.x incompatibility | Explicit Dockerfile from day one |
| `DATABASE_URL` format mismatch | `SPRING_DATASOURCE_URL=jdbc:postgresql://...` |
| JVM heap overage | `JAVA_OPTS=-Xmx384m -Xms128m` |
| CLI token expiry in CI | `RAILWAY_TOKEN` API token (not session token) |
| No CLI rollback | Smoke test health check in CI pipeline |

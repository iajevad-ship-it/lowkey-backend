# Lowkey API on Render

## What this deploys
- `lowkey-api` — Ktor backend (Docker)
- `lowkey-db` — managed Postgres
- `lowkey-redis` — managed Redis (Key Value) for auth nonces + SOS presence

## Deploy (Dashboard)
1. Push this repo to GitHub.
2. In [Render](https://dashboard.render.com) → **New** → **Blueprint** → select the repo.
3. Apply `render.yaml`. Wait for the first deploy (Postgres + Redis + API).
4. Open the service URL → `https://<service>.onrender.com/health` should return `{"status":"ok"}`.
5. Check API logs for `Bootstrap invite code (use once…)` — use that for the first account.
6. Point the Android app at that host:

```properties
# Lowkey/local.properties (or Gradle -P flags)
lowkey.api.baseUrl=https://<service>.onrender.com
lowkey.ws.baseUrl=wss://<service>.onrender.com
```

Then rebuild the release APK.

## Notes
- Free web services on Render **spin down** after idle time; first request can take ~30–60s. For always-on, upgrade the web plan.
- Free Postgres may require a paid plan on newer Render accounts — upgrade if Blueprint creation fails on the DB.
- Set `FCM_ENABLED=true` + Firebase credentials later for push; not required for chat over WebSockets while the app is open.

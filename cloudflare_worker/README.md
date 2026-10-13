# Deploy without buying a domain

This Cloudflare Worker implements the same license API as `license_server/server.py`. Cloudflare D1 stores keys and activated phones, and Cloudflare provides an HTTPS `workers.dev` address. The Android app needs a **new signed APK** built with that address; an APK built with no license URL cannot be activated later.

## One-time setup

1. [Create a free Cloudflare account](https://dash.cloudflare.com/sign-up), then open PowerShell in `cloudflare_worker` on the build computer.
2. Run `npm ci` and `npx wrangler login`. The login command opens a browser; approve access to your Cloudflare account.
3. Run `npx wrangler d1 create message-auto-licenses --location weur`. Copy the database UUID from its output into `database_id` in [wrangler.toml](wrangler.toml), replacing the all-zero placeholder. If Wrangler inserted the binding automatically, keep only one `[[d1_databases]]` section with `binding = "DB"`.
4. Run `npx wrangler d1 migrations apply message-auto-licenses --remote` and confirm the migration.
5. Run `npx wrangler deploy`. If asked to create a `workers.dev` subdomain, accept. Copy the HTTPS URL printed by Wrangler, such as `https://message-auto-licenses.YOURNAME.workers.dev`.
6. Generate a private admin token with `python -c "import secrets; print(secrets.token_urlsafe(48))"`. Save it in a password manager. Run `npx wrangler secret put DMA_ADMIN_TOKEN` and paste the token when prompted. It must have at least 32 characters.
7. Open `YOUR_WORKER_URL/health` and check for `{"ok":true}`. Open `YOUR_WORKER_URL/admin`, enter the admin token, then generate a key for each person.

To build the distributable APK on Windows, return to the repository root and run:

```powershell
$env:DMA_LICENSE_URL = 'https://message-auto-licenses.YOURNAME.workers.dev'
powershell -ExecutionPolicy Bypass -File scripts/build-release.ps1
```

The signed APK will be in `artifacts/discord-message-auto-1.4.0.apk`. Give each person a different activation key. A key binds to its first activated phone. The admin page lists phones after activation and can revoke their keys. Back up the D1 database and the Android release signing directory; never commit the admin token or signing material.

The Worker and D1 have free-plan limits. Each active phone checks approximately every 30 seconds while watching. Revocation takes effect at the next check, or before a new session. See [Cloudflare Workers limits](https://developers.cloudflare.com/workers/platform/limits/) and [D1 pricing](https://developers.cloudflare.com/d1/platform/pricing/). For production use that cannot tolerate interruptions, review the platform limits and use a custom domain when available.

## Local verification

```powershell
npm ci
npx wrangler d1 migrations apply message-auto-licenses --local
# Create an untracked .dev.vars file containing DMA_ADMIN_TOKEN=<40+ random characters>
npx wrangler dev
```

In another terminal, from the repository root, set `DMA_TEST_ADMIN_TOKEN` to the local token and run `python -m unittest cloudflare_worker/test_integration.py -v`. This exercises key creation, signed Android-style activation, single-phone binding, replay rejection, status checks and revocation against local D1.

# License server

Each generated key binds to the first Android Keystore public key that activates it. Later checks are signed with that phone's private key. The server stores only a SHA-256 hash of each license key, the device public key, model, and activation/check timestamps. It receives no Discord message, channel, login, or token.

The admin page is at `https://YOUR_DOMAIN/admin`. Enter `DMA_ADMIN_TOKEN` to list activated phones, create a key, or revoke one. Copy each newly generated key immediately: the server does not store its plaintext. A revoked key fails the next license check (within about 30 seconds while monitoring). The client also checks before every new session. An offline or unavailable license server prevents starting or continuing a session.

An APK download cannot be detected by Android. The list shows **first activations**, not downloads or installs that never opened and activated the app. Reinstalling or clearing app data can remove the Keystore identity and prevent that key from working; issue a new key and revoke the old one. A modified APK or compromised phone can bypass client-side enforcement.

## Deploy when you have a domain and host

1. Point a DNS name to a server that can accept inbound ports 80 and 443. Install Docker Compose there.
2. In this directory, create an untracked `.env` with `LICENSE_DOMAIN=licenses.example.com` and `DMA_ADMIN_TOKEN=` followed by at least 32 random characters. Generate the token with `python -c "import secrets; print(secrets.token_urlsafe(48))"` and keep it private.
3. Run `docker compose up -d --build`. Caddy obtains and renews HTTPS certificates. Back up the `license_data` Docker volume regularly; losing it loses all issued keys and bindings.
4. Confirm `https://licenses.example.com/health` returns `{"ok":true}` and open `/admin` in your browser.
5. On the build computer set `DMA_LICENSE_URL=https://licenses.example.com` before running `scripts/build-release.ps1`. This URL is compiled into the APK. Rebuild and distribute the new signed APK if the domain changes.

Do not expose the Python service directly to the internet without the HTTPS proxy. Keep the `.env`, admin token, database, and release signing key outside Git. Debug APKs built with no URL skip licensing for local development only and use a separate package name; release APKs without a URL refuse to start monitoring.

Run API tests with `python -m pip install -r license_server/requirements.txt` and `python -m unittest discover -s license_server -v` from the repository root.

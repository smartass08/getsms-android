# Security policy

## Supported versions

This repository implements and supports protocol version `otp-relay/v1` (specified in `PROTOCOL.md`).

## Reporting a vulnerability

Do not open a public GitHub issue for security vulnerabilities.
Use GitHub's private vulnerability reporting feature on this repository, or contact the repository maintainers privately with:

- Affected component (`Server`, `TgBot`, `AndroidApp`, or `PROTOCOL.md`).
- Exact reproduction steps or proof of concept.
- Potential impact and any suggested mitigation.

## Security guarantees and boundaries

### What the system protects

- **Client-side encryption**: `AndroidApp` encrypts every SMS with AES-256-GCM (random 96-bit nonce, versioned AAD, 256-byte padded plaintext frame) before leaving the device or entering WorkManager's queue.
- **Zero-trust relay (`Server`)**: The server receives only a domain-separated bearer token derived via Argon2id + HKDF-SHA256, hashes it with SHA-256 into a mailbox key, stores ciphertext strictly in memory for 15 minutes, zeros evicted buffers, and emits zero application or access logs.
- **PIN vault (`TgBot`)**: Stores only the Argon2id-derived master key encrypted under `HKDF(Argon2id(pin, salt), VAULT_PEPPER, uid)` in SQLite (`0600`, `PRAGMA journal_mode=DELETE`, `PRAGMA synchronous=FULL`).
  Every PIN attempt commits `fails = fails + 1` before decryption and deletes the user's PIN record after 5 consecutive failures.
- **Access control (`TgBot`)**: Unwhitelisted users and non-private chats are silently dropped in memory (`O(1)`) without DB queries, Telegram replies, or Argon2id work.

### Residual risks and hardening checklist

- **Password entropy**: Because the key derivation uses a fixed protocol salt, always use the Android app's **Generate** button (120-bit random token) rather than a short passphrase.
- **Public relay DoS**: `Server` tracks no client IPs by design.
  Put a rate-limiting WAF or reverse proxy (such as Cloudflare WAF or nginx `limit_req` with `access_log off`) in front of public `/v1/messages` endpoints and block public access to `/healthz`.
- **Host isolation**: Running `TgBot` on a separate host from `Server` isolates plaintext decryption from the relay host.
  If you co-locate both services on the same machine, root access on that host can inspect `TgBot` memory while decrypting.
- **Host swap and upstream proxies**: Disable swap (or use encrypted swap) on the server host, and disable request logging on any reverse proxy or CDN.
- **Telegram transport**: Telegram receives decrypted SMS replies until auto-deletion (`REPLY_TTL_SECONDS`, default 120s) and briefly sees your `/refresh` command before the bot deletes it.
  Using `/setpin` reduces how often your master password crosses Telegram.

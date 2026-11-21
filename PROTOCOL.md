# OTP Relay protocol (`otp-relay/v1`)

This document defines the wire format, cryptographic primitives, and validation rules shared by `Server`, `TgBot`, and `AndroidApp`.

## 1. Input classification

Every secret entered by a user is classified by format alone after trimming leading and trailing ASCII whitespace (`0x20`, `0x09`, `0x0A`, `0x0D`):

- **PIN**: matches `^[0-9]{6,12}$` (6 to 12 ASCII digits).
- **Password**: length 16 to 128 bytes inclusive, where every byte is printable non-whitespace ASCII (`0x21` through `0x7E`).
- **Invalid**: anything else.

Because a PIN has at most 12 characters and a password has at least 16 characters, the two sets never overlap.
UTF-8 or Unicode normalization is not performed.

## 2. Key derivation

Given a valid password (encoded as its ASCII bytes):

```text
master       = Argon2id(
                 password = password_ascii,
                 salt     = "otp-relay/v1/argon2id/salt" (26 ASCII bytes),
                 t        = 3,
                 m        = 65536 KiB,
                 p        = 4,
                 version  = 0x13,
                 len      = 32
               )
access_token = HKDF-SHA256(ikm = master, salt = empty, info = "otp-relay/v1/access-token", L = 32)
enc_key      = HKDF-SHA256(ikm = master, salt = empty, info = "otp-relay/v1/sms-key",      L = 32)
bearer       = base64url_nopad(access_token)                       # 43 ASCII characters
mailbox_id   = SHA-256("otp-relay/v1/mailbox" || access_token)     # computed only by Server
```

An empty HKDF salt uses RFC 5869's default of `HashLen` (32) zero bytes.
Base64 encoding everywhere in this protocol uses RFC 4648 Section 5 (URL-safe alphabet `-` and `_`) without `=` padding.
Decoders must reject padding, whitespace, and non-canonical trailing bits.

## 3. Plaintext SMS frame (version 1)

| Offset | Size (bytes) | Field |
| --- | --- | --- |
| 0 | 1 | `version` (`0x01`) |
| 1 | 1 | `flags` (bit 0 = body truncated, bit 1 = test message; bits 2..7 must be `0`) |
| 2 | 8 | `received_at_ms` (unsigned 64-bit big-endian Unix timestamp in milliseconds) |
| 10 | 2 | `sender_len` `n` (unsigned 16-bit big-endian, `0 <= n <= 64`) |
| 12 | `n` | `sender` (UTF-8 bytes) |
| `12 + n` | 2 | `body_len` `m` (unsigned 16-bit big-endian) |
| `14 + n` | `m` | `body` (UTF-8 bytes) |
| `14 + n + m` | `padded_len - (14 + n + m)` | `0x00` padding bytes |

Rules:

- Maximum unpadded length: `L = 14 + n + m <= 4096`.
- Padded length: `padded_len = max(256, ceil(L / 256) * 256)`.
  Valid padded lengths are multiples of 256 between 256 and 4096 inclusive.
- UTF-8 truncation:
  - Truncate `sender` to at most 64 UTF-8 bytes without splitting a multi-byte UTF-8 sequence.
  - Truncate `body` to at most `4096 - 14 - n` UTF-8 bytes without splitting a multi-byte UTF-8 sequence.
  - To truncate a UTF-8 byte array `b` to `limit` bytes: if `len(b) <= limit`, keep all bytes.
    Otherwise start with `cut = limit` and decrement `cut` while `cut > 0` and `(b[cut] & 0xC0) == 0x80`.
  - Set flag bit 0 (`0x01`) if and only if `body` was truncated.
- Decoding rejects frames where:
  - `len(frame) < 256`, `len(frame) > 4096`, or `len(frame) % 256 != 0`.
  - `version != 0x01`.
  - `(flags & ~0x03) != 0`.
  - `sender_len > 64` or `14 + sender_len + body_len > len(frame)`.
  - Any trailing padding byte is nonzero.
  - `sender` or `body` is not valid UTF-8.

## 4. AEAD envelope

```text
aad  = "otp-relay/v1/sms" || 0x01                 # 17 bytes
ct   = AES-256-GCM(key = enc_key, nonce = random(12), plaintext = frame, aad = aad)
blob = 0x01 || nonce(12) || ct                    # ct = ciphertext || tag(16)
```

Blob shape rules enforced by `Server` without decrypting:

- `blob[0] == 0x01`
- `285 <= len(blob) <= 4125`
- `(len(blob) - 29) % 256 == 0`

Because `1 + 12 + 16 = 29`, valid blob byte lengths are `29 + 256 * k` for `1 <= k <= 16`.

## 5. PIN vault (`TgBot` only)

`TgBot` stores the 32-byte `master` key (never the raw password) per Telegram user ID `uid` (unsigned 64-bit big-endian):

```text
a   = Argon2id(
        password = pin_ascii,
        salt     = random(16),
        t        = 3,
        m        = 65536 KiB,
        p        = 4,
        version  = 0x13,
        len      = 32
      )
kek = HKDF-SHA256(
        ikm  = a,
        salt = VAULT_PEPPER (32 bytes from environment),
        info = "otp-relay/v1/pin-kek" || u64be(uid),
        L    = 32
      )
aad = "otp-relay/v1/pin-vault" || u64be(uid)
ct  = AES-256-GCM(key = kek, nonce = random(12), plaintext = master(32), aad = aad)
```

Vault storage uses a local SQLite database (`VAULT_PATH`, default `/data/vault.db`, file mode `0600`, `PRAGMA synchronous=FULL`):

```sql
CREATE TABLE IF NOT EXISTS pin_vault (
    user_id    INTEGER PRIMARY KEY,
    salt       BLOB NOT NULL,
    nonce      BLOB NOT NULL,
    ct         BLOB NOT NULL,
    fails      INTEGER NOT NULL DEFAULT 0,
    updated_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS allowed_users (
    user_id    INTEGER PRIMARY KEY,
    added_by   INTEGER NOT NULL,
    created_at INTEGER NOT NULL
);
```

Lockout rules:

- Before each decryption attempt, increment `fails` and commit the SQLite transaction durably.
- On decryption success, reset `fails` to `0` and commit.
- After 5 consecutive failed attempts, delete the user's `pin_vault` row and commit.

## 6. HTTP API (`Server`)

All responses include:

- `Cache-Control: no-store`
- `X-Content-Type-Options: nosniff`
- `Referrer-Policy: no-referrer`

Authentication header for `/v1/messages`:

- `Authorization: Bearer <bearer>` where `<bearer>` is exactly 43 characters and strictly decodes to 32 bytes.

Endpoints:

- `POST /v1/messages`
  - Requires `Content-Type: application/json` (parameters like `; charset=utf-8` allowed).
  - Body limit: 8192 bytes.
  - Request JSON: `{"blob": "<base64url>"}` (unknown fields rejected).
  - Returns `204 No Content` on success or idempotent duplicate (same SHA-256 blob digest in the same mailbox).
  - Errors: `400`, `401`, `413`, `415`, `429` (with `Retry-After`), `503` (with `Retry-After`).
- `GET /v1/messages`
  - Returns `200 OK` with `{"now": <ms>, "messages": [{"blob": "...", "received_at": <ms>, "expires_at": <ms>}]}` ordered newest first.
  - Returns an empty `messages` array for unknown or empty mailboxes without creating a mailbox.
- `DELETE /v1/messages`
  - Deletes all stored messages for the mailbox and returns `204 No Content`.
  - Returns `204 No Content` for unknown mailboxes without creating one.
- `GET /healthz`
  - Unauthenticated. Returns `200 OK` with `ok`.

Error response JSON:

```json
{"error": "bad_request|unauthorized|too_large|unsupported_media_type|rate_limited|capacity"}
```

## 7. Test vectors

Canonical test vectors are generated by `protocol/gen_vectors.py` into `protocol/test-vectors.json`.
All implementations must validate against `protocol/test-vectors.json`.

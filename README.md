# getsms-android

Zero-trust Android SMS forwarder written in Kotlin and Jetpack Compose for `getsms-server` and `getsms-tgbot`.
Encrypts incoming raw SMS messages on the device with AES-256-GCM before enqueuing network delivery, uses zero polling, and runs zero background services when idle.

## Related repositories

- **Server**: [`smartass08/getsms-server`](https://github.com/smartass08/getsms-server)
- **Telegram bot**: [`smartass08/getsms-tgbot`](https://github.com/smartass08/getsms-tgbot)
- **Android app (this repo)**: [`smartass08/getsms-android`](https://github.com/smartass08/getsms-android)

## Key properties

- **Two settings only**: **Server URL** (`https://`, with debug-only `http://` for emulator/localhost) and **Password/token** (with a 120-bit **Generate** button).
- **On-device encryption**: Derives `master` via Argon2id, wraps `master` directly in Android Keystore (AES-256-GCM, works while locked), encrypts each SMS into a 256-byte padded AES-256-GCM frame, and passes only ciphertext to WorkManager.
- **Zero battery drain**: Uses Android's static `Telephony.SMS_RECEIVED` broadcast receiver—no background polling, no alarms, no persistent services, and no held wakelocks.
- **Zero backups**: Disables Android cloud backup and device transfer (`allowBackup="false"`, `data_extraction_rules.xml`).

## Install from GitHub Releases

Download the signed release APK (`getsms-android-vX.Y.Z.apk`) from [GitHub Releases](https://github.com/smartass08/getsms-android/releases) and verify its build provenance:

```bash
gh attestation verify getsms-android-v1.0.2.apk --repo smartass08/getsms-android
```

### Bypassing "App blocked to protect your device" (Play Protect Enhanced Fraud Protection)

In select regions (including India, Singapore, Thailand, and Brazil), Google Play Protect automatically blocks installing any sideloaded APK that declares `RECEIVE_SMS` when opened from a web browser, messaging app, or file manager—even when properly signed and clean.

Use either of these installation methods:

- **Option A — Install on the phone (no PC required):**
  1. Open **Google Play Store -> Profile icon -> Play Protect -> Settings (gear)** and turn **Scan apps with Play Protect** off temporarily.
  2. Install `getsms-android-vX.Y.Z.apk` from your Downloads.
  3. Turn **Scan apps with Play Protect** back on immediately afterward.
  4. On Android 15 and later, if the **SMS** permission is greyed out, open **App info -> menu (⋮) -> Allow restricted settings**, authenticate, then grant **SMS** permission.
- **Option B — Install via `adb` (recommended for technical users):**
  ```bash
  adb install getsms-android-v1.0.2.apk
  ```
  `adb install` bypasses both Play Protect's sideload fraud block and Android 15's restricted-settings prompt (works over USB or Wireless Debugging).
- **Option C — Auto-updates via Obtainium + Shizuku:**
  Track this GitHub repository in [Obtainium](https://github.com/ImranR98/Obtainium) with its Shizuku installer enabled.

## Build locally

### Native build with Nix and `direnv`

```bash
direnv allow   # or: nix develop
./gradlew :protocol:test :app:lintDebug :app:assembleDebug
```

### Containerized build with Docker

```bash
docker build -t getsms-android .
cid=$(docker create getsms-android)
docker cp "$cid:/workspace/app/build/outputs/apk/debug/app-debug.apk" ./getsms-android-debug.apk
docker rm "$cid"
```

## Release signing (GitHub Actions)

The release workflow automatically builds and signs a release APK when these repository secrets are set under **Settings -> Secrets and variables -> Actions**:

- `ANDROID_KEYSTORE_BASE64`: Base64-encoded `.jks` / `.p12` keystore (`base64 -i release.jks | tr -d '\n'`)
- `KEYSTORE_PASSWORD`: Keystore password
- `KEY_ALIAS`: Signing key alias
- `KEY_PASSWORD`: Signing key password

If signing secrets are not set, the release workflow still publishes the signed debug APK with `SHA256SUMS.txt` and GitHub Sigstore build provenance attestations.

## License

MIT — see `LICENSE`.

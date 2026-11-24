# Twin Star S

On-device notes for Android. Every note is a book: swipe through pages, tap the middle to edit.
Linked devices sync shared notes within seconds, with no server of your own.

## Build & release
Push to `main`. GitHub Actions builds the APK and publishes a release tagged `vX.Y.Z`
(version comes from `appVersion` in `app/build.gradle.kts`).

### Signing (needed so updates install over each other)
Add these repository secrets:

| Secret | Value |
| --- | --- |
| `KEYSTORE_BASE64` | your `.jks` file, base64-encoded |
| `KEYSTORE_PASSWORD` | keystore password |
| `KEY_ALIAS` | key alias |
| `KEY_PASSWORD` | key password |

Without them the build still works but is signed with a throwaway debug key that changes every build.

## How sync works
- **Pairing:** one device shows a QR code holding a fresh random 256-bit key; the other scans it.
  Only those two devices ever hold that key. Each link has its own key.
- **Same Wi-Fi:** devices find each other by UDP broadcast and send encrypted frames directly over TCP.
- **Internet:** encrypted messages go through public Nostr relays (editable in CONFIG → SYNC) on an
  unguessable topic. Relays only see ciphertext and do not store it (ephemeral events).
- **Conflicts:** newest edit wins; the replaced version is moved to the trash. Trash empties after 30 days.
- **Apart for a while:** when two devices reconnect with differences, you're asked before catching up
  (can be turned off).

No Google services are used.

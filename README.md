# Twin Star S

Android app made with **Stellar AutoHub**.

- Every push builds an APK (any branch). Pushes to `main` also publish a release tagged `vX.Y.Z` from `versionName`.
- Signing uses the repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`.
  Without them the build uses a temporary debug key.
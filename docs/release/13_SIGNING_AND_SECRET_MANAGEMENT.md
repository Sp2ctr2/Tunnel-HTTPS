# Signing and secret management

Release signing reads four environment variables: `TUNNELHTTPS_STORE_FILE`, `TUNNELHTTPS_STORE_PASSWORD`, `TUNNELHTTPS_KEY_ALIAS`, and `TUNNELHTTPS_KEY_PASSWORD`. Partial configuration and unreadable keystore paths fail the Gradle configuration. No password, token, private key, or keystore was found in source inventory.

Current release APK and AAB are unsigned because no values were supplied. They are not upload candidates.

Manual procedure:

1. Confirm Play App Signing enrollment and existing application ID ownership.
2. Use the existing upload key if one exists; do not replace it.
3. Store the keystore outside the repository with offline encrypted backups and documented recovery ownership.
4. Inject all four values through a secure local/CI secret store.
5. Run `bundleRelease`, verify the AAB certificate/fingerprint, upload only to internal testing, and record Play’s app-signing certificate separately.
6. Never print passwords or private-key material. Record only public certificate fingerprints and validity dates.

Owner/account/key facts remain `REQUIRES OWNER INPUT`.

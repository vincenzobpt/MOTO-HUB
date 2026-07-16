# Public Release Process

MOTO-HUB keeps Android Auto identity files and the APK-signing keystore out of Git history. The official GitHub release workflow provisions those inputs from repository secrets, validates them, builds and tests the application, signs the APK, and attaches the verified result to a GitHub release.

## User Experience

Users download the APK attached to the latest GitHub release and install it directly. The release APK includes Android Auto support and does not require users to import certificates, configure developer tools, or understand the identity mechanism.

The expected release asset name is:

```text
MOTO-HUB-<versionName>-<versionCode>-android-auto.apk
```

Every release also contains a matching `.sha256` checksum file.

## Source And Binary Separation

The public repository excludes:

- `aa_cert`;
- `aa_identity_data`;
- the APK-signing keystore;
- passwords used to access the signing keystore;
- the local `tooling/private/` directory;
- locally generated APKs under `artifacts/`.

The release APK necessarily contains the runtime Android Auto identity. Keeping its source files in GitHub Actions secrets prevents accidental inclusion in source history or workflow logs, but it does not make material packaged inside a public APK confidential.

## Required GitHub Actions Secrets

Configure these repository-level Actions secrets before pushing a release tag:

| Secret | Content |
|---|---|
| `ANDROID_AUTO_CERT_B64` | Base64 encoding of `aa_cert` |
| `ANDROID_AUTO_IDENTITY_B64` | Base64 encoding of `aa_identity_data` |
| `MOTOHUB_KEYSTORE_B64` | Base64 encoding of the APK-signing JKS file |
| `MOTOHUB_KEYSTORE_PASSWORD` | Signing keystore password |
| `MOTOHUB_KEY_ALIAS` | Signing key alias |

The current JKS uses the keystore password for its signing-key entry as well. GitHub does not expose secret values after they are stored. Rotate the APK-signing key only as a deliberate migration: Android will reject an update signed by a different key unless a supported signing-key rotation process is used.

## Workflow Gate

The workflow in `.github/workflows/release-android.yml` runs only for tags matching `v*` and performs the following checks:

1. Validates that all required secrets exist.
2. Reconstructs build inputs on the ephemeral runner.
3. Confirms that the Android Auto certificate and private key are a matching pair.
4. Installs Android SDK platform 36 and Build Tools 36.0.0.
5. Runs unit tests, release lint, and a clean release build.
6. Confirms that both Android Auto raw resources are packaged.
7. Requires the Git tag version to match the Android `versionName`.
8. Aligns and signs the APK with the persistent MOTO-HUB signing key.
9. Verifies APK alignment, signature, version code, and version name.
10. Publishes the APK and SHA-256 checksum as GitHub release assets.
11. Removes reconstructed private material even when an earlier step fails.

The release is not created when any gate fails.

## Creating A Release

1. Update `versionName` and increment `versionCode` in `apps/android/app/build.gradle.kts`.
2. Build and test the exact candidate on real motorcycle hardware.
3. Commit and push the validated source to `main`.
4. Create and push an annotated tag matching `versionName`, for example `v0.8.1`.
5. Monitor the `Release Android APK` workflow until it completes.
6. Download the published APK and verify its checksum and signing certificate before announcing the release.

Do not create or move the release tag before the hardware test is complete.

## Local Builds

For a local Android Auto build, place `aa_cert` and `aa_identity_data` under `tooling/private/android-auto/` and run from `apps/android/`:

```bash
./gradlew -PincludeAndroidAutoIdentity=true testDebugUnitTest lintRelease assembleRelease
```

A default build without `-PincludeAndroidAutoIdentity=true` excludes the identity. Pairing, T-Box streaming, mirroring, and diagnostics remain available, while Android Auto reports that its identity is unavailable.

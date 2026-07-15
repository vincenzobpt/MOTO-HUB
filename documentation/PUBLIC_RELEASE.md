# Public Release Policy

MOTO-HUB is published with a deliberate split between the public source release and the private Android Auto build.

## Public Repository

The public repository excludes:

- `aa_cert`;
- `aa_identity_data`;
- the private `tooling/private/android-auto/` build-input directory;
- any APK that embeds those files.

The public source builds without the Android Auto identity. Pairing, T-Box streaming, mirroring, diagnostics, and the rest of the application remain available. Android Auto reports a clear unavailable-identity error instead of failing later during the TLS handshake.

## Private Android Auto Build

For personal sideloading only, provide `aa_cert` and `aa_identity_data` under `tooling/private/android-auto/` and run:

```bash
./gradlew -PincludeAndroidAutoIdentity=true exportPrivateAndroidAutoApk
```

The APK is exported with the deterministic name `MOTO-HUB-0.8.0-37-android-auto-private.apk` under `artifacts/`. This APK contains the static Android Auto identity and must not be uploaded to a public GitHub release.

The public preview APK is generated without the identity:

```bash
./gradlew exportPublicApk
```

It is named `MOTO-HUB-0.8.0-37-public.apk` and supports the non-Android-Auto features.

The identity files originate from the Android Auto head-unit integration used as a technical reference. They are not user credentials, T-Box passwords, or data extracted from a phone.

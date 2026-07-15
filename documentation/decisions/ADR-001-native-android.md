# ADR-001 - Native Kotlin/Compose Android App

Status: Proposed
Date: 2026-07-14

## Context

The product directly depends on `MediaProjection`, `VirtualDisplay`,
`MediaCodec`, a foreground service, local-only Wi-Fi, NSD and an AAR generated
with `gomobile`. The working reference is already Kotlin/Compose.

## Decision

Build the app natively in Kotlin with Compose. Use direct Android APIs for the
critical pipeline and limit wrappers to the ridedaemon boundary.

## Consequences

- Complete access to lifecycle, network and codecs.
- Lower risk of video copies or plugin mismatch in a multiplatform stack.
- The initial product is Android-only.
- Specific expertise in Android services and concurrency is required.

## Alternatives Considered

- Flutter: possible for the UI, but it would still require native plugins for
  nearly the entire critical pipeline.
- React Native: the same native-boundary and lifecycle problem.

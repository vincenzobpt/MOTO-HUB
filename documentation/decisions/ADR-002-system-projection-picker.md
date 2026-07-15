# ADR-002 - Android System Projection Picker

Status: Proposed
Date: 2026-07-14

## Context

Android requires explicit consent for `MediaProjection`. Android 14 QPR2+
allows the user to choose a single app or the entire display in the system
picker. A custom app list does not grant permission to capture those apps.

## Decision

The primary command is `Share app or screen` and opens the system picker. HUB
will not implement an MVP launcher that promises direct selection of installed
apps.

## Consequences

- UX is consistent with Android privacy and platform constraints.
- Single-app sharing exists only on releases that support it.
- The system, not HUB, controls the final label and selection.
- Every new session requires new consent.

## Alternatives Considered

- Custom list plus app launch: does not replace consent and creates ambiguous UX.
- Accessibility service or special privileges: disproportionate, fragile and
  unnecessary for the product.

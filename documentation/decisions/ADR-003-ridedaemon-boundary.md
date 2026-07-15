# ADR-003 - Ridedaemon Isolated As Transport

Status: Proposed
Date: 2026-07-14

## Context

`ridedaemon-lib` implements protocol, framing and T-Box session behavior.
Android must handle consent, network, codec and UI. Exposing `MobileSession`
directly to every feature would couple the entire app to gomobile callbacks and
Go types.

## Decision

Embed the AAR behind a Kotlin `TBoxTransport` interface and one
`RideDaemonAdapter`. Keep the Go fork close to upstream and change the boundary
only when a hardware need is demonstrated.

## Consequences

- The transport is replaceable and testable with fakes.
- Go callbacks do not leak into ViewModels and UI.
- AAR build and version must be reproducible.
- Android networking follows the reference controller: Wi-Fi suggestion,
  active-SSID confirmation and then the standard Go session.

## Alternatives Considered

- Copy the protocol into Kotlin: high risk and duplication with no MVP benefit.
- Use AAR types directly everywhere: fast initially, expensive for tests and
  lifecycle management.

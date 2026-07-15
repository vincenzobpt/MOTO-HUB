# ADR-004 - Direct Surface Before EGL Compositor

Status: Proposed
Date: 2026-07-14

## Context

`MediaProjection` can render a virtual display directly into the `MediaCodec`
input surface. An EGL compositor would provide more control, but introduces an
intermediate texture, synchronization, shaders, power use and new failure modes.

## Decision

Implement the direct pipeline at T-Box resolution first. Introduce EGL only if
hardware tests show unresolved problems with aspect ratio, rotation,
letterboxing or overlays.

## Consequences

- Smaller spike and MVP with lower latency.
- Limited control over image transformation.
- Resize and rotation must be tested early.
- The architecture keeps a `ProjectionCapture` boundary that allows the
  pipeline to be replaced later.

## Alternatives Considered

- Immediate EGL: flexible but premature without evidence.
- `ImageReader` capture and CPU conversion: copies and power use are unsuitable
  for continuous 15-30 fps.

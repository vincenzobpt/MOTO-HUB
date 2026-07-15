# Architecture Decision Records

ADRs record decisions that are difficult to reverse or that change the
boundary between Android, the encoder and `ridedaemon-lib`.

Format:

```text
# ADR-NNN - Title
Status: Proposed | Accepted | Superseded
Date: YYYY-MM-DD

## Context
## Decision
## Consequences
## Alternatives Considered
```

Index:

- [ADR-001](ADR-001-native-android.md): native Kotlin/Compose Android app.
- [ADR-002](ADR-002-system-projection-picker.md): source selection through
  the system `MediaProjection` picker.
- [ADR-003](ADR-003-ridedaemon-boundary.md): keep ridedaemon isolated as a
  transport adapter.
- [ADR-004](ADR-004-direct-surface-first.md): direct surface in the MVP, EGL
  only after hardware evidence.

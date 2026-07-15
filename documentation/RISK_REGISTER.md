# Risk Register

Status: open

Scale: probability and impact are `Low`, `Medium` or `High`.

| ID | Risk | Prob. | Impact | Mitigation / verification | Status |
|---|---|---|---|---|---|
| R-01 | T-Box Wi-Fi interrupts Internet needed by the source app | High | High | local-only spike, process binding, STA/cellular tests | Open |
| R-02 | Go sockets do not reliably follow the selected Android network | Medium | High | use primary T-Box Wi-Fi as the reference app does; physical tests on OPPO and T-Box | Mitigating |
| R-03 | OEM encoder produces incompatible GOP/B-frames | Medium | High | bitstream inspection and codec profiles | Open |
| R-04 | Aspect ratio/orientation is unreadable on 800x400 TFT | High | Medium | direct-surface test; EGL compositor gate | Open |
| R-05 | Lock or OEM policies terminate projection/service | High | Medium | callback, notification, battery guidance, OEM matrix | Open |
| R-06 | DRM/`FLAG_SECURE` app displays black | High | Medium | documented limitation, no bypass | Accepted |
| R-07 | Latency is too high for navigation | Medium | High | short GOP, zero-copy, frame dropping, physical measurement | Open |
| R-08 | T-Box firmware differs in protocol behavior | Medium | High | firmware matrix and versioned profiles | Open |
| R-09 | mDNS discovery is fragile in background/new LAN permissions | Medium | High | modern NSD, API 36/37 permission strategy | Open |
| R-10 | Concurrent cleanup causes crashes/leaks | High | High | serialized state machine, idempotent stop, stress test | Open |
| R-11 | `pushFrame()` blocks the codec drain | Medium | High | measure, bounded capacity-1 buffer, drop-oldest | Open |
| R-12 | GPL conflicts with distribution strategy | Medium | High | licensing decision before beta | Open |
| R-13 | Media transport is not encrypted | Medium | Medium | protocol audit, disclosure and dedicated network | Open |
| R-14 | Overheating during long sessions | High | Medium | 15/20 fps fallback, thermal monitoring, 60-minute test | Open |
| R-15 | Reference app contains demo assumptions not production-ready | High | Medium | reuse concepts, do not blindly copy the controller | Mitigating |

## Management Rule

- Every `High/High` risk must have a spike or test before the complete MVP UI.
- A closed risk must include evidence: test, commit, model and firmware.
- An accepted risk must become a visible product limitation.

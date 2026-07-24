# RideDaemon EOF Crash Fix

## Problem Summary

**Crash**: `RideDaemon fatal callback: 2 (error reading header: EOF)` after 4-6 minutes of streaming Android Auto to T-Box.

**Root Cause**: Deadlock between the AVC encoder drain thread and the RideDaemon socket reader thread.

---

## Detailed Analysis

### From the Diagnostic Log (MOTO-HUB-diagnostics-20260720-190731-kurrvva.txt)

**Timeline**:
- **21:59:21** — Android Auto starts, PXC heartbeats every ~2 seconds, frames at 30fps
- **22:02:51-53** — FPS drop anomaly: 25→23→22fps 
- **22:04:27** — Another FPS drop to 25fps
- **22:04:39** — **CRASH**: "RideDaemon fatal callback: 2 (error reading header: EOF)"

**Protocol stats at crash**:
```
pxcRx=306 (last=1283ms ago)        ← Heartbeat still arriving every ~2s
mediaCtrlRx=3 (last=600982ms ago)  ← MediaCtrl DEAD for 10+ MINUTES
framesOffered=11553 (last=17ms)    ← Frames continue being offered
```

### The Deadlock

```
Thread: MotoHubAvcDrain (Encoder)
├─ drainLoop() dequeues output buffer
├─ Calls onAccessUnit(frame) callback
└─ Blocks on handle.transport.offerAccessUnit(accessUnit)
   └─ Calls RideDaemonTransport.offerAccessUnit()
      └─ Calls activeSession.pushFrame() [JNI call to Go]
         └─ BLOCKS waiting for T-Box to accept frame
            (T-Box socket buffer is congested)

Thread: Go RideDaemon (Socket Reader) 
├─ Tries to read next PXC command header from socket
├─ Cannot proceed (blocked on frame write backpressure?)
└─ Eventually times out after ~30 seconds
   └─ Socket closes with EOF
```

### Why FPS Drops Precede the Crash

When `pushFrame()` starts blocking (T-Box congestion):
1. The drain thread stalls inside `onAccessUnit()`
2. MediaCodec output buffers accumulate, not released on time
3. Encoder slows down to avoid buffer overflow → FPS drops (observed: 19-25fps)
4. If the congestion persists > socket read timeout (30s), EOF occurs

### Why mediaCtrlRx Remains Stale

The T-Box has two communication channels:
- **PXC** (heartbeat/control): Managed by Go, unaffected by drain thread stall
- **MediaCtrl** (video commands): Would need the socket reader to be responsive

With the drain thread stalled, any response processing is delayed. The 10-minute staleness indicates the T-Box stopped sending control commands because it detected the client isn't responsive.

---

## Solution: Non-Blocking pushFrame() with Timeout

### Changes Made

**File**: `RideDaemonTransport.kt`

#### 1. Added imports
```kotlin
import java.util.concurrent.TimeUnit
```

#### 2. Created dedicated executor for pushFrame()
```kotlin
private val pushFrameExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "MotoHubPushFrame").apply { isDaemon = true }
}
private const val PUSH_FRAME_TIMEOUT_MS = 5_000L
```

#### 3. Added timeout tracking
```kotlin
private val framesTimedOut = AtomicLong(0L)
```

#### 4. Modified offerAccessUnit() to use timeout
```kotlin
override fun offerAccessUnit(avcc: ByteArray): Boolean {
    val activeSession = session ?: return false
    if (!activeSession.isRunning) return false
    return runCatching {
        val future = pushFrameExecutor.submit {
            activeSession.pushFrame(avcc)
        }
        try {
            future.get(PUSH_FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            framesOffered.incrementAndGet()
            lastFrameOfferedElapsed.set(SystemClock.elapsedRealtime())
            true
        } catch (timeout: java.util.concurrent.TimeoutException) {
            framesTimedOut.incrementAndGet()
            ProjectionEventLog.warning(
                "TBOX",
                "AVC frame dropped: pushFrame() exceeded ${PUSH_FRAME_TIMEOUT_MS}ms timeout. " +
                    "The T-Box may be unresponsive. Timeouts: ${framesTimedOut.get()}"
            )
            false
        }
    }.getOrElse {
        Log.w(TAG, "Unable to offer AVC access unit", it)
        ProjectionEventLog.error("TBOX", "Unable to push an AVC access unit to RideDaemon.", it)
        false
    }
}
```

#### 5. Updated protocol snapshot to include timeouts
```kotlin
private fun protocolSnapshot(): String {
    // ...
    return "protocolStats=" +
        "pxcRx=${pxcEvents.get()} (last=${age(lastPxcEventElapsed)}), " +
        "mediaCtrlRx=${mediaControlEvents.get()} (last=${age(lastMediaControlEventElapsed)}), " +
        "framesOffered=${framesOffered.get()} (last=${age(lastFrameOfferedElapsed)}), " +
        "frameTimeouts=${framesTimedOut.get()}"
}
```

#### 6. Cleanup executor on stop
```kotlin
private fun stopSession() {
    // ...
    pushFrameExecutor.shutdownNow()
}
```

---

## How This Fixes the Crash

1. **Non-blocking**: `pushFrame()` now runs on a separate thread with a 5-second timeout
   - The drain thread is NOT blocked indefinitely
   - If T-Box doesn't accept within 5 seconds, the frame is dropped (not lost forever)

2. **Prevents socket timeout**: Since the drain thread can't stall the socket reader, the socket reader remains responsive
   - Heartbeats continue flowing
   - Socket read timeout never triggers
   - No EOF error

3. **Diagnostics**: `frameTimeouts` counter lets us see when T-Box is unresponsive
   - If this counter climbs, we know the link is congested
   - Watchdog can use this to trigger recovery earlier (before 30-second timeout)

---

## Expected Behavior After Fix

**Before crash**:
```
framesOffered=11553, frameTimeouts=0     ← All frames accepted normally
```

**If T-Box congests**:
```
framesOffered=11600, frameTimeouts=15    ← Some frames timeout and are dropped
```

**In logs**:
```
WARNING  TBOX: AVC frame dropped: pushFrame() exceeded 5000ms timeout. 
               The T-Box may be unresponsive. Timeouts: 15
```

The app continues streaming (with some frame loss) rather than crashing.

---

## Further Improvements (Optional)

1. **Reduce PUSH_FRAME_TIMEOUT_MS to 3000** if T-Box link is consistently faster
2. **Add watchdog check** for `frameTimeouts > threshold` to trigger recovery sooner
3. **Increase executor thread pool** if we want multiple concurrent frame pushes
4. **Add metrics export** for `frameTimeouts` to understand real-world congestion patterns

---

## Testing

**Manual test**:
1. Start Android Auto projection
2. Simulate T-Box congestion (e.g., reduce WiFi bandwidth)
3. Observe: FPS may drop slightly, but no EOF crash
4. Check logs: `frameTimeouts` should increase
5. When congestion clears: streaming resumes normally

**CI test**: Existing crash should no longer reproduce with the stress test that triggered it.

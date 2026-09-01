// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.tbox

import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Filters duplicate contacts from motorcycle digitizers and stitches a short UP/DOWN gap back
 * into the same drag. Some 800NK panels report one physical finger twice or briefly release it
 * while scanning; forwarding those frames makes Android Auto see a broken gesture.
 */
class TBoxTouchFilter(
    private val log: (String) -> Unit,
    private val downstream: (TBoxEvent.Touch) -> Unit,
    private val policy: TBoxTouchPolicy = TBoxTouchPolicy()
) : AutoCloseable {
    private data class Contact(val x: Int, val y: Int, val at: Long)
    private data class PendingUp(val pointerId: Int, val x: Int, val y: Int, val at: Long)

    private val executor: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { runnable ->
            Thread(runnable, "motohub-touch-filter").apply { isDaemon = true }
        }
    private val contacts = LinkedHashMap<Int, Contact>()
    private var pendingUp: PendingUp? = null
    private var pendingTask: ScheduledFuture<*>? = null
    private var ghostsDropped = 0
    private var stitches = 0
    private var lastMoveLogAt = 0L

    @Synchronized
    fun onTouch(event: TBoxEvent.Touch) {
        val now = System.currentTimeMillis()
        removeStaleContacts(now, event.pointerId)

        when (event.action) {
            ACTION_UP -> {
                if (!contacts.containsKey(event.pointerId)) return
                holdUp(event, now)
                return
            }
            ACTION_DOWN -> {
                val pending = pendingUp
                if (pending != null &&
                    now - pending.at <= policy.stitchMillis &&
                    near(pending.x, pending.y, event.x, event.y, policy.stitchDistancePx)
                ) {
                    cancelPendingUp()
                    contacts.remove(pending.pointerId)
                    contacts[pending.pointerId] = Contact(event.x, event.y, now)
                    stitches++
                    if (stitches == 1 || stitches % 20 == 0) {
                        log("[TOUCH] stitched digitizer contact #$stitches")
                    }
                    downstream(
                        event.copy(action = ACTION_MOVE, pointerId = pending.pointerId)
                    )
                    return
                }
                commitPendingUp()
                if (isGhost(event)) return
                evictOldestIfNeeded(event.pointerId)
            }
            ACTION_MOVE -> if (isGhost(event)) {
                ghostsDropped++
                if (ghostsDropped == 1 || ghostsDropped % 20 == 0) {
                    log("[TOUCH] dropped duplicate digitizer contact #$ghostsDropped")
                }
                return
            }
            else -> return
        }

        contacts[event.pointerId] = Contact(event.x, event.y, now)
        if (event.action == ACTION_MOVE) logMoveThrottled(event, now)
        downstream(event)
    }

    @Synchronized
    override fun close() {
        commitPendingUp()
        executor.shutdownNow()
    }

    private fun isGhost(event: TBoxEvent.Touch): Boolean =
        contacts.any { (pointerId, contact) ->
            pointerId != event.pointerId &&
                near(contact.x, contact.y, event.x, event.y, policy.ghostMergePx)
        }

    /** Keep the AAP pointer set bounded, mirroring the motorcycle digitizer contract. */
    private fun evictOldestIfNeeded(incomingPointerId: Int) {
        while (contacts.size >= policy.maxPointers && incomingPointerId !in contacts) {
            val oldest = contacts.entries
                .filter { it.key != incomingPointerId }
                .minByOrNull { it.value.at }
                ?: return
            contacts.remove(oldest.key)
            downstream(
                TBoxEvent.Touch(
                    action = ACTION_UP,
                    pointerId = oldest.key,
                    x = oldest.value.x,
                    y = oldest.value.y
                )
            )
            log("[TOUCH] evicted stale pointer ${oldest.key}; max=${policy.maxPointers}")
        }
    }

    /**
     * Releases fingers whose UP frame was lost. [activePointerId] is exempt: it is the contact the
     * frame being processed belongs to, and a dash that samples a slow drag sparsely - or a link
     * that hiccups - routinely leaves more than [TBoxTouchPolicy.staleContactMillis] between two of
     * its own MOVEs. Releasing it here injected an UP into the middle of the gesture; Android Auto
     * then promoted the next MOVE back to a DOWN, so every drag reached it as a string of taps.
     * Tapping an icon worked, scrolling the Android Auto task bar and pinching never did. The
     * OpenCfMoto reference excludes the active pointer for exactly this reason.
     */
    private fun removeStaleContacts(now: Long, activePointerId: Int) {
        val stale = contacts.entries
            .filter { it.key != activePointerId && now - it.value.at > policy.staleContactMillis }
            .toList()
        stale.forEach { (pointerId, contact) ->
            contacts.remove(pointerId)
            downstream(
                TBoxEvent.Touch(
                    action = ACTION_UP,
                    pointerId = pointerId,
                    x = contact.x,
                    y = contact.y
                )
            )
            log("[TOUCH] synthesized UP for stale pointer $pointerId")
        }
    }

    private fun holdUp(event: TBoxEvent.Touch, now: Long) {
        cancelPendingUp()
        pendingUp = PendingUp(event.pointerId, event.x, event.y, now)
        pendingTask = executor.schedule({
            synchronized(this) {
                val pending = pendingUp
                if (pending?.pointerId == event.pointerId) {
                    pendingUp = null
                    contacts.remove(event.pointerId)
                    downstream(event)
                }
            }
        }, policy.stitchMillis, TimeUnit.MILLISECONDS)
    }

    private fun commitPendingUp() {
        val pending = pendingUp ?: return
        cancelPendingUp()
        contacts.remove(pending.pointerId)
        downstream(
            TBoxEvent.Touch(
                action = ACTION_UP,
                pointerId = pending.pointerId,
                x = pending.x,
                y = pending.y
            )
        )
    }

    private fun cancelPendingUp() {
        pendingTask?.cancel(false)
        pendingTask = null
        pendingUp = null
    }

    /**
     * A drag is the one gesture whose frames are never logged individually - a single swipe is
     * dozens of them. With no trace at all, a rider log cannot say whether a scroll failed because
     * the dash sent nothing or because we dropped it, which is precisely the question a "touch does
     * not scroll" report asks. One line every [MOVE_LOG_INTERVAL_MS] answers it without flooding.
     */
    private fun logMoveThrottled(event: TBoxEvent.Touch, now: Long) {
        if (now - lastMoveLogAt < MOVE_LOG_INTERVAL_MS) return
        lastMoveLogAt = now
        log("[TOUCH] move p${event.pointerId} (${event.x},${event.y}); ${contacts.size} down")
    }

    private fun near(x1: Int, y1: Int, x2: Int, y2: Int, distance: Int): Boolean =
        kotlin.math.abs(x1 - x2) <= distance && kotlin.math.abs(y1 - y2) <= distance

    private companion object {
        const val ACTION_DOWN = 0
        const val ACTION_UP = 1
        const val ACTION_MOVE = 2
        const val MOVE_LOG_INTERVAL_MS = 400L
    }
}

// SPDX-License-Identifier: AGPL-3.0-only
// Copyright (C) 2026 Vincenzo Buonomano and the MOTO-HUB contributors.
// Part of MOTO-HUB. Free software under the GNU AGPL v3; see LICENSE.
package io.motohub.android.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier

/**
 * Every full-screen destination MainActivity can put on screen, across both editions.
 *
 * One shared enum rather than one per flavor: the transition needs a stable identity to animate
 * between, and the two MainActivities are hand-maintained twins - a single list keeps their
 * screen keys from drifting apart the way everything else about them tries to. CORE simply
 * never produces the PRO-only values.
 */
enum class HubScreenKey {
    HOME,
    APPLICATION_LOGS,
    ANDROID_AUTO_HELP,
    ADVANCED_PROMO,
    ABOUT,
    ANDROID_AUTO_PREVIEW,
    RIDE_DASHBOARD_PREVIEW,
    CONTROLS,
    OBD,
    CAPABILITIES,
    MOTORCYCLE_DETAILS,
    DASHBOARD_CUSTOMIZATION,
    WIDGET_PICKER,
    DASHBOARD_SETTINGS,
    GROUP_INTERCOM,
    NETWORK_DIAGNOSTICS,
    CLOCK_LAB,
    BLE_EXPLORER,
    QR_SCANNER,
    MANUAL_PAIRING
}

/**
 * Slides between the hub's full-screen destinations instead of cutting between them.
 *
 * Thin wrapper over [ScreenSlideTransition]: HOME is the floor everything else covers. See that
 * function for the transition itself - this is the one call site every screen this app has ever
 * shown started from, so it earns keeping its own name rather than being spelled out everywhere.
 */
@Composable
fun HubScreenTransition(
    screen: HubScreenKey,
    modifier: Modifier = Modifier,
    content: @Composable (HubScreenKey) -> Unit
) {
    ScreenSlideTransition(
        screen = screen,
        isBase = { it == HubScreenKey.HOME },
        modifier = modifier,
        label = "hub-screen",
        content = content
    )
}

/**
 * Slides between destinations of a single screen instead of cutting between them, wherever the
 * app swaps one full piece of content for another based on some state - a tab's dispatcher, a
 * list-then-detail drill-down, a fullscreen toggle. One generic implementation rather than one
 * per feature: every one of these was the same `if`/`when` MainActivity's screen chain was
 * before it got this treatment, and the fix is the same shape everywhere it appears.
 *
 * The model is the one the rest of Android has trained every thumb on: [isBase] names the floor
 * - the list, the collapsed state, the tab's resting screen - and everything else is a card that
 * slides in over it from the right, full-width, while the base retreats a quarter of its width
 * behind it. That parallax is what reads as one surface *covering* another rather than two
 * surfaces racing past each other. Leaving a non-base state for another non-base state (a detail
 * screen replaced by a different detail screen, not by the list) still counts as forward - there
 * is no base to return to, so the card that was on top simply hands off to the next one the same
 * way it arrived.
 *
 * Direction is decided once, from [isBase] on the target alone: toward the base is back, away
 * from it is forward. That is exactly right for how every one of these dispatchers actually
 * behaves - a single active card over a resting floor, never two cards deep - and it is why one
 * predicate is enough; no caller has to track where it came from.
 *
 * [T] must be a stable, comparable identity ([AnimatedContent] keys on structural equality) -
 * an enum, a sealed interface, or a nullable id are all fine, including null itself as the base
 * state; a fresh object built on every recomposition is not, or every recomposition would look
 * like a navigation.
 *
 * **Every screen keeps its place.** A destination that slides away leaves composition, and with
 * it went every `rememberSaveable` it held - which is what a scroll state is. That is why going
 * into a detail and coming back used to land the rider at the top of a list they had scrolled
 * half way down, everywhere in this app at once. The [rememberSaveableStateHolder] below keeps
 * each screen's saved state alive while it is off stage and hands it back when it returns, so
 * `rememberScrollState()` / `rememberLazyListState()` restore themselves with no work at the
 * call sites. [stateKey] names the drawer each screen's state is filed under; the default is the
 * screen's own `toString()`, which is exactly right for the enums, data objects and data classes
 * every caller here uses, and is why the identity requirement above is not merely about
 * animation any more.
 */
@Composable
fun <T> ScreenSlideTransition(
    screen: T,
    isBase: (T) -> Boolean,
    modifier: Modifier = Modifier,
    label: String = "screen-slide",
    stateKey: (T) -> Any = { "$it" },
    content: @Composable (T) -> Unit
) {
    val savedScreenState = rememberSaveableStateHolder()
    AnimatedContent(
        targetState = screen,
        modifier = modifier,
        transitionSpec = { screenSlideTransform(isBase) },
        label = label
    ) { shown ->
        savedScreenState.SaveableStateProvider(stateKey(shown)) {
            content(shown)
        }
    }
}

/**
 * A crossfade between screens that keeps each one's place.
 *
 * The same saved-state contract as [ScreenSlideTransition], for the dispatchers where sliding
 * would be wrong - the bottom tabs, which are siblings rather than a stack, and where a card
 * covering another would say something untrue about how they relate.
 *
 * Without the holder each tab was rebuilt from scratch on every switch: a rider who scrolled half
 * way down one tab, glanced at another and came back landed at the top again.
 */
@Composable
fun <T> ScreenCrossfade(
    screen: T,
    modifier: Modifier = Modifier,
    label: String = "screen-crossfade",
    stateKey: (T) -> Any = { "$it" },
    content: @Composable (T) -> Unit
) {
    val savedScreenState = rememberSaveableStateHolder()
    Crossfade(targetState = screen, modifier = modifier, label = label) { shown ->
        savedScreenState.SaveableStateProvider(stateKey(shown)) {
            content(shown)
        }
    }
}

private fun <T> AnimatedContentTransitionScope<T>.screenSlideTransform(
    isBase: (T) -> Boolean
): ContentTransform {
    val forward = !isBase(targetState)
    val spec = tween<Float>(TRANSITION_MILLIS, easing = FastOutSlowInEasing)
    val slide = tween<androidx.compose.ui.unit.IntOffset>(TRANSITION_MILLIS, easing = FastOutSlowInEasing)
    return if (forward) {
        (slideInHorizontally(slide) { width -> width } togetherWith
            slideOutHorizontally(slide) { width -> -width / PARALLAX_FRACTION } + fadeOut(spec, targetAlpha = 0.6f))
            .apply { targetContentZIndex = 1f }
    } else {
        (slideInHorizontally(slide) { width -> -width / PARALLAX_FRACTION } + fadeIn(spec, initialAlpha = 0.6f) togetherWith
            slideOutHorizontally(slide) { width -> width })
            .apply { targetContentZIndex = 0f }
    }
}

private const val TRANSITION_MILLIS = 320

/** The base retreats a quarter of the width while a card covers it: parallax, not a race. */
private const val PARALLAX_FRACTION = 4

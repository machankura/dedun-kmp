package com.machankura.dedun.compose.ui.theme

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntOffset
import androidx.navigation.NavBackStackEntry

/**
 * Whether the platform has been asked to keep motion to a minimum.
 *
 * Provided by [BennuTheme] as [LocalReducedMotion]; the transitions below read it, so a
 * call site does not have to remember to. Reading it directly is for a screen animating
 * something the shared helpers do not cover.
 *
 * The same expect/actual shape as [platformThemeContrast], for the same reason: this is a
 * per-platform accessibility setting, it can change while the app is running, and somebody
 * who turns it on because motion makes them ill should not have to restart the app.
 */
@Composable
expect fun platformReducedMotion(): Boolean

/** Defaults to `false`, which is the app's behaviour anywhere the theme is not reached. */
val LocalReducedMotion = staticCompositionLocalOf { false }

/** `MaterialTheme.reducedMotion`, to match `MaterialTheme.breakpoint` and `MaterialTheme.spacing`. */
val MaterialTheme.reducedMotion: Boolean
    @Composable
    @ReadOnlyComposable
    get() = LocalReducedMotion.current

/**
 * The transitions between navigation destinations.
 *
 * **What this replaces.** All 43 routes took navigation-compose's default, which on
 * android and desktop is `fadeIn(tween(700))` / `fadeOut(tween(700))`. That is not the
 * hard cut the plan expected -- but 700 milliseconds is roughly three times M3's own
 * duration for a full-screen transition, and a `tween` written into a library's internals
 * is not a decision this app made. Both directions now come from the theme's
 * [androidx.compose.material3.MotionScheme], which is where phase 1 put the one decision
 * about how this app moves.
 *
 * **Shape.** M3's shared-axis transition for forward and backward navigation: the arriving
 * screen slides in from the trailing edge while the leaving one slides out toward the
 * leading edge, both fading. Going back mirrors it, so the direction of travel is legible
 * rather than a dissolve that looks the same either way. `slideIntoContainer` is
 * layout-direction aware, so an RTL locale gets the mirror image for free.
 *
 * Spatial specs for the slide and effects specs for the fade, which is the distinction the
 * scheme draws: spatial motion is springy because it moves something, effects motion is not
 * because a fading colour that overshoots looks like a fault.
 *
 * Typed for `NavBackStackEntry` rather than star-projected. These are only ever handed to
 * a `NavHost`, and a star projection would make every call site cast.
 */
object NavigationMotion {

    /** The distance a sliding screen travels, as a fraction of the container. */
    private const val SlideFraction = 0.25f

    @Composable
    fun enter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition {
        val reduced = MaterialTheme.reducedMotion
        val spatial = MaterialTheme.motionSchemeSpatial()
        val effects = MaterialTheme.motionSchemeEffects()
        return {
            if (reduced) {
                fadeIn(effects)
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = spatial,
                    initialOffset = { (it * SlideFraction).toInt() },
                ) + fadeIn(effects)
            }
        }
    }

    @Composable
    fun exit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition {
        val reduced = MaterialTheme.reducedMotion
        val spatial = MaterialTheme.motionSchemeSpatial()
        val effects = MaterialTheme.motionSchemeEffects()
        return {
            if (reduced) {
                fadeOut(effects)
            } else {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Start,
                    animationSpec = spatial,
                    targetOffset = { (it * SlideFraction).toInt() },
                ) + fadeOut(effects)
            }
        }
    }

    @Composable
    fun popEnter(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition {
        val reduced = MaterialTheme.reducedMotion
        val spatial = MaterialTheme.motionSchemeSpatial()
        val effects = MaterialTheme.motionSchemeEffects()
        return {
            if (reduced) {
                fadeIn(effects)
            } else {
                slideIntoContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = spatial,
                    initialOffset = { (it * SlideFraction).toInt() },
                ) + fadeIn(effects)
            }
        }
    }

    @Composable
    fun popExit(): AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition {
        val reduced = MaterialTheme.reducedMotion
        val spatial = MaterialTheme.motionSchemeSpatial()
        val effects = MaterialTheme.motionSchemeEffects()
        return {
            if (reduced) {
                fadeOut(effects)
            } else {
                slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.End,
                    animationSpec = spatial,
                    targetOffset = { (it * SlideFraction).toInt() },
                ) + fadeOut(effects)
            }
        }
    }
}

/**
 * The scheme's default spatial spec, typed for a slide.
 *
 * `MotionSchemeKeyTokens` -- which the plan named -- is `internal` to material3, so an app
 * cannot reach the tokens by name. `MaterialTheme.motionScheme` is the public surface and
 * gives the same six specs; these two helpers exist only to name which of them this app
 * uses for what, so that the choice is made once rather than at every call site.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaterialTheme.motionSchemeSpatial(): FiniteAnimationSpec<IntOffset> =
    motionScheme.defaultSpatialSpec()

/** The scheme's default effects spec, typed for a fade. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MaterialTheme.motionSchemeEffects(): FiniteAnimationSpec<Float> =
    motionScheme.defaultEffectsSpec()

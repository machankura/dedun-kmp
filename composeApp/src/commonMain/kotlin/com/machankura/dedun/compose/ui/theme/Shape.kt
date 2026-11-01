package com.machankura.dedun.compose.ui.theme

import androidx.compose.material3.Shapes

/**
 * The shape scale, and which corner belongs to what.
 *
 * Baseline M3, unmodified, for the same reason [AuxTypography] is: the values are already
 * right for this app. The corners hand-written across the tree land on the scale almost
 * exactly —
 *
 *     RoundedCornerShape(4.dp)   x3    = extraSmall
 *     RoundedCornerShape(12.dp)  x11   = medium
 *     RoundedCornerShape(16.dp)  x2    = large
 *     RoundedCornerShape(30.dp)  x1    ~ extraLarge (28dp)
 *
 * — so nothing needs restyling. What is missing is that they are literals, which is why the
 * 30dp one drifted two units from the scale and why nothing can be adjusted per breakpoint
 * or per density later. Migrating those seventeen call sites onto `MaterialTheme.shapes` is
 * a later phase; this file is what they migrate onto.
 *
 * **The scale, and what each step is for.** Under `MaterialExpressiveTheme` there are eight
 * steps rather than five:
 *
 *  - `extraSmall` (4dp) — small containers whose corners should barely read: text field
 *    indicators, small badges, snackbar edges.
 *  - `small` (8dp) — chips and other compact controls.
 *  - `medium` (12dp) — cards, and the default for a container holding a group of content.
 *  - `large` (16dp) — bigger surfaces: bottom sheet tops, large cards, dialogs.
 *  - `largeIncreased` (20dp) — expressive only. `large` where the container is prominent
 *    enough to want the extra roundness.
 *  - `extraLarge` (28dp) — extended FABs, prominent containers.
 *  - `extraLargeIncreased` (32dp) — expressive only.
 *  - `extraExtraLarge` (48dp) — expressive only. Very large surfaces, hero containers.
 *
 * A fully round shape is `CircleShape`, not a step on this scale.
 *
 * Declared explicitly rather than left to `MaterialTheme`'s default so that every slot of
 * the theme has a named home, and so this note has somewhere to live.
 */
val DedunShapes = Shapes()

package com.machankura.dedun.compose.ui.theme

import androidx.compose.material3.Typography

/**
 * The type scale, and which role carries what.
 *
 * The values are M3's baseline -- `Typography()` with nothing overridden -- which is a
 * deliberate starting point rather than an omission: the scale itself is well judged, and
 * this app has no brand typeface to substitute. What it lacks is not different numbers but
 * a rule about which of the roles to reach for, and the audit says so plainly.
 *
 * **Roles, and what belongs in each.** M3 gives fifteen roles in three sizes across five
 * families, and material3 1.10 adds an `…Emphasized` variant of every one, so thirty in
 * total. They are not interchangeable sizes; each family has a job:
 *
 *  - `display*` — a screen's own identity. A number or a word that *is* the screen.
 *    Sparing: at most one per screen, often none.
 *  - `headline*` — the top of a screen or a major section. What a heading level 1 or 2
 *    would be.
 *  - `title*` — section headers, card headers, app bar titles, list item headlines.
 *  - `body*` — prose. Anything the user reads a sentence of. **This is the default**; when
 *    in doubt about a run of text, it is `bodyMedium` or `bodyLarge`.
 *  - `label*` — **component text only**. Button labels, tab labels, chip labels, field
 *    labels, badges, timestamps. Never a sentence.
 *
 * The `…Emphasized` variants are the same size at heavier weight, for the one line in a
 * block that carries the weight -- a sender's name above their message, the active item in
 * a list. They are not a second, bolder scale to reach for freely.
 *
 * **Why this is written down.** 92 of the app's 240 typography reads are `label*`, which is
 * the smallest and tightest family and is meant for component text, while `display*` and
 * `headline*` together carry 9 uses across 43 screens. The effect is a UI at one pitch:
 * body copy set in a label role reads as cramped, and nothing establishes a hierarchy
 * because the roles that would are unused. That is a call-site problem, not a scale
 * problem, which is why this file stays baseline while the rule lives here for the sweep
 * that fixes them.
 *
 * Until this commit this file declared `package com.example.ui.theme`, one of three package
 * namespaces holding live UI code in this tree.
 */
val AuxTypography = Typography()

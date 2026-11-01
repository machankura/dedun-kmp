package com.machankura.dedun.compose.ui.composable.widgets

/**
 * An icon or image that adds nothing a screen reader user would miss.
 *
 * `contentDescription = null` is the correct Compose API for this -- M3 asks that
 * decorative visuals be "annotated as decorative in order to hide them in code", and null
 * is how that annotation is spelled. The problem with `null` is not what it does; it is
 * that it looks identical whether somebody decided the icon was decorative or never
 * thought about it, and 18 of them in this tree were indistinguishable.
 *
 * `contentDescription = Decorative` compiles to the same null and says which it was. It is
 * also greppable, so docs/scripts/m3-audit.sh can count the ones still to triage.
 *
 * **Use it when the adjacent text already says what the icon says** -- a lock beside
 * "Private to Ada", a check beside "The group has a shared key.", an icon inside a button
 * whose label is right there.
 *
 * **Do not use it for an icon carrying state the text does not repeat**: a filled-versus-
 * empty circle beside a member's name, a status icon in a list row. Those get a real
 * description, and M3's rule for writing one is to name the purpose rather than the
 * picture, and never to include the role -- "Contributed", not "green check", and never
 * "Contributed icon".
 */
@Suppress("MayBeConstant") // a `const` cannot be nullable, and null is the point
val Decorative: String? = null

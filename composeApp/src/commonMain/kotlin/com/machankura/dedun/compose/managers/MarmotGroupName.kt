package com.machankura.dedun.compose.managers

/**
 * What a Marmot room is called: a `#`, then the name.
 *
 * A device's room list holds two unrelated kinds of room. A NIP-17 room is a
 * conversation between the people in it; a Marmot room is a *group* -- it has an
 * id its key derives, a membership baked into an MLS tree, admins who can act for
 * it, and a signature anyone can check. Nothing on a row says which one is which,
 * and the two behave differently enough that guessing is a mistake. The `#` says
 * it, in the one place a user always reads.
 *
 * This replaces naming a group's Marmot room `"Ekklesia (#admins)"`. That form
 * said the same thing in more words and only for the *admin* room -- a subgroup
 * got no marker at all, so half the Marmot rooms on a device were unmarked, and
 * "Ekklesia (#admins)" sorted nowhere near "Ekklesia". `#Ekklesia` marks both
 * kinds, and marks them at the front where a truncated row still shows it.
 *
 * Applied where a Marmot room is minted rather than where it is drawn, because
 * the name is baked into the epoch-0 `MarmotGroupData` every member is welcomed
 * with: a name that only existed at the point of display would be a name this
 * device alone could see.
 */
object MarmotGroupName {
    /** Shown in a name field so the user reads the name they are about to get. */
    const val PREFIX = "#"

    /**
     * [name] under the convention, and [name] again if it is already there.
     *
     * Idempotent on purpose. A subgroup's name is normalised twice from the same
     * bare subject and by two unrelated callers -- once into the birth
     * certificate the parent's quorum signs, once onto the room itself -- and
     * those two have to be the same string, or the room is not called what its
     * parent certified.
     *
     * A blank name is handed back untouched rather than turned into a bare `#`.
     * No caller passes one -- each has a fallback for the room whose subject is
     * missing -- and inventing a name here would hide that if one ever did.
     */
    fun of(name: String): String {
        val bare = name.trim().trimStart('#').trim()

        return if (bare.isEmpty()) name.trim() else PREFIX + bare
    }
}

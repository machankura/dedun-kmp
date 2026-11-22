package com.machankura.dedun.compose.managers

/**
 * What a Marmot room is called: an `&`, then the name.
 *
 * A device's room list holds two unrelated kinds of room. A NIP-17 room is a
 * conversation between the people in it; a Marmot room is a *group* -- it has an
 * id its key derives, a membership baked into an MLS tree, admins who can act for
 * it, and a signature anyone can check. Nothing on a row says which one is which,
 * and the two behave differently enough that guessing is a mistake. The `&` says
 * it, in the one place a user always reads.
 *
 * The mark has been spelled three ways. First `"Ekklesia (#admins)"`, which said
 * it in more words and only for the *admin* room -- a subgroup got no marker at
 * all, and "Ekklesia (#admins)" sorted nowhere near "Ekklesia". Then
 * `#Ekklesia`, which marked both kinds at the front, where a truncated row still
 * shows it. Now `&Ekklesia`, because `#` already means something in nostr and in
 * this app: `#word` in a note is a hashtag, and a leading `#` in search is a
 * hashtag search, so a group's name written in a note or typed into search was
 * read as a topic. Nothing gives `&` a meaning. docs/group-name-prefix.md.
 *
 * Applied where a Marmot room is minted rather than where it is drawn, because
 * the name is baked into the epoch-0 `MarmotGroupData` every member is welcomed
 * with: a name that only existed at the point of display would be a name this
 * device alone could see. Applied again where a Welcome is joined, so a room
 * minted under `#` -- by an older Dedun, or by a Dedun peer -- or by a client
 * that marks nothing, is called the same way as every other.
 */
object MarmotGroupName {
    /** Shown in a name field so the user reads the name they are about to get. */
    const val PREFIX = "&"

    /**
     * Every mark a Marmot room's name has been given: the current one, and the
     * `#` that rooms minted before docs/group-name-prefix.md still carry in
     * their MLS state and their birth certificates.
     */
    private val MARKS = charArrayOf('&', '#')

    /**
     * [name] under the convention, and [name] again if it is already there.
     *
     * Idempotent on purpose. A subgroup's name is normalised twice from the same
     * bare subject and by two unrelated callers -- once into the birth
     * certificate the parent's quorum signs, once onto the room itself -- and
     * those two have to be the same string, or the room is not called what its
     * parent certified.
     *
     * Any leading run of marks and space is taken off before the `&` goes on,
     * so an old `#name` and a new `&name` say the same thing. That is what lets
     * this one function rename the rooms already on a device, the rooms a
     * Welcome brings in, and the names old certificates carry.
     *
     * A blank name is handed back untouched rather than turned into a bare `&`,
     * and so is a name that is only marks. No caller passes one -- each has a
     * fallback for the room whose subject is missing -- and inventing a name
     * here would hide that if one ever did.
     */
    fun of(name: String): String {
        val bare = name.trimStart { it.isWhitespace() || it in MARKS }.trimEnd()

        return if (bare.isEmpty()) name.trim() else PREFIX + bare
    }
}

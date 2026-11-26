package com.machankura.dedun.compose.managers

/**
 * What a Marmot room is called, and how it is drawn: `Ekklesia`, drawn `{Ekklesia}`.
 *
 * A device's room list holds two unrelated kinds of room. A NIP-17 room is a
 * conversation between the people in it; a Marmot room is a *group* -- it has an
 * id its key derives, a membership baked into an MLS tree, admins who can act for
 * it, and a signature anyone can check. Nothing on a row says which one is which,
 * and the two behave differently enough that guessing is a mistake. The braces say
 * it, at the front of the row, where a truncated row still shows the `{`.
 *
 * The mark is drawn, not stored. It says what kind of room a row is, which is not
 * part of what the group is called, and storing it meant every reader had to strip
 * it before comparing, every change of character needed a migration, and the mark
 * ended up signed into certificates that can never change. So the database, the
 * wire and every certificate carry the bare name, [bare] is what makes a name so,
 * and [display] is the only place the braces go on. docs/group-name-braces.md.
 *
 * The mark has been spelled four ways. First `"Ekklesia (#admins)"`, only for the
 * admin room. Then `#Ekklesia`, stored. Then `&Ekklesia`, stored, because `#word`
 * is a hashtag (docs/group-name-prefix.md). Now `{Ekklesia}`, drawn. The two stored
 * marks are still in rooms minted before, in Dedun's rooms, and in old birth
 * certificates, which is why [bare] takes them off.
 *
 * Giza draws a confederation `{{name}}` by a rule of its own; Dedun takes no
 * confederations (docs/giza-second-batch.md, D12).
 */
object MarmotGroupName {
    /** Drawn before the name, and shown in a name field before what is typed. */
    const val PREFIX = "{"

    /** Drawn after the name, and shown at the trailing edge of a name field. */
    const val SUFFIX = "}"

    /**
     * Every mark a Marmot room's name has been stored with: the `&` of
     * docs/group-name-prefix.md and the `#` before it. Rooms minted under them still
     * carry them in their MLS state, and certificates signed under them in their tags.
     */
    private val LEGACY_MARKS = charArrayOf('&', '#')

    /**
     * [name] as it is stored: no mark, no braces, no surrounding space.
     *
     * Idempotent, and `bare` of any old spelling -- `#X`, `&X`, `{X}` -- is `X`, which is
     * what lets one function name the rooms a Welcome brings in, the rooms already on a
     * device, and the names old certificates carry.
     *
     * Idempotence matters for one more reason. A subgroup's name is made bare twice from
     * the same subject by two unrelated callers -- once into the birth certificate the
     * parent's quorum signs, once onto the room itself -- and those two have to be the
     * same string, or the room is not called what its parent certified.
     *
     * Only a *leading* mark is a mark: `Arts & crafts` keeps its `&`. A blank name, or one
     * that is only marks, is handed back trimmed rather than made up.
     */
    fun bare(name: String): String = NameMarks.strip(name, LEGACY_MARKS).ifEmpty { name.trim() }

    /**
     * [name] as it is drawn: `{` + [bare] + `}`. Takes a stored name in any spelling, so it
     * draws a room minted under `&` the same as one minted since, and drawing a drawn name
     * again changes nothing. A blank name is not given braces.
     */
    fun display(name: String): String {
        val bare = NameMarks.strip(name, LEGACY_MARKS)
        return if (bare.isEmpty()) name.trim() else PREFIX + bare + SUFFIX
    }
}

package com.machankura.dedun.compose.nostr

/**
 * Which Marmot profile a group, a KeyPackage or a bundle is on.
 *
 * The spec was reorganised from the MIP-era layout -- one monolithic `0xF2EE` group
 * data extension, no proof that a leaf's signature key belongs to its account -- into
 * the *current profile*: group state as versioned components in an
 * `app_data_dictionary`, and every member leaf carrying an account identity proof.
 * quartz reads both; the reference engine refuses to add a member to a legacy group
 * at all. So the profile is the thing that decides which clients a room can reach,
 * and it is stored explicitly rather than inferred so that a row written by a build
 * that knew only one is read as that one.
 *
 * See docs/marmot-v2.md.
 */
enum class MarmotProfile(
    /** The column value. Stable: rows outlive builds. */
    val wireName: String,
) {
    /** MIP-01 through MIP-03 as this app first spoke them. Rows with no profile are these. */
    LEGACY("legacy"),

    /** The current profile: components, identity proofs, framed KeyPackages. */
    CURRENT("current"),
    ;

    companion object {
        /**
         * [wireName] back to the profile. Null and anything unknown read as [LEGACY]:
         * every row written before the column existed is legacy, and a value this
         * build does not know is not a reason to treat a stored bundle as unusable.
         */
        fun fromWireName(name: String?): MarmotProfile = entries.firstOrNull { it.wireName == name } ?: LEGACY

        /**
         * The profile new rooms are created on.
         *
         * Legacy until the rollout flips it -- docs/marmot-v2.md, Phase 9. A
         * current-profile room admits only leaves carrying an identity proof, which
         * only a build past Phase 3 can make, so flipping this is the moment this
         * app's users stop being able to make rooms for members who have not
         * upgraded. One constant, read only by the two creation sites.
         */
        val DEFAULT_FOR_NEW_ROOMS: MarmotProfile = LEGACY
    }
}

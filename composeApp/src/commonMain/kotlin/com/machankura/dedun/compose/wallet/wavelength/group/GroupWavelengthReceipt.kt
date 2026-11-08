package com.machankura.dedun.compose.wallet.wavelength.group

/**
 * One entry as a record somebody can keep: what it was, how much, whose, when, what it cost, **and
 * whose word each of those is**.
 *
 * B9 of `docs/group-wavelength-redesign.md`. A member paid by the group has nothing to show for it but
 * a row on a screen in an app nobody else has, and the guide's activity chapter asks for a record that
 * leaves the app. The provenance travels with it, because the whole of U2 is that this wallet's figures
 * come from two places and a receipt that does not say which is a receipt that makes a claim it cannot
 * support.
 *
 * Nothing here is new information and nothing is derived: every line is something the entry screen is
 * already showing. The composable resolves the words -- they are string lookups and a member's name --
 * and this assembles them, which is what makes it a function with a fixture rather than a screen.
 */
object GroupWavelengthReceipt {

    /** What the group can actually show for an entry. */
    sealed interface Proof {
        /**
         * An identifier this device has of its own: a coin, a round or a transfer it signed for, or a
         * boarding payment it has watched arrive on the chain.
         */
        data class Held(val id: String) : Proof

        /**
         * A Lightning payout, which this wallet can never prove.
         *
         * The preimage is the proof of a Lightning payment and no device here ever sees one
         * (`docs/group-wavelength-transaction-details.md`, T4). The receipt says so in the words the
         * technical sheet already uses -- `group_wavelength_tech_no_proof` -- rather than a second
         * sentence of its own, which is what the shared `entryHeadline`/`entryStatus` helpers exist to
         * prevent.
         */
        data object NoneForLightning : Proof

        /** Nothing to name, and the source line above has already said whose word it is. */
        data object Unsaid : Proof
    }

    /**
     * The words a receipt is made of, each already resolved by the screen that is showing them.
     *
     * [source] is the sentence saying whose word the entry is, and it is not optional: it is the line
     * this whole object exists to carry out of the app.
     */
    data class Words(
        val headline: String,
        val status: String,
        val at: String,
        val amount: String,
        val source: String,
        val fee: String? = null,
        val whose: String? = null,
        /** `What the group can show: %1$s`, with [Proof.Held]'s id in it. */
        val holds: ((String) -> String)? = null,
        /** `group_wavelength_tech_no_proof`, for [Proof.NoneForLightning]. */
        val noProof: String? = null,
    )

    /** The receipt, newline-separated, in the order the entry screen shows it. */
    fun of(entry: GroupWavelengthEntry, words: Words): String = listOfNotNull(
        words.headline,
        "${words.status}. ${words.at}",
        words.amount,
        words.fee,
        words.whose,
        // Above the proof line, because a reader who gets no further than this has still been told the
        // one thing that qualifies everything above it.
        words.source,
        when (val proof = proof(entry)) {
            is Proof.Held -> words.holds?.invoke(proof.id)
            Proof.NoneForLightning -> words.noProof
            Proof.Unsaid -> null
        },
    ).joinToString("\n")

    /**
     * What the group holds for [entry], from the rows this device signed.
     *
     * **There is no txid here, which this plan expected.** `GroupArkLeave` -- an onchain payout's own
     * row -- holds the round's `commitment` and the destination `script`, and no transaction id: a
     * payout leaves in a round, and the round is the thing this device put its signature on. The
     * commitment is what a member can take to the chain, so the commitment is what the receipt names.
     */
    fun proof(entry: GroupWavelengthEntry): Proof = when (val detail = entry.detail) {
        // The coin on the chain that the group's key signed over: what a payer can be shown. Before a
        // round signs for it there is still an outpoint to name -- the payment is on the chain already,
        // in the mempool or in a block -- and it is this device's own sighting rather than the
        // operator's word, which is the one case where `Held` is the honest answer for money nobody has
        // signed for yet. Saying `Unsaid` here would tell a payer there is nothing to check at exactly
        // the moment they most want to check something.
        is GroupWavelengthEntry.Detail.OnchainDeposit ->
            (detail.boarded?.outpoint ?: detail.arriving?.row?.outpoint)?.let(Proof::Held) ?: Proof.Unsaid
        // The transfer the claim went into. Not the payment hash: a hash shows a payment was asked for.
        is GroupWavelengthEntry.Detail.LightningDeposit -> detail.claim?.ark?.let(Proof::Held) ?: Proof.Unsaid
        is GroupWavelengthEntry.Detail.LightningPayout -> Proof.NoneForLightning
        is GroupWavelengthEntry.Detail.OnchainPayout -> detail.leave?.commitment?.let(Proof::Held) ?: Proof.Unsaid
        is GroupWavelengthEntry.Detail.Round -> detail.round?.commitment?.let(Proof::Held) ?: Proof.Unsaid
        // Asked for and not yet in a round: there is nothing on any chain to point at.
        is GroupWavelengthEntry.Detail.Exit -> Proof.Unsaid
        // Only their word, which the source line has already said in full.
        is GroupWavelengthEntry.Detail.OperatorLine -> Proof.Unsaid
    }
}

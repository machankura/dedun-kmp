package com.machankura.dedun.compose.ui.view.model

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import com.machankura.dedun.compose.managers.GuardianDerivation
import com.machankura.dedun.compose.text.groupedBlocks

/**
 * A guardian delay as somebody is choosing it -- a preset, or blocks typed --
 * for the two screens that choose one: the subgroup picker
 * (`docs/guardian-delay.md`) and the group's member-wallet policy
 * (`docs/member-wallet-delay.md`).
 *
 * Holds the text as typed and never clamps it, so the field can say *at most
 * 65,535 blocks* about the number somebody actually entered.
 */
class GuardianDelayInput(initial: Long = GuardianDerivation.DEFAULT_DELAY) {
    /** The delay, in blocks, as typed or picked. */
    val input: MutableState<String> = mutableStateOf(initial.toString())

    /** Whether the delay is being typed rather than picked from a preset. */
    val isCustom: MutableState<Boolean> = mutableStateOf(initial !in PRESETS)

    /** Picks a preset. */
    fun pick(blocks: Long) {
        isCustom.value = false
        input.value = blocks.toString()
    }

    /** Switches to typing, starting from the delay already chosen. */
    fun useCustom() {
        isCustom.value = true
    }

    /** What was typed, digits only, kept as typed. */
    fun type(value: String) {
        input.value = value.filter { it in '0'..'9' }.take(MAX_DIGITS)
    }

    /** Starts again from [blocks]: a preset as its chip, anything else in the field. */
    fun reset(blocks: Long) {
        input.value = blocks.toString()
        isCustom.value = blocks !in PRESETS
    }

    /** The delay typed or picked, or null while the field holds no number. */
    fun delay(): Long? = input.value.toLongOrNull()

    /** Why the delay cannot be used, or null when it can. */
    fun problem(): String? {
        val delay = delay() ?: return "Give the delay in blocks."
        return when {
            delay < GuardianDerivation.MIN_DELAY -> "A delay is at least ${GuardianDerivation.MIN_DELAY} blocks."
            delay > GuardianDerivation.MAX_DELAY -> "A delay is at most ${GuardianDerivation.MAX_DELAY.groupedBlocks()} blocks."
            else -> null
        }
    }

    companion object {
        /**
         * The delays offered as chips, in blocks -- `docs/guardian-delay.md`, D4:
         * an hour, two (the default), a day, a week, a month, six months, and the
         * longest a leaf can carry.
         */
        val PRESETS: List<Long> = listOf(6L, 12L, 144L, 1_008L, 4_320L, 26_280L, GuardianDerivation.MAX_DELAY)

        /** Enough digits for anything past the ceiling, so the field can say so. */
        private const val MAX_DIGITS = 7
    }
}

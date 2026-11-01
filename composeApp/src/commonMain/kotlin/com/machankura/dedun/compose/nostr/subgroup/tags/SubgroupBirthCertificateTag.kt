package com.machankura.dedun.compose.nostr.subgroup.tags

import com.vitorpamplona.quartz.nip01Core.core.Event
import com.vitorpamplona.quartz.nip01Core.core.has
import com.vitorpamplona.quartz.utils.ensure

/**
 * The parent's signed birth certificate, carried whole on the child's key state.
 *
 * A whole event inside a tag value, which wants justifying. The alternative is a
 * bare 64-byte signature plus a rule for rebuilding the event it covers -- kind,
 * author, tags, `created_at` -- from fields carried alongside. That rule breaks
 * silently the first time the certificate's shape changes: a rebuild differing by
 * one byte hashes to an id whose signature does not verify, and is
 * indistinguishable from a forgery. Every state signed before the change would
 * stop being believed, all at once, for a reason nothing logs.
 *
 * A few hundred bytes inside an encryption removes that class entirely. The
 * event travels as the parent signed it and is checked as the parent signed it.
 *
 * Nothing here says the certificate is *good*. Parsing is JSON and nothing else;
 * whether the parent really signed it, and really signed it for this child, is
 * `SubgroupBirthCertificateEvent.certifies` -- see `GroupKeyStateManager.stateFrom`,
 * which drops the whole state when it says no.
 */
class SubgroupBirthCertificateTag(
    val certificate: Event,
) {
    fun toTagArray() = assemble(certificate = certificate)

    companion object {
        const val TAG_NAME = "birth_certificate"

        fun parse(tag: Array<String>): SubgroupBirthCertificateTag? {
            ensure(tag.has(1)) { return null }
            ensure(tag[0] == TAG_NAME) { return null }

            val certificate = Event.fromJsonOrNull(tag[1]) ?: return null

            return SubgroupBirthCertificateTag(certificate = certificate)
        }

        fun assemble(certificate: Event): Array<String> =
            arrayOf(TAG_NAME, certificate.toJson())

        fun assemble(subgroupBirthCertificateTag: SubgroupBirthCertificateTag) =
            assemble(certificate = subgroupBirthCertificateTag.certificate)
    }
}

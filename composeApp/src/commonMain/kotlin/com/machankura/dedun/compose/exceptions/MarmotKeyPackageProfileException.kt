package com.machankura.dedun.compose.exceptions

/**
 * A peer's KeyPackage cannot join this room because its leaf is on the wrong Marmot
 * profile: a current-profile room requires a leaf that advertises the
 * `app_data_dictionary` extension and the `app_data_update` proposal, and a package
 * from an older build advertises neither. The member can fix that -- by updating --
 * and cannot fix a generic failure, which is why this is its own exception with a
 * message meant to be shown. See docs/marmot-v2.md, Phase 4.
 */
class MarmotKeyPackageProfileException(message: String? = null, cause: Throwable? = null) : Exception(message, cause)

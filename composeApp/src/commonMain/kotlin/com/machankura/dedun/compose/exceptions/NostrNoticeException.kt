package com.machankura.dedun.compose.exceptions

class NostrNoticeException(
    val reason: String?,
    val subscriptionId: String? = null
): RuntimeException("$subscriptionId: $reason")
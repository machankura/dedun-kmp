package com.machankura.dedun.compose.exceptions

class NostrPublishException(
    override val cause: Throwable? = null,
    override val message: String? = null,
) : RuntimeException()
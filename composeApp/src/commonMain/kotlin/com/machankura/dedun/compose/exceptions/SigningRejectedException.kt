package com.machankura.dedun.compose.exceptions

class SigningRejectedException(message: String? = null, cause: Throwable? = null) : SignatureException(message, cause)
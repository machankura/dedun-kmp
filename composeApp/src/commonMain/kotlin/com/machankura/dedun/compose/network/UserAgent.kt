package com.machankura.dedun.compose.network

object UserAgent {
    const val APP_NAME = "Bennu"
    const val CLIENT_NAME = "Bennu"
}

fun String.asClientTag(): Array<String> = arrayOf(
    "client",
    this
)
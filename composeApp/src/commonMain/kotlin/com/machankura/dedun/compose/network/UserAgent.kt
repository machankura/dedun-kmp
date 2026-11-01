package com.machankura.dedun.compose.network

object UserAgent {
    const val APP_NAME = "Dedun"
    const val CLIENT_NAME = "Dedun"
}

fun String.asClientTag(): Array<String> = arrayOf(
    "client",
    this
)
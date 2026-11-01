package com.machankura.dedun.compose

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
package com.machankura.dedun.compose

expect object AppVersion {
    val versionName: String
    val versionCode: String // iOS uses string for build number
}
package com.machankura.dedun.compose.contacts

actual fun deviceRegionCode(): String? = java.util.Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase()

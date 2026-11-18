package com.machankura.dedun.compose.extensions

import com.vitorpamplona.mls.group.MlsGroup

fun MlsGroup.exporterSecret() = this.exporterSecret(
    "marmot",
    "group-event".encodeToByteArray(),
    32,
)
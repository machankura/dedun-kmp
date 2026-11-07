package com.machankura.dedun.compose.network

import co.touchlab.kermit.Logger
import com.machankura.dedun.compose.extensions.toHex
import com.machankura.dedun.compose.wallet.wavelength.group.GroupWavelengthTerms
import fr.acinq.bitcoin.Chain
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Asks an Ark operator what its terms are now, on the app's own engine: a proposer's check against [GroupWavelengthTerms], and no more.
 * Null for any failure, since an operator that can't be reached is no reason to think its terms have changed.
 */
class GroupWavelengthTermsReader(base: HttpClient = HttpClient(platformHttpClientEngineFactory())) {
    private val logger = Logger.withTag("GroupWavelengthTermsReader")
    private val client = base.config {
        install(HttpTimeout) { requestTimeoutMillis = 10_000 }
    }

    suspend fun read(chain: Chain): GroupWavelengthTerms.Terms? {
        val url = GroupWavelengthTerms.infoUrl(chain) ?: return null
        return try {
            parse(client.post(url) { contentType(ContentType.Application.Json); setBody("{}") }.bodyAsText())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.i { "Couldn't read the operator's terms on ${chain.name}: ${e.message}" }
            null
        }
    }

    companion object {
        /** The fields of `get-info` a statement or a screen uses, or null for anything else. */
        @OptIn(ExperimentalEncodingApi::class)
        fun parse(body: String): GroupWavelengthTerms.Terms? = runCatching {
            val info: JsonObject = Json.parseToJsonElement(body).jsonObject
            fun number(name: String): Long = info.getValue(name).jsonPrimitive.content.toLong()
            GroupWavelengthTerms.Terms(
                operatorKey = Base64.decode(info.getValue("pubkey").jsonPrimitive.content).toHex(),
                exitDelay = info.getValue("vtxo_exit_delay").jsonPrimitive.long,
                boardingDelay = info.getValue("boarding_exit_delay").jsonPrimitive.long,
                boardingConfirmations = number("min_confirmations").toInt(),
                minBoardingSat = number("min_boarding_amount"),
                minOperatorFeeSat = number("min_operator_fee"),
                maxVtxoSat = number("max_vtxo_amount"),
                maxWalletSat = number("max_user_balance"),
                freeRefreshWindowBlocks = number("free_refresh_window_blocks"),
            )
        }.getOrNull()
    }
}

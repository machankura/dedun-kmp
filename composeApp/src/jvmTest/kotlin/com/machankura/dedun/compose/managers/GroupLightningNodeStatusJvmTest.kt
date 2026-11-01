package com.machankura.dedun.compose.managers

import fr.acinq.bitcoin.Chain
import fr.acinq.lightning.Feature
import fr.acinq.lightning.FeatureSupport
import fr.acinq.lightning.Features
import fr.acinq.lightning.UnknownFeature
import fr.acinq.lightning.io.TcpSocket
import fr.acinq.lightning.utils.Connection
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Why a connection to the LSP ended, as a cause the screen can say (`docs/lsp-connection-status.md`,
 * Phase 1). Each case is one of `dropReason`'s, so the cause and the logged detail never disagree.
 */
class GroupLightningNodeStatusJvmTest {
    private val ours = Features(Feature.SimpleTaprootChannels to FeatureSupport.Mandatory, Feature.VariableLengthOnion to FeatureSupport.Mandatory)
    private val closed = Connection.CLOSED(TcpSocket.IOException.ConnectionClosed())

    @Test
    fun `an LSP that does not offer a feature we require is a feature mismatch`() {
        val drop = GroupLightningNode.drop(ours, Features(Feature.VariableLengthOnion to FeatureSupport.Mandatory), closed)
        assertEquals(LspDrop.Cause.FEATURE_MISMATCH, drop.cause)
        assertEquals("Connection closed: the LSP does not offer simple_taproot_channels, which this node requires", drop.detail)
    }

    @Test
    fun `an LSP that requires a feature we do not know is a feature mismatch`() {
        val drop = GroupLightningNode.drop(ours, Features(ours.activated, setOf(UnknownFeature(600), UnknownFeature(601))), closed)
        assertEquals(LspDrop.Cause.FEATURE_MISMATCH, drop.cause)
    }

    @Test
    fun `a connection the LSP ends with features agreed is closed by the LSP`() {
        assertEquals(LspDrop(LspDrop.Cause.CLOSED_BY_LSP, "Connection closed"), GroupLightningNode.drop(ours, ours, closed))
        assertEquals(LspDrop.Cause.CLOSED_BY_LSP, GroupLightningNode.drop(ours, null, closed).cause)
    }

    @Test
    fun `no socket is unreachable`() {
        val refused = Connection.CLOSED(TcpSocket.IOException.ConnectionRefused())
        assertEquals(LspDrop.Cause.UNREACHABLE, GroupLightningNode.drop(ours, null, refused).cause)
    }

    @Test
    fun `a close with no reason and no features is the handshake`() {
        assertEquals(LspDrop(LspDrop.Cause.HANDSHAKE, "closed by this node, before the LSP sent its features"), GroupLightningNode.drop(ours, null, Connection.CLOSED(null)))
    }

    @Test
    fun `anything else is other, with its detail`() {
        val unknown = Connection.CLOSED(TcpSocket.IOException.Unknown("Broken pipe"))
        assertEquals(LspDrop(LspDrop.Cause.OTHER, "Broken pipe"), GroupLightningNode.drop(ours, ours, unknown))
    }

    @Test
    fun `a dial that throws says whether it was the Tor check`() {
        assertEquals(LspDrop(LspDrop.Cause.TOR_WITHOUT_ONION, "Tor is on, and the LSP has no onion address on ${Chain.Regtest.name}"), GroupLightningNode.drop(GroupLightningNode.TorWithoutOnion(Chain.Regtest)))
        assertEquals(LspDrop(LspDrop.Cause.OTHER, "boom"), GroupLightningNode.drop(IllegalArgumentException("boom")))
    }
}

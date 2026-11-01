import Foundation
import Bark
import ComposeApp

/// The Swift half of the profile's Ark wallet on iOS: docs/bark-wallet.md, Phase 8.
///
/// Bark has no Kotlin/Native binding, so the app's Kotlin reaches Bark's Swift package
/// through this. It implements ComposeApp's `ArkEngine`: four methods, JSON across the
/// boundary. Every method name and JSON shape here is one `EngineArkWallet` in commonMain
/// asks for, and `EngineArkWalletJvmTest` pins the Kotlin side of that contract.
///
/// One wallet at a time, as Bark allows per datadir; the Kotlin holder closes the last one
/// before it opens the next.
final class BarkEngine: NSObject, ArkEngine {
    private var wallet: Wallet?
    private var onchain: OnchainWallet?
    private var notifications: NotificationHolder?

    private struct EngineConfig: Decodable {
        let network: String
        let server: String
        let esplora: String
        let userAgent: String
    }

    func open(configJson: String, words: String, datadir: String, reply: ArkEngineReply) {
        Task {
            do {
                let engineConfig = try JSONDecoder().decode(EngineConfig.self, from: Data(configJson.utf8))
                let network: Network
                switch engineConfig.network {
                case "mainnet": network = .bitcoin
                case "regtest": network = .regtest
                default: network = .signet
                }
                let config = Config(
                    serverAddress: engineConfig.server,
                    serverAccessToken: nil,
                    esploraAddress: engineConfig.esplora,
                    bitcoindAddress: nil,
                    bitcoindCookiefile: nil,
                    bitcoindUser: nil,
                    bitcoindPass: nil,
                    vtxoRefreshExpiryThreshold: nil,
                    vtxoExitMargin: nil,
                    htlcRecvClaimDelta: nil,
                    fallbackFeeRate: nil,
                    roundTxRequiredConfirmations: nil,
                    daemonSyncIntervalSecs: nil,
                    offboardRequiredConfirmations: nil,
                    daemonManualSync: nil,
                    lightningReceiveClaimRetries: nil,
                    userAgent: engineConfig.userAgent,
                    vtxoKeyGapLimit: nil
                )
                // A VTXO database restored onto another device behind the server's back is worse
                // than none, so the datadir is kept out of iCloud backups.
                var directory = URL(fileURLWithPath: datadir, isDirectory: true)
                try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
                var values = URLResourceValues()
                values.isExcludedFromBackup = true
                try directory.setResourceValues(values)

                let onchain = try await OnchainWallet.`default`(network: network, mnemonic: words, config: config, datadir: datadir)
                let wallet = try await Wallet.open(
                    network: network,
                    mnemonicOrSeed: words,
                    config: config,
                    args: WalletOpenArgs(datadir: datadir, onchain: onchain)
                )
                self.onchain = onchain
                self.wallet = wallet
                self.notifications = wallet.notifications()
                reply.ok(json: "{}")
            } catch {
                reply.error(message: "\(error)")
            }
        }
    }

    func call(method: String, argumentsJson: String, reply: ArkEngineReply) {
        Task {
            do {
                guard let wallet = self.wallet, let onchain = self.onchain else {
                    throw EngineError.notOpen
                }
                let arguments = (try? JSONSerialization.jsonObject(with: Data(argumentsJson.utf8))) as? [String: Any] ?? [:]
                let result = try await self.dispatch(method, arguments, wallet, onchain)
                reply.ok(json: try Self.encode(result))
            } catch {
                reply.error(message: "\(error)")
            }
        }
    }

    func nextEvent(reply: ArkEngineReply) {
        Task {
            do {
                guard let holder = self.notifications, let event = try await holder.nextNotification() else {
                    reply.ok(json: "null")
                    return
                }
                switch event {
                case .movementCreated(let movement), .movementUpdated(let movement):
                    reply.ok(json: try Self.encode(["type": "movement", "movement": Self.movement(movement)]))
                case .channelLagging:
                    reply.ok(json: try Self.encode(["type": "lagging"]))
                }
            } catch {
                reply.error(message: "\(error)")
            }
        }
    }

    func close(reply: ArkEngineReply) {
        Task {
            do {
                self.notifications?.cancelNextNotificationWait()
                try await self.wallet?.stopDaemonWait()
            } catch {
                // Closing goes ahead whatever the daemon said: the datadir must be released.
            }
            self.notifications = nil
            self.wallet = nil
            self.onchain = nil
            reply.ok(json: "{}")
        }
    }

    private enum EngineError: Error, CustomStringConvertible {
        case notOpen
        case unknownMethod(String)
        case missing(String)

        var description: String {
            switch self {
            case .notOpen: return "the Ark wallet is not open"
            case .unknownMethod(let name): return "unknown method \(name)"
            case .missing(let name): return "missing argument \(name)"
            }
        }
    }

    // MARK: - The port's calls, by the names EngineArkWallet uses

    private func dispatch(_ method: String, _ arguments: [String: Any], _ wallet: Wallet, _ onchain: OnchainWallet) async throws -> Any {
        switch method {
        case "recovery":
            switch wallet.recoveryStatus() {
            case .notRun: return ["kind": "notRun"]
            case .failed(let message): return ["kind": "failed", "message": message]
            case .completed(let report):
                return [
                    "kind": "completed",
                    "complete": report.isComplete,
                    "recoveredSats": report.recovered.totalSats,
                    "failedIds": report.failed.vtxoIds,
                ]
            }
        case "balance":
            let balance = try await wallet.balance()
            let chain = try await onchain.balance()
            return [
                "spendable": balance.spendableSats,
                "pendingLightningSend": balance.pendingLightningSendSats,
                "claimableLightningReceive": balance.claimableLightningReceiveSats,
                "pendingInRound": balance.pendingInRoundSats,
                "pendingBoard": balance.pendingBoardSats,
                "pendingExit": balance.pendingExitSats,
                "pendingOffboard": balance.pendingOffboardSats,
                "pendingArkSend": balance.pendingArkoorSendSats,
                "onchain": chain.totalSats,
            ]
        case "history":
            return try await wallet.history().map(Self.movement)
        case "sync":
            try await wallet.sync()
            return [:]
        case "newArkAddress":
            return ["value": try await wallet.newAddress()]
        case "receive":
            let amount = (arguments["amount"] as? NSNumber).map { $0.uint64Value }
            let onchainAddress = try await onchain.newAddress()
            let uri = try await wallet.bip321Uri(options: Bip321UriOptions(
                amountSats: amount,
                label: nil,
                message: arguments["description"] as? String,
                ark: true,
                lightning: amount != nil,
                onchainAddress: onchainAddress
            ))
            var result: [String: Any] = ["uri": uri.uri]
            result["arkAddress"] = uri.arkAddress
            result["bolt11"] = uri.lightningInvoice?.invoice
            result["paymentHash"] = uri.lightningInvoice?.paymentHash
            result["onchainAddress"] = uri.onchainAddress
            return result
        case "lightningReceiveFee":
            return Self.fee(try await wallet.estimateLightningReceiveFee(amountSats: try Self.amount(arguments)))
        case "claimLightningReceives":
            _ = try await wallet.tryClaimAllLightningReceives(wait: false)
            return [:]
        case "boardAll":
            _ = try await wallet.boardAll()
            return [:]
        case "parse":
            guard let text = arguments["text"] as? String else { throw EngineError.missing("text") }
            let request = try await wallet.parsePaymentRequest(payment: text)
            var result: [String: Any] = ["options": request.options.compactMap(Self.option)]
            result["amount"] = request.amountSats
            result["description"] = request.message ?? request.label
            result["preferred"] = request.defaultOption.flatMap(Self.option)
            return result
        case "estimateFee":
            return Self.fee(try await wallet.estimatePaymentFee(method: try Self.method(arguments), amountSats: try Self.amount(arguments)))
        case "send":
            let amount = (arguments["amount"] as? NSNumber).map { $0.uint64Value }
            let result = try await wallet.sendPayment(
                method: try Self.method(arguments),
                amountSats: amount,
                comment: arguments["comment"] as? String,
                wait: false,
                retryForSecs: nil
            )
            switch result {
            case .ark(let movementId): return ["type": "ark", "value": String(movementId)]
            case .lightning(_, let paymentHash): return ["type": "lightning", "value": paymentHash]
            case .onchain(let txid): return ["type": "onchain", "value": txid]
            }
        case "maintenance":
            try await wallet.maintenanceDelegated()
            return [:]
        case "renewalFee":
            let due = try await wallet.getVtxosToRefresh().map { $0.id }
            if due.isEmpty { return ["value": NSNull()] }
            return ["value": Self.fee(try await wallet.estimateRefreshFee(vtxoIds: due))]
        case "renew":
            let due = try await wallet.getVtxosToRefresh().map { $0.id }
            if !due.isEmpty { _ = try await wallet.refreshVtxosDelegated(vtxoIds: due) }
            return [:]
        case "firstExpiryHeight":
            return ["value": (try await wallet.getFirstExpiringVtxoBlockheight()).map { NSNumber(value: $0) } ?? NSNull()]
        case "tipHeight":
            return ["value": try await onchain.tipHeight()]
        case "vtxos":
            return try await wallet.vtxos().map { vtxo in
                [
                    "id": vtxo.id,
                    "amount": vtxo.amountSats,
                    "expiryHeight": vtxo.expiryHeight,
                    "state": Self.caseName(vtxo.state),
                ] as [String: Any]
            }
        case "onchainAddress":
            return ["value": try await onchain.newAddress()]
        case "offboardAllFee":
            return Self.fee(try await wallet.estimateOffboardAllFee(address: try Self.address(arguments)))
        case "offboardAll":
            return ["value": try await wallet.offboardAll(address: try Self.address(arguments)).txid]
        case "emergencyExitFee":
            let ids = try await wallet.spendableVtxos().map { $0.id }
            let estimate = try await wallet.estimateEmergencyExitFee(vtxoIds: ids, feeRateSatPerVb: nil, destination: nil, feeMargin: nil)
            return ["total": estimate.totalFeeSats, "fundable": estimate.fundable]
        case "startEmergencyExit":
            try await wallet.startExitForEntireWallet()
            return [:]
        case "progressExits":
            return try await wallet.progressExits(feeRateSatPerVb: nil).map { status in
                var exit: [String: Any] = ["vtxoId": status.vtxoId, "state": Self.caseName(status.state)]
                exit["error"] = status.error
                return exit
            }
        case "exitsClaimableAt":
            return ["value": (try await wallet.allExitsClaimableAtHeight()).map { NSNumber(value: $0) } ?? NSNull()]
        case "claimExits":
            let claim = try await wallet.drainExits(vtxoIds: [], drainAll: true, address: try Self.address(arguments), feeRateSatPerVb: nil)
            let signed = try await wallet.signExitClaimInputs(psbtBase64: claim.psbtBase64)
            return ["value": try await wallet.broadcastTx(txHex: try extractTxFromPsbt(psbtBase64: signed))]
        default:
            throw EngineError.unknownMethod(method)
        }
    }

    // MARK: - JSON shapes

    private static func encode(_ value: Any) throws -> String {
        let data = try JSONSerialization.data(withJSONObject: value, options: [.fragmentsAllowed])
        return String(decoding: data, as: UTF8.self)
    }

    private static func movement(_ movement: Movement) -> [String: Any] {
        var result: [String: Any] = [
            "id": movement.id,
            "status": movement.status,
            "subsystemName": movement.subsystemName,
            "subsystemKind": movement.subsystemKind,
            "effectiveBalanceSats": movement.effectiveBalanceSats,
            "offchainFeeSats": movement.offchainFeeSats,
            "sentTo": movement.sentToAddresses,
            "receivedOn": movement.receivedOnAddresses,
            "createdAt": movement.createdAt,
        ]
        result["paymentHash"] = movement.paymentHash
        result["lightningInvoice"] = movement.lightningInvoice
        result["completedAt"] = movement.completedAt
        return result
    }

    private static func fee(_ estimate: FeeEstimate) -> [String: Any] {
        ["gross": estimate.grossAmountSats, "fee": estimate.feeSats, "net": estimate.netAmountSats]
    }

    private static func option(_ option: AvailablePaymentMethod) -> [String: Any]? {
        let method: [String: Any]
        switch option.method {
        case .ark(let value): method = ["type": "ark", "value": value]
        case .bitcoin(let value): method = ["type": "bitcoin", "value": value]
        case .invoice(let value): method = ["type": "invoice", "value": value]
        case .offer(let value): method = ["type": "offer", "value": value]
        case .lightningAddress(let value): method = ["type": "lightningAddress", "value": value]
        case .lnurl(let value): method = ["type": "lnurl", "value": value]
        default: return nil
        }
        return ["method": method, "requiresAmount": option.requiresAmount, "problems": option.errors.map { "\($0)" }]
    }

    private static func method(_ arguments: [String: Any]) throws -> PaymentMethod {
        guard let method = arguments["method"] as? [String: Any],
              let type = method["type"] as? String,
              let value = method["value"] as? String else { throw EngineError.missing("method") }
        switch type {
        case "ark": return .ark(value: value)
        case "bitcoin": return .bitcoin(value: value)
        case "invoice": return .invoice(value: value)
        case "offer": return .offer(value: value)
        case "lightningAddress": return .lightningAddress(value: value)
        case "lnurl": return .lnurl(value: value)
        default: throw EngineError.missing("method")
        }
    }

    private static func amount(_ arguments: [String: Any]) throws -> UInt64 {
        guard let amount = arguments["amount"] as? NSNumber else { throw EngineError.missing("amount") }
        return amount.uint64Value
    }

    private static func address(_ arguments: [String: Any]) throws -> String {
        guard let address = arguments["address"] as? String else { throw EngineError.missing("address") }
        return address
    }

    /// An enum case's name, without its associated values: `awaitingDelta(tipHeight: …)` is
    /// `awaitingDelta`. The Kotlin side maps the names, so a case added to Bark arrives as a
    /// name it does not know rather than as a compile error here.
    private static func caseName(_ value: Any) -> String {
        let text = String(describing: value)
        return String(text.prefix { $0 != "(" })
    }
}

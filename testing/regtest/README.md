# Regtest network for the group Lightning wallet

A Bitcoin regtest network on one machine, for `GroupLightningRegtestJvmTest`: four devices open a
group Lightning wallet, receive, pay, close and sweep against a real lightning-kmp node and an eclair
LSP. What the run checks, what it found, and why each LSP setting is there is in
[docs/group-lightning-wallet.md](../../docs/group-lightning-wallet.md), "The regtest run".

`LiquidityPurchaseRegtestJvmTest` uses the same network for the liquidity plan's Phase 0
([docs/group-lightning-liquidity.md](../../docs/group-lightning-liquidity.md)): a stock node that keeps one
funding key buys inbound liquidity from the LSP, with its signatures delayed, to see what eclair accepts
and how long it waits. The group run buys liquidity too, between receiving and paying.

The run is **not** part of `./gradlew check` or CI. Without `DEDUN_REGTEST=1` the test is reported as
skipped, so a green `jvmTest` says nothing about it.

## Prerequisites

- Docker with the Compose v2 plugin (`docker compose`, not `docker-compose`)
- `curl` and `python3`, which `regtest.sh` uses to call the nodes and read their answers
- About 800 MB for the images, which the first `up` downloads: `bitcoin/bitcoin:31.1`,
  `getumbrel/electrs:v0.11.1` and `acinq/eclair:v0.14.4`. eclair 0.14.4 refuses a Bitcoin Core
  older than 31.
- These localhost ports free:

| port | what |
|---|---|
| 28443 | bitcoind RPC (`foo` / `bar`) |
| 28001 | electrs, Electrum protocol, no TLS |
| 29735 | the LSP's Lightning port, which the group's node connects to |
| 28080 | the LSP's API (password `lsp`) |
| 28081 | the payer's API (password `payer`) |

Nothing listens on any other interface, and the compose project is named `dedun-regtest`, so it sits
beside other stacks without touching them.

## Running it

From this directory:

```bash
./regtest.sh up
```

`up` starts bitcoind, makes three wallets (a miner, the LSP's, the payer's) and funds two of them,
starts electrs and both eclair nodes, and opens a 5,000,000-sat channel from the payer to the LSP. It
finishes by printing the LSP's node id. Allow a minute the first time, after the downloads.

From the repository root:

```bash
DEDUN_REGTEST=1 ./gradlew :composeApp:jvmTest --tests '*GroupLightningRegtestJvmTest'
```

It takes about thirty seconds. It can be run again on the same network as often as you like. Each
run makes a new Lightning wallet with a new node. The group's onchain key is fixed in the test fixture,
though, so its first address collects coins across runs; the checks compare balances before and after.

When you are done:

```bash
./regtest.sh down
```

`down` removes the containers, bitcoind's volume and the network. The images stay cached.

To reach each piece by hand: `./regtest.sh mine 6`, `./regtest.sh btc <rpc> [args]`,
`./regtest.sh lsp <method> -d key=value` and `./regtest.sh payer <method> -d key=value`. The last two
call eclair's API, for example `./regtest.sh lsp channels` or `./regtest.sh payer usablebalances`.

## Where the output goes

- **The run's own lines and the node's log.** With Gradle's `-q`, or whenever the test passes, nothing
  reaches the console. Everything the test printed is in
  `composeApp/build/test-results/jvmTest/TEST-com.machankura.dedun.compose.managers.GroupLightningRegtestJvmTest.xml`,
  under `<system-out>`: the `REGTEST …` progress lines, lightning-kmp's log (`fr.acinq.lightning.*`),
  and the app's managers (`GroupLightningNode`, `GroupLightningCoSigner`, `FrostSigningManager`). The
  HTML report beside it, `composeApp/build/reports/tests/jvmTest/index.html`, shows the same.
- **eclair's log.** `docker compose logs` shows almost nothing for eclair, which writes to a file in its
  data directory:

  ```bash
  docker compose exec lsp tail -n 200 /data/eclair.log
  ```

  Use `payer` in place of `lsp` for the payer's.
- **bitcoind and electrs** log to the console: `docker compose logs bitcoind` or `docker compose logs electrs`.

## Troubleshooting

| what you see | what it means |
|---|---|
| the test is reported as skipped | `DEDUN_REGTEST=1` was not set in the environment Gradle runs the test in |
| `The regtest network is not running: nothing answers for … on 127.0.0.1:…` | that service is down. Run `./regtest.sh up`, or check it with `docker compose ps -a` and its logs |
| `the payer has no open channel to the LSP` | `up` stopped part way through. Run `./regtest.sh down`, then `up` again |
| `up` prints `timed out waiting for: …` | a container exited: `docker compose ps -a` shows which, and its log says why |
| `timed out waiting for …` from the test | it lists the round-one label assignments and pool counts first (`REGTEST assigned …` and `REGTEST pool …` in the report). A pool counter with fewer than three rows is a co-signer that never answered. Otherwise look for `Error(` and `Warn:` lines in the node's log and for `WARN` in the LSP's |
| `Error(… data=…)` in the node's log | an error message from the LSP or to it. Its `data` is text in hex: `python3 -c "print(bytes.fromhex('<hex>').decode())"` reads it |
| `the payment failed: … route not found` | the LSP has no usable route to the payer. Check `./regtest.sh lsp usablebalances` and `./regtest.sh lsp allupdates`: an update with `htlcMaximumMsat` of 1 means eclair's balance thresholds were on when the channel opened (`lsp.conf` turns them off; `./regtest.sh lsp updaterelayfee -d nodeIds=<payer> -d feeBaseMsat=1000 -d feeProportionalMillionths=100` refreshes one) |
| a port is already in use | another stack has it. Change the host side of that port in `compose.yml`, and the matching port in `GroupLightningRegtestJvmTest` |
| the LSP refuses to start: `requires Bitcoin Core 31 or higher` | `compose.yml` was changed to an older bitcoind image |

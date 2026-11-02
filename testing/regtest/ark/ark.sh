#!/usr/bin/env bash
# The profile's Ark wallet's regtest network: docs/bark-wallet.md, Phase 12. `up` builds it from
# nothing -- bitcoind with a miner wallet, Core Lightning, captaind with its round wallet funded,
# and an Esplora -- and `down` removes all of it.
set -euo pipefail
cd "$(dirname "$0")"
btc() { docker compose exec -T bitcoind bitcoin-cli -regtest -rpcuser=second -rpcpassword=ark "$@"; }
captaind() { docker compose exec -T captaind captaind --config /config/captaind.toml "$@"; }
mine() { btc -rpcwallet=miner generatetoaddress "${1:-1}" "$(btc -rpcwallet=miner getnewaddress)" >/dev/null; }
wait_for() { for _ in $(seq 1 120); do if eval "$1" >/dev/null 2>&1; then return 0; fi; sleep 1; done; echo "timed out waiting for: $1" >&2; return 1; }
case "${1:-}" in
  up)
    docker compose up -d bitcoind
    wait_for "btc getblockchaininfo"
    btc -named createwallet wallet_name=miner load_on_startup=true >/dev/null 2>&1 || btc loadwallet miner >/dev/null 2>&1 || true
    mine 150
    docker compose up -d cln captaind esplora
    wait_for "captaind rpc wallet"
    rounds=$(captaind rpc wallet | python3 -c 'import sys,json; print(json.load(sys.stdin)["rounds"]["address"])')
    btc -rpcwallet=miner sendtoaddress "$rounds" 10 >/dev/null
    mine 6
    wait_for "curl -sf http://127.0.0.1:3002/blocks/tip/height"
    echo "captaind on 127.0.0.1:3535, Esplora on 127.0.0.1:3002, bitcoind RPC on 127.0.0.1:38443 (second:ark)"
    ;;
  down) docker compose down -v ;;
  mine) mine "${2:-1}" ;;
  btc) shift; btc "$@" ;;
  captaind) shift; captaind "$@" ;;
  stop-server) docker compose stop captaind ;;
  start-server) docker compose start captaind ;;
  *) echo "usage: $0 up | down | mine [n] | btc <args> | captaind <args> | stop-server | start-server"; exit 1 ;;
esac

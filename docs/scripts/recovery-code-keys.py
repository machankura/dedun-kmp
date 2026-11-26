#!/usr/bin/env python3
"""
What a v3 recovery code derives in Dedun, computed without Dedun.

An independent derivation for RecoveryCodeKeysTest's pinned vectors
(docs/recovery-code-v3.md, Phase 2): standard library only, sharing no code with the app,
so that a vector the app and this script agree on was not merely agreed with itself.

    code ─HMAC-SHA256("muun:rc")→ 32 bytes ─BIP39 entropy→ 24 words
         ─PBKDF2-SHA512(2048, "mnemonic")→ seed ─BIP32→ m/44'/1237'/0'/0/0  the nostr key
                                                       → m/50'/0'          the Lightning node;
                                                                           hash160 = profile id

Usage:
    python3 -I docs/scripts/recovery-code-keys.py english.txt CODE [CODE ...]

english.txt is BIP39's English wordlist (bitcoin/bips, bip-0039/english.txt, sha256
2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda). The code must be
canonical: upper case, nine blocks, dashes in place -- the dashes are part of what is hashed.
"""
import hashlib
import hmac
import sys

WORDLIST_SHA256 = "2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda"

# secp256k1
P = 2**256 - 2**32 - 977
N = 0xFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFEBAAEDCE6AF48A03BBFD25E8CD0364141
G = (0x79BE667EF9DCBBAC55A06295CE870B07029BFCDB2DCE28D959F2815B16F81798,
     0x483ADA7726A3C4655DA4FBFC0E1108A8FD17B448A68554199C47D08FFB10D4B8)


def point_add(a, b):
    if a is None:
        return b
    if b is None:
        return a
    if a[0] == b[0] and (a[1] + b[1]) % P == 0:
        return None
    if a == b:
        lam = 3 * a[0] * a[0] * pow(2 * a[1], -1, P) % P
    else:
        lam = (b[1] - a[1]) * pow(b[0] - a[0], -1, P) % P
    x = (lam * lam - a[0] - b[0]) % P
    return x, (lam * (a[0] - x) - a[1]) % P


def point_mul(k):
    result, addend = None, G
    while k:
        if k & 1:
            result = point_add(result, addend)
        addend = point_add(addend, addend)
        k >>= 1
    return result


def compressed(k):
    x, y = point_mul(k)
    return bytes([2 + (y & 1)]) + x.to_bytes(32, "big")


def root_key(code):
    return hmac.new(b"muun:rc", code.encode(), hashlib.sha256).digest()


def to_words(entropy, wordlist):
    checksum_bits = len(entropy) * 8 // 32
    bits = int.from_bytes(entropy, "big") << checksum_bits
    bits |= hashlib.sha256(entropy).digest()[0] >> (8 - checksum_bits)
    count = (len(entropy) * 8 + checksum_bits) // 11
    return [wordlist[(bits >> (11 * (count - 1 - i))) & 0x7FF] for i in range(count)]


def to_seed(words):
    return hashlib.pbkdf2_hmac("sha512", " ".join(words).encode(), b"mnemonic", 2048)


def derive(seed, path):
    digest = hmac.new(b"Bitcoin seed", seed, hashlib.sha512).digest()
    k, chain = int.from_bytes(digest[:32], "big"), digest[32:]
    for index in path:
        if index >= 2**31:
            data = b"\x00" + k.to_bytes(32, "big") + index.to_bytes(4, "big")
        else:
            data = compressed(k) + index.to_bytes(4, "big")
        digest = hmac.new(chain, data, hashlib.sha512).digest()
        k, chain = (int.from_bytes(digest[:32], "big") + k) % N, digest[32:]
    return k


H = 2**31


def main():
    if len(sys.argv) < 3:
        sys.exit(__doc__)
    with open(sys.argv[1], "rb") as f:
        raw = f.read()
    if hashlib.sha256(raw).hexdigest() != WORDLIST_SHA256:
        sys.exit("that is not BIP39's English wordlist")
    wordlist = raw.decode().split()
    for code in sys.argv[2:]:
        entropy = root_key(code)
        words = to_words(entropy, wordlist)
        seed = to_seed(words)
        nostr = derive(seed, [44 + H, 1237 + H, 0 + H, 0, 0])
        node = derive(seed, [50 + H, 0 + H])
        profile_id = hashlib.new("ripemd160", hashlib.sha256(compressed(node)).digest()).hexdigest()
        print(code)
        print("  root key   ", entropy.hex())
        print("  words      ", " ".join(words))
        print("  nostr key  ", nostr.to_bytes(32, "big").hex())
        print("  nostr pub  ", point_mul(nostr)[0].to_bytes(32, "big").hex())
        print("  node id    ", compressed(node).hex())
        print("  profile id ", profile_id)


if __name__ == "__main__":
    main()

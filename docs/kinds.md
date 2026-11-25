# Kinds

Every nostr kind in 30300–30449 that Dedun uses, and who allocated it. Giza, Bennu and
Dedun share one numbering, so **a new kind goes in this file before it goes in code**,
and the allocation is checked against the other two trees first.
[giza-into-dedun.md](./giza-into-dedun.md), Phase 11, asked for this register.

Checked against Bennu at `2db6dde5`, and Giza at `d0437d6c` as
[giza-into-dedun.md](./giza-into-dedun.md) measured it.

## The map

| range | meaning | allocated by | Dedun |
|---|---|---|---|
| 30300–30309 | Giza's, from before Dedun's fork (Bennu uses them) | Giza | not read |
| 30310–30316 | key ceremony (`DkgRitualEvents`) | shared | in use |
| 30320–30326, 30330, 30332 | FROST signing (`FrostSigningEvents`), key state 30326 | shared | in use |
| 30327–30328 | the member chronicle and its request | shared | in use |
| 30329 | subgroup birth certificate | shared | in use |
| 30331, 30333–30337 | group wallet statement, guarded wallet notice, member wallet certificate, request, key notice, policy | shared | in use |
| 30338 | group payout destination | **Dedun** | in use |
| 30339 | depositor statement | **Dedun** | in use |
| 30340–30349 | share refresh (30340–30345) and repair (30346–30349) | Giza | in use, at Giza's meaning |
| 30350–30366 | group Lightning wallet (`GroupLightningEvents`) | shared | in use |
| 30370–30377 | governed rooms | Giza | read only (`GizaProtocolEvents`) |
| 30383–30389 | free | **Dedun's range** | — |
| 30390–30393 | deposit attribution, coin assignment, transfer request, member transfer | **Dedun** | in use |
| 30394–30399 | contacts: record, amendment, depositor statement, claim, request, payout destination ([non-member-deposits.md](./non-member-deposits.md)) | **Dedun** | allocated 2026-11-25 |
| 30400–30412 | group Wavelength wallet (`GroupWavelengthEvents`) | **Dedun** | in use |

## Legacy numbers

Dedun's own kinds that moved out of Giza's way keep their old numbers for reading, for
good. An event signed under one keeps it ([MovedKinds.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/MovedKinds.kt)).
Dedun never reallocates them:

- 30340–30343, now 30390–30393;
- 30370–30382, now 30400–30412.

## Allocating a kind

1. Pick the lowest free number in Dedun's range, 30383–30412. When that is full, agree
   a new range with Giza and Bennu first.
2. Check the other two trees:

   ```bash
   grep -rhoE '\b30[34][0-9]{2}\b' --include=*.kt <tree> | sort -u
   ```

3. Add the row here, in the same commit as the plan that needs it.

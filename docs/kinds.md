# Kinds

Every nostr kind in 30300–30449 that Dedun uses, and who allocated it. Giza, Bennu and
Dedun share one numbering, so **a new kind goes in this file before it goes in code**,
and the allocation is checked against the other two trees first.
[giza-into-dedun.md](./giza-into-dedun.md), Phase 11, asked for this register.

Checked against Bennu at `2db6dde5`, Giza at `a5a802c5` and quartz 2.20.1, by
[giza-second-batch.md](./giza-second-batch.md), Phase 0. Giza took 30379–30416 after
`d0437d6c`, so Dedun's kinds there moved again, to 30420–30442 (that plan's Phase 4).

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
| 30370–30377 | governed rooms: statement, own commit, support, carrier, promotion offer, acceptance and handover, step-down (`GovernanceEvents`, `PromotionEvents`) | Giza | in use, at Giza's meaning; until K3 told from the legacy Wavelength numbers by shape (`GovernanceEvents.isGovernance`) |
| 30379–30388 | confederation key ceremony (30380 and 30383 retired, 30386 and 30388 reserved) | Giza | refused (`GizaProtocolEvents`); Dedun takes no confederations |
| 30390–30399 | confederation signing (30397 free) | Giza | refused (`GizaProtocolEvents`) |
| 30400–30401 | a group's direct messages, threshold ECDH (`GroupEcdhEvents`) | Giza | in use, at Giza's meaning, since [giza-second-batch.md](./giza-second-batch.md), Phase 6 |
| 30402–30403 | NIP-99 classified listings | NIP-99 | not used |
| 30410–30416 | a group inbox's key ceremony (`InboxKeyRitualEvents`) | Giza | in use, at Giza's meaning, since [giza-second-batch.md](./giza-second-batch.md), Phase 6 |
| 30420–30423 | deposit attribution, coin assignment, transfer request, member transfer | **Dedun** | in use |
| 30424–30429 | contacts: record, amendment, depositor statement, claim, request, payout destination ([non-member-deposits.md](./non-member-deposits.md)) | **Dedun** | in use |
| 30430–30442 | group Wavelength wallet (`GroupWavelengthEvents`) | **Dedun** | in use |
| 30443 | Marmot KeyPackage | Marmot | in use |

## Legacy numbers

Dedun's own kinds that moved out of Giza's way keep their old numbers for reading, for
good. An event signed under one keeps it ([MovedKinds.kt](../composeApp/src/commonMain/kotlin/com/machankura/dedun/compose/nostr/MovedKinds.kt)).
Dedun never reallocates them:

- 30340–30343, now 30420–30423;
- 30370–30382, now 30430–30442.

30390–30393, 30394–30399 and 30400–30412 were Dedun's numbers for a few days in November 2026.
No release wrote them, so they are not legacy numbers. They are Giza's now: Dedun refuses the
confederation's 30390–30399 like any of Giza's kinds it does not act on, and reads 30400–30401
and 30410–30412 at Giza's meanings since Phase 6 brought the group's direct messages and inbox.

## Allocating a kind

1. Dedun's block, 30420–30442, is full. Agree a new range with Giza and Bennu first, in
   all three registers.
2. Check the other two trees:

   ```bash
   grep -rhoE '\b30[34][0-9]{2}\b' --include=*.kt <tree> | sort -u
   ```

3. Add the row here, in the same commit as the plan that needs it.

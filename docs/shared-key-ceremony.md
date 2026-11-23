# The shared key ceremony

A group creates a `t`-of-`n` FROST key by running ChillDKG over its own chat. No
trusted dealer, no single device ever holding the whole key.

Implemented in `ChillDkgRitualManager`, on `fr.acinq.bitcoin.crypto.dkg.chill.ChillDKG`.

## Shape

The participant set is who the proposal `p`-tags, the member who opens the
ceremony coordinates it, and every protocol message travels on a pipeline chat
messages already use — a gift-wrapped rumor in a NIP-17 room, an MLS application
message in a Marmot one — so there is no second transport to operate.

A group's own ceremony runs in the NIP-17 room its members share, and there the
participant set and the room's membership are the same thing. A **subgroup's**
runs in its parent's Marmot room over a subset of it, which is why the set is the
p-tags rather than the room: see [subgroups.md](./subgroups.md).

The coordinator is a participant too, and ChillDKG treats it as untrusted: it
relays and aggregates but cannot learn secrets or bias the key. Being the room's
creator buys it no authority, only work.

| kind  | from        | carries                                              |
|-------|-------------|------------------------------------------------------|
| 30310 | coordinator | proposal — "let's make a t-of-n key"                 |
| 30311 | participant | host public key                                      |
| 30312 | participant | `pmsg1`, this device's contribution                  |
| 30313 | coordinator | `cmsg1`, everyone's contributions combined           |
| 30314 | participant | CertEq signature confirming the combined result      |
| 30315 | coordinator | `cmsg2`, the success certificate                     |
| 30316 | anyone      | failure — abort and blame                            |

Every step is a pure function of inputs the device has already stored, so there is
no long-lived in-memory session to lose. Each inbound message is persisted and
then the ritual is asked whether it can move; if the app dies mid-round it resumes
on the next message. `DkgSession` deliberately stores *inputs* — the randomness
and the received messages — rather than protocol state, which is what makes that
work.

**A ceremony cannot finish until every member takes part.** That is unusual for a
chat feature and drives most of the UI: the progress ladder names who it is
waiting on rather than showing a count, because "2 of 3" does not tell anyone whose
door to knock on.

## Joining is the only approval

The ritual is driven by arriving messages, which originally meant a relay
delivering an event to a phone in someone's pocket was enough to enrol its owner
in a group's permanent signing quorum. `acceptProposal` published the host key on
arrival; rounds 1 and 2 followed automatically.

It now publishes nothing on this device's behalf until its owner agrees to join,
and after that asks them nothing:

| step       | publishes                | asked?                                                                         |
|------------|--------------------------|--------------------------------------------------------------------------------|
| `HOST_KEY` | the host public key      | **yes**: joins the ceremony and fixes `n`. Nothing on the device can decide that for its owner |
| `ROUND_1`  | the key contribution     | no: it follows from having joined, once every host key is in                    |
| `ROUND_2`  | the CertEq signature     | no: the check is ChillDKG's, and a mismatch fails the ceremony whether or not anybody is looking |

Rounds 1 and 2 were gates of their own until
[automatic-key-ceremony.md](./automatic-key-ceremony.md). Neither was a decision
a person could add to. The participant set the round-1 screen showed was fixed
by the proposal before anyone joined, so the join screen shows it now. The
round-2 screen said itself that the device had already checked the combined
result; a coordinator substituting it is caught by the members' checks and the
CertEq signatures, with nobody asked (`CeremonyAcrossDevicesJvmTest`).

The coordinator's two aggregations were never gated. They relay other members'
already-published messages and disclose nothing of the coordinator's own.

The member who opens a ceremony is approved at creation -- starting one is
already the act of agreeing to be in it -- and is never asked about it.

The join gate returns rather than throwing. The ritual is not failing, it is
waiting on a person; everything received stays stored and it resumes on
approval. Without the round gates a tap no longer resumes a ritual, so
`resumeRunning` does, once per activation: a ceremony that holds every message
it needs and will be sent no more -- one an older build left waiting to be told
to contribute -- would otherwise never move.

> **`pendingApproval` must mirror the gate in `advance` exactly.** If they drift,
> the screen offers an approval that does nothing, or offers none while the ritual
> sits still. Neither produces an error.

Approval is recorded as `hostKeyApprovedAt` on `DkgSession`, plus
`approvalRequestedThrough` so the chat line asking for it is written once.
`round1ApprovedAt` and `round2ApprovedAt` stay in the schema, unread, and
`DkgApprovalStep` keeps `ROUND_1` and `ROUND_2`, so rows written by older builds
keep decoding and their request lines keep rendering.

Once everyone has joined, the only thing a ceremony can wait on is a device that
is not answering. After a day without a new message the ritual screen names who
it is waiting on and since when, with **Abandon** beside it. Nothing abandons on
its own; whether a day is too long is the group's call.

## Faults are values, not exceptions

ChillDKG reports a faulty participant in the `ChilldkgFault` field of each result,
because a faulty participant is a normal outcome of a DKG rather than a bug.

This ritual has one response to all of them — the key is unusable, the session
dies, the group is told — so `raiseIfFaulty` turns them into an exception carrying
the culprit and lets them join `advance`'s single failure path. The gain is the
failure text: "ChillDKG round 2 failed: a participant is faulty (participant 3)"
rather than whatever `e.message` happened to hold. On a failed DKG, which
participant to blame is the only actionable thing there is.

## The transcript

Every protocol message becomes a line in the group's chat naming the member whose
device sent it, plus the three that bracket them: started, complete, abandoned.

These rows are **not anybody's words**: no `giftWrapPayloadId`, no event behind
them, nothing sent to say them. Each device writes its own from messages it
already received, so they cost no traffic and cannot disagree with the ritual they
describe. They render as system lines rather than bubbles — attributing "a shared
key ceremony started" to the coordinator would read as something they said.

Wording describes what a step accomplishes, not what it is called. "sent their
contribution to the key" is useful in a group chat; "sent pmsg1" is not, and the
protocol names are on the shared-key screen for anyone who wants them.

**Idempotency is by construction, not de-duplication.** `ChatMessage` has no key
to make a second insert a no-op — its id is autogenerated — while ritual messages
arrive repeatedly: relays redeliver, and `replayStoredMessages` feeds the whole
backlog through `record()` again on every resume. So every announce is guarded by
reading the row it is about to write over. `DkgParticipantMessage` absorbs the
redelivery itself, being keyed on `(sessionId, participantPublicKey, kind)`; a chat
row cannot.

Request lines carry their step in the message type — one type per step, not one
type for all three. The type is the only thing a transcript keeps: a line drawn
days later has no session to ask what was being requested. Sharing one type left
every request wearing the same icon.

Whether a request was answered is read from the transcript rather than the
session: approving is the only thing that causes the step to be published, and
publishing writes an authored line. That keeps a room that has run more than one
ceremony correct, since `ChatMessage` has no session id to disambiguate with.

## Ordering

ChillDKG has no session-params object to agree on out of band. Every step takes
the host public keys and the threshold and hashes them into the session identity,
so **a group that orders its participants differently on different devices does
not get a weaker key — it gets no key.**

Each device derives that order independently by sorting the host keys bytewise,
and orders each round's messages by their sender's host key to match. Sorting is
the only ordering every device can arrive at without being told. Nothing in the
protocol checks this, so `ChillDkgRitualOrderingTest` does.

## Where a ceremony starts, and how one ends early

A ceremony is started in exactly three places, each of which knows what it is
for: group creation (`SelectChatRoomTypeViewModel`) for a new group's own key,
the subgroup picker (`SelectSubgroupAdminsViewModel`) for a subgroup's, and
`CreateSharedKeyScreen` for a new group made from the members of an existing
NIP-17 room. `CeremonyEntryPointsJvmTest` counts them.

The same people always share one NIP-17 room, since its id is derived from
them, so a second group they make is a second ceremony in that room. The room
is where each group's ceremony is held, not the group, and every ceremony after
the room's first carries a name of its own. The room's details list the groups
made in it. See [nip17-multiple-ceremonies.md](./nip17-multiple-ceremonies.md). `DkgRitualScreen` shows one ceremony,
named by its route, and never starts one: it used to, from the same button slot
its approvals used, and a member coming back from approving a subgroup's
ceremony opened a second, whole-room one in the parent's room. A Marmot room
cannot hold a ceremony of its own at all -- `proposeRitual` refuses and
`acceptProposal` drops one -- since its id is derived from the key it already
has. See [ceremony-creation.md](./ceremony-creation.md).

A failed ceremony is tried again from the screen that starts its kind: the
creation screen for a group's own, the subgroup picker, filled in from the one
that failed, for a subgroup's.

Any participant can **abandon** a running ceremony from its screen, which
broadcasts the same 30316 a fault does and ends it for everyone; nobody outside
the ceremony can, and a 30316 from a non-participant is ignored. ChillDKG has no
"no" short of this, and without it a ceremony opened by mistake could only be
left running.

The kinds above travel in both transports: gift wraps for a group's own
ceremony, Marmot inner events in the parent's room for a subgroup's. In the
second, 30310-30312 once collided with document kinds that shared the numbers,
and `ChatMessage` now recognises a ceremony's kinds before any content kind.

## After it completes

The group has a threshold public key; each device keeps its own share, restorable
from that member's wallet backup and nobody else's.

From there the coordinator's device creates the group's admin room on its own,
once the group has signed its key state -- see `CeremonyFollowThrough` and
[automatic-key-ceremony.md](./automatic-key-ceremony.md), Phases 6 and 7. The
room is a Marmot group whose id is derived from the shared key. See
[shared-key-derivation.md](./shared-key-derivation.md) for how, and
[marmot-membership.md](./marmot-membership.md) for how members are added to it.

It is named `&` and then the group's name — `&Ekklesia` for a group called
Ekklesia. Every Marmot room on a device carries that mark and no NIP-17 room does,
which is the only thing on a room list that says which of the two a row is. The
rule is one function, `MarmotGroupName.of`, applied where a room is minted rather
than where it is drawn: the name is baked into the epoch-0 `MarmotGroupData` every
member is welcomed with, so a name added at display time would be a name this
device alone could see. It is applied again where a Welcome is joined, so a room
another client named is called the same way. It replaces `Ekklesia (#admins)`, which
marked only the admin room, left every subgroup unmarked, and sorted nowhere near the
group it belonged to. The mark was `#` until `#` turned out to read as a hashtag;
[group-name-prefix.md](./group-name-prefix.md) has that change and the rooms it
renamed.

## Known gaps

- No ceremony has been run on a physical device.
- A member invited to the admin room whose Welcome never goes out is
  indistinguishable, from the coordinator's side, from one who joined.
- The ritual's chat lines are `3n + 4` per ceremony, plus one request line on
  each device that had to be asked to join — 19 or 20 lines for five members.
  Compact, but they dominate a transcript while a ceremony runs.
- A ceremony moves only while its members' apps are in the foreground. Nobody
  has to tap anything after joining, but each device still has to be opened.

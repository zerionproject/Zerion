# Binding channel comment and reaction authors to one post-quantum key

From 3.0.18 (channel protocol version 3) every Android device binds, per
channel, the Ed25519 key of each comment and reaction author to one ML-DSA-65
key, and the channel owner countersigns those bindings in every pull reply it
sends to a version 3 subscriber. This document records why, the rules every
implementation follows, the wire changes, test vectors and the rollout. The
channel wire format itself is in
[CHANNELS_WIRE_PROTOCOL.md](../wire/CHANNELS_WIRE_PROTOCOL.md).

Code: `ChannelAuthorBinding` (key hash, reply digest, the binding rules),
`ChannelAuthorPins` (stored bindings), `ChannelManagerImpl` (where the rules
are applied), `ChannelPullProtocol` (version 3 replies). Tests:
`ChannelAuthorBindingTest`, `ChannelAuthorPinsTest`,
`ChannelAuthorKeyBindingTest`, `ChannelAuthorBindingReplyTest`.

## What was wrong

Members sign their comments, reactions, announcements, applications and
approval checks with a hybrid signature: a 64-byte Ed25519 signature and a
3309-byte ML-DSA-65 signature over the same labelled input, which names the
channel. Both halves must verify. Each message carries the signer's Ed25519
key (`ed`) and ML-DSA-65 key (`ml`). Up to 3.0.17 the verifier took `ml` from
the same message and checked nothing else about it. Each half of the
signature is bound to its own key (Ed25519 hashes its public key into the
signature, ML-DSA-65 hashes `tr = H(pk)` into it), but nothing tied the two
keys together. Whoever can forge Ed25519 signatures for a known `ed` can pair
them with an ML-DSA-65 key of their own and produce a hybrid signature that
verifies. Authorship of these items therefore rested on Ed25519 alone.

What the Ed25519 key decides in a channel:

- who wrote a comment: the name it carries, the first 8 hex digits shown, and
  the owner mark for the channel's own key;
- whether a signer is known (the channel key, editors, approved applicants,
  members the owner marked as trusted) or anonymous, and so its retention
  class, its per-signer ceilings and its write allowance;
- the reaction slot `(post, signer)`, where a newer reaction replaces the
  held one;
- bans and trust marks;
- the announced name of a member, which any announcement signed under the
  key replaces;
- the application of a member, which a new application with a new ephemeral
  key replaces, even an approved one;
- the approval check that hands out the wrapped capability.

With Ed25519 broken an attacker could therefore post as the owner (with the
owner mark), as an editor or as any author whose key it had seen, replace an
author's reaction, rename an announced member, push a trusted member's items
out through the per-signer ceilings and spend its allowance, and displace an
approved member's application with one carrying the attacker's ephemeral key;
if the owner then approved the "renewed" application, the attacker received
the capability of a private channel. It could not forge posts, manifests,
delegation certificates or tombstones: those are verified against the
channel's hybrid key, whose ML-DSA-65 half the channel id commits to.

### Exposure over time

- Member keys are long-lived. A member signs in a channel with a key derived
  from its identity private key and the channel id (`ChannelMemberKeys`). It
  is the same key every time, also after leaving and joining again, and it is
  never rotated. The channel key and editors' keys live as long as the
  channel.
- Keys are widely held. Every pull reply carries, for every retained comment
  and reaction, the author's `ed` and `ml` to every puller: anyone who has the
  link of a public channel, every capability holder of a private one. The
  owner's device also keeps every announced name (up to 4096) and every
  application with their keys; every subscriber keeps the items on the posts
  it shows.
- Items live long. Intake is limited to items at most 48 hours old and 2
  hours ahead, but a held item has no age limit: it stays as long as its post
  is held and the ceilings keep it, which for a post without a time limit can
  be months.
- Injection is open. Anyone who can reach the owner's onion (anyone with a
  public channel's link, a capability holder of a private one) can submit
  items, and the owner stores and serves what it accepts to every subscriber.
  An attacker that can also break the classical authentication of the onion
  service in transit can add items to replies directly.

### Harvest now, forge later

A signature is forged at the moment the attacker has a cryptographically
relevant quantum computer, not earlier, so recorded traffic does not let
anyone forge today. What matters is how long a forgery would be accepted and
whether old keys stay valid. Both answers were unfavourable: author keys never
expire and are published to every puller, so a key collected today can be
attacked whenever such a computer exists (Shor's algorithm recovers an
Ed25519 private key from the public key alone), and the 48-hour freshness
window is no obstacle because the forger signs a fresh timestamp. A forged
item was then kept and served like any other until retention removed it.
Collecting author keys now is the harvesting step.

## Decision

Bind, do not replace. The hybrid signature stays exactly as it is: the same
labels, the same signed inputs, the same 3373-byte layout, the same library
(Bouncy Castle through `CryptoComponent.hybridSign` and
`verifyHybridSignature`). On top of it:

1. every device binds each author's Ed25519 key to one ML-DSA-65 key per
   channel and refuses any item whose ML-DSA-65 key differs, whatever version
   the peer runs;
2. the owner countersigns the bindings each version 3 pull reply carries, with
   the manifest signature it already makes for every reply;
3. a subscriber that has received a version 3 reply refuses to take comments
   and reactions from replies of an earlier version.

No new primitive is used and no item format changes. The only new signed
structure is the version 3 manifest signature, under a label of its own.

A separate binding record signed by both halves is not added, because every
member item already is one: the first item a member key sends in a channel is
a hybrid signature by both halves over a labelled input that names the
channel. Where the protocol has an owner approval it is used: an editor's
certificate is signed by the channel key over both of the editor's keys, the
application the owner approves names both of the applicant's keys, and each
version 3 reply is signed by the owner over every binding it carries.

It ships in 3.0.18 rather than as a later migration because nothing an honest
client sends changes and no honest client is refused: member keys are derived
deterministically, so a client of any version always presents the same
ML-DSA-65 key for its Ed25519 key in a channel (clients up to 3.0.14 sign with
the account's hybrid identity key, which is generated once). iOS has its own
channel implementation with a different, list-based wire format that carries
no ML-DSA-65 key and has never interoperated with Android channels, so no
iOS change is needed to keep anything working; an iOS implementation of this
wire format must implement these rules from the start.

## Rules

### Key hash and binding

```
K(M) = H("org.zerionproject/CHANNEL_AUTHOR_KEY", M)
```

`H` is `CryptoComponent.hash`: BLAKE2b-256 over `uint32(len(label)) || label`
followed by `uint32(len(x)) || x` for each input. `M` is a 1952-byte ML-DSA-65
public key.

On a device, the binding of an author key `E` in channel `C` is, in this
order:

1. if `E` is the channel's Ed25519 key: `K` of the channel's ML-DSA-65 key,
   which the channel id commits to;
2. the binding stored for `E` (a pin);
3. if delegation certificates (active or retired) name `E`: `K` of the
   ML-DSA-65 key they name; certificates that name different keys bind `E` to
   no key, so every item of `E` is refused;
4. otherwise none: `E` is new.

Rules:

- An item whose `K(ml)` differs from the binding of its `ed` is refused
  before its signature is checked.
- When an item of a new `E` is taken in (its hybrid signature verified and,
  on the owner's device, the item admitted), its `K(ml)` becomes the pin of
  `E`. The pin is written before the item.
- A pin is never replaced. There is no key rotation in version 3.

Where the rules apply:

| Device | Applied to |
| --- | --- |
| Owner | every comment, reaction, announcement, application and approval check it receives; issuing an editor certificate (a key bound to another ML-DSA-65 key cannot be certified) |
| Subscriber | every comment and reaction of every pull reply; its own key, pinned when the owner acknowledges its comment or reaction |

A refused comment, reaction, announcement or application is acknowledged with
`ok = false`; a refused approval check is answered `DENIED` without envelope.

### Records held at the update

The first time a device checks a binding in a channel it pins, first record
wins, the keys of the records it already holds: certificates, then (owner)
applications and announced subscribers, then comments, then reactions. The
channel key is never pinned (rule 1 binds it). Records held from earlier
releases were accepted before anyone could forge Ed25519, so they carry the
authors' genuine keys. A marker row records that this was done; nothing is
written for a channel that holds no records.

### Storage

Settings namespace `zerion-channels-author-pins:<channelId hex>`:

| Key | Value |
| --- | --- |
| `hex(E)` (64 characters) | `<counter>:<hex K(M)>` |
| `n` | next counter |
| `s` | `1` once the held records were pinned |
| `v` | `3` once a version 3 reply of the channel was taken in (subscribers) |

At most 4096 pins per channel are kept
(`MAX_PINNED_AUTHORS_PER_CHANNEL`), checked every 256 new pins: past that,
the oldest pins of authors the device holds no record of (no comment, no
reaction and, on the owner's device, no announced name and no application)
are dropped. Pins of authors with a held record are never dropped. A row that
does not parse counts as absent. Leaving or deleting the channel deletes the
namespace.

### Owner countersignature: version 3 replies

A pull request with `v >= 3` gets a reply with `v = 3` whose manifest
`signature` is

```
hybridSign("org.zerionproject/CHANNEL_MANIFEST_V3",
           manifestSignedInput || D)
```

where `manifestSignedInput` is the byte layout of section 3 of the wire
document, unchanged, and

```
D = H("org.zerionproject/CHANNEL_AUTHOR_BINDINGS",
      uint32(count) || b_1 || ... || b_count)
b_i = ed (32) || K(ml) (32)
```

over the distinct pairs of every comment and reaction entry in the reply,
sorted in ascending order as unsigned bytes (64 bytes each). A version 3
reply carries only entries with a 32-byte `ed` and a 1952-byte `ml` (and, for
comments, a valid body and name), and the decoder drops any other entry, so
both sides compute `D` over the same entries. The subscriber computes `D`
from the entries it decoded and verifies the manifest signature under the
version 3 label. An entry under a binding the owner did not sign, a changed
key, or the removal of every entry of one author changes `D` and fails the
whole reply; removing one of several entries of the same author does not. A
request with `v = 2` gets
the reply of 3.0.15 to 3.0.17 (label `org.zerionproject/CHANNEL_MANIFEST`,
no `D`, `v = 2`); a request without `v` gets the reply of 3.0.14 and earlier.

The countersignature costs no signing operation: the owner already signs a
fresh manifest for every reply.

### Downgrade protection

- The binding rules do not depend on any version: a request or reply of
  version 2, or one without a version, is checked exactly like a version 3
  one, so speaking an older version relaxes nothing.
- A subscriber that has taken in a version 3 reply of a channel records it
  (`v` above) and from then on takes comments and reactions of that channel
  only from version 3 replies. A reply of an earlier version still updates
  the manifest and the posts, which are signed by the channel key; its
  comment and reaction cursors are not advanced, so the items it would have
  carried arrive with the next version 3 reply. Stripping `v` from a reply in
  transit fails it, because the signature does not verify under the version 2
  label; lowering or removing `v` in a request makes the owner answer in an
  earlier version, which such a subscriber takes no items from.
- The two manifest labels differ, so a version 3 signature is never valid as
  a version 2 one, nor the reverse.

### Malformed input

- Requests: `ed` must be 32 bytes, `ml` 1952 bytes and `sig` 3373 bytes
  before anything is hashed, parsed, looked up or verified; anything else is
  refused and writes nothing.
- Replies: comment and reaction entries with an `ed` other than 32 bytes or an
  `ml` other than 1952 bytes are dropped by the decoder and are not part of
  `D`. The existing limits stay: 4096 comments, 25600 reactions, 16 MiB per
  reply.
- Every list stays bounded: pins as above, the reply digest by the reply's
  own entry limits.

### Replay

- Items keep their existing replay rules: a comment id already held is
  accepted only if identical, the owner takes in items at most 48 hours old
  and 2 hours ahead, and a reaction never replaces a held one with an equal
  or later timestamp. A replayed genuine item carries the bound key and
  changes nothing; the same id or slot under another ML-DSA-65 key is a
  forgery and is refused by the binding.
- A recorded version 3 reply can be replayed, but not altered: it carries only
  bindings the owner signed. (A whole recorded reply, of any version, can
  still be replayed to roll a subscriber's item set back until its next
  pull; that is unchanged and cannot introduce a binding.)
- Pins are never replaced, so no replayed record can move a key to another
  ML-DSA-65 key.

## Key lifecycle

- Creation: unchanged. Member keys are derived per channel
  (`ChannelMemberKeys`), the channel key is generated with the channel, an
  editor's key is its member key.
- Binding: by the channel id (channel key), by the owner's certificate
  (editors), otherwise by the first verified item a device takes in. On the
  owner's device that is the member's own first request: a member key is
  published nowhere before it, except when a member copies its key to the
  owner for an editor certificate, which then binds it.
- Rotation: none. No honest client ever presents a second ML-DSA-65 key for
  its Ed25519 key, and a different ML-DSA-65 key under a bound Ed25519 key is
  refused on every path, including one signed by both halves of the new pair.
  A member that needs a new key takes a new Ed25519 key with it and is a new
  author in the channel. A rotation record is reserved below and deliberately
  not accepted: no client can produce one, and accepting it would add the
  only path by which a binding could change.
- Removal: leaving or deleting the channel removes its bindings. A ban keeps
  the binding. Past 4096 pins, pins of authors the device holds nothing of
  are dropped as described under Storage.

### Reserved: rotation record

If a later version needs to change an author's ML-DSA-65 key while keeping
its Ed25519 key, it will use a record of its own type and protocol version 4,
which version 3 devices do not accept:

```
signedInput = channelId (32) || ed (32) || K(old) (32) || new (1952)
              || int64 n || int64 timestampHourMs
label       = "org.zerionproject/CHANNEL_AUTHOR_KEY_ROTATION"
sigOld      = hybrid signature by (ed, old) over signedInput
sigNew      = hybrid signature by (ed, new) over signedInput
```

It is valid only if the binding of `ed` is `K(old)` and `n` is one more than
the rotation number stored with the pin, so an older record cannot be
replayed to return to a superseded key. The owner must carry it in replies
so that subscribers can follow it, and items signed under the old key after
the rotation must be refused. None of this is implemented.

## Compatibility

| Owner | Subscriber | Result |
| --- | --- | --- |
| 3.0.18 | 3.0.18 | Version 3 replies: countersigned bindings, downgrade rule |
| 3.0.18 | 3.0.15 to 3.0.17 | Version 2 replies exactly as before; the owner binds the old client's items, which always carry the same key, so none is refused |
| 3.0.18 | 3.0.14 and earlier | Replies without a version, as before; the same binding applies to their items |
| 3.0.15 to 3.0.17 | 3.0.18 | The old owner treats `v = 3` as 2 and answers in version 2; the subscriber binds authors on first sight and refuses items under a bound key that carry another ML-DSA-65 key; the old owner itself binds nothing |
| any Android | iOS | Not interoperable, before or after |

A channel can hold members of all these versions at once. Older subscribers
of a 3.0.18 owner see no change; newer subscribers of an older owner keep the
bindings they formed.

## Cost

- Wire: no byte is added to items or requests; a reply carries the same
  3373-byte manifest signature, with `v = 3` instead of 2.
- CPU: no additional ML-DSA-65 or Ed25519 operation anywhere. Added are one
  BLAKE2b-256 over a 1952-byte key per checked item and per distinct author
  in a reply, and a single-row settings read per author seen in a batch.
  Measured on a desktop JVM (Intel i7-13650HX): 12.5 microseconds per key
  hash, 6.4 ms for `D` over 512 distinct authors (the most a full reply of
  the ceilings can carry), against 0.5 to 1.6 ms for one hybrid verification
  and 1.5 to 2.0 ms for one hybrid signature. On Android the same Java code
  typically runs several times slower; this was not measured on a phone.
- Storage: one settings row of about 135 bytes per bound author, at most
  4096 per channel beyond those protected by held records (about 550 KB);
  a typical channel needs a few KB.

## Test vectors

Generated by `ChannelMemberKeys.derive` and `ChannelAuthorBinding` and checked
in `ChannelAuthorBindingTest`; the hashes were also recomputed with an
independent BLAKE2b implementation.

Member key 1: identity private key `000102...1f` (bytes 0x00 to 0x1f), channel
id `202122...3f` (bytes 0x20 to 0x3f).

```
ed_1     ca2208a2b22967b579b72957876612bbe72a203ed530f1a6c9cd63a11b1ba7fa
ml_1     starts 2158cc959ccf66d1209dd33599ad24c9880ac47eb978c646b952137c47bbc681 (1952 bytes)
K(ml_1)  4953c72e0ed68061d9981c08d27f84a054066e5908beef404a3733e540c0729e
```

Member key 2: identity private key `404142...5f`, the same channel id.

```
ed_2     ea7851dc112fa8bf851d95037f03d0be25cb8f17f4ab15dfa5b3e5afa4f3089b
ml_2     starts 34bd73851d8c6edc9003babea44942d5b77cf6c1b6bf9e3d0cb2c09d290ab4ee (1952 bytes)
K(ml_2)  83dd7d5e191a41e298a5c37c19d726da42bcbf45aec9f7389253544c3ad8fa87
```

Reply digests:

```
no entries
  input   00000000
  D       4d4b12e1b8e5ab2db452a2924b4d6e94fca69644d828d7cd9ded8b8b5049e095

one comment by key 1
  input   00000001
          ca2208a2b22967b579b72957876612bbe72a203ed530f1a6c9cd63a11b1ba7fa
          4953c72e0ed68061d9981c08d27f84a054066e5908beef404a3733e540c0729e
  D       01e58b0b73e5466433d7915907197fd501174ac87c1a6df385ccf00b82de4e05

a reaction by key 2 and a comment by key 1, in any order and with repeats
  input   00000002
          ca2208a2b22967b579b72957876612bbe72a203ed530f1a6c9cd63a11b1ba7fa
          4953c72e0ed68061d9981c08d27f84a054066e5908beef404a3733e540c0729e
          ea7851dc112fa8bf851d95037f03d0be25cb8f17f4ab15dfa5b3e5afa4f3089b
          83dd7d5e191a41e298a5c37c19d726da42bcbf45aec9f7389253544c3ad8fa87
  D       0b822b98e8bd968d0c3972b64435cd5385dfcb05b41b933aefd0c1db32bdf2ff
```

The member key derivation is described in section 5 of the wire document. The
ML-DSA-65 half is the FIPS 204 key generated from the 32-byte seed, so `ml_1`
and `ml_2` follow from the inputs. Signatures have no fixed vector: ML-DSA-65
signing is randomized. A hybrid signature is the 64-byte Ed25519 signature
followed by the 3309-byte ML-DSA-65 signature, both over
`uint32(len(label)) || label || uint32(len(input)) || input`.

## Rollout

- 3.0.18 ships the rules with no setting and nothing to migrate by hand. An
  owner on 3.0.18 binds from the first request it handles after the update
  (pinning the records it holds first), and a subscriber from its first
  pull.
- Nothing visible changes for honest members. A refused forgery is not
  stored and not shown.
- Channels whose owner stays on 3.0.15 to 3.0.17 keep accepting items under
  any ML-DSA-65 key on the owner's device; subscribers on 3.0.18 still refuse
  those under a key they have bound.
- A subscriber that has received a version 3 reply takes no comments or
  reactions while its owner runs an earlier release again (for example after
  moving the account to a device with an older version); posts keep
  arriving.
- An iOS implementation of the Android channel wire format must implement
  version 3 from its first release.

## Limits

- A key is bound by the first item a device verifies for it. On the owner's
  device that is the member's own first request. A subscriber binds an author
  on first sight: countersigned by an owner on 3.0.18, on trust from an owner
  on 3.0.15 to 3.0.17, which binds nothing and may hold a forged item; a
  subscriber that joins such a channel after a forgery may bind the forged
  key.
- The owner's countersignature never replaces a binding a subscriber holds,
  but a dishonest owner able to forge Ed25519 can post as an author no
  subscriber has bound yet.
- Past 4096 pins per channel, the oldest pins of authors a device holds
  nothing of are dropped; such an author can be bound anew if it appears
  again.
- The Ed25519 key is still the author's name in the channel (bans, trust
  marks, the 8 hex digits shown); that is safe only because each name maps to
  one ML-DSA-65 key.
- Channel confidentiality is unchanged and classical: the Tor onion-service
  circuit and AES-256-GCM.

# Author format 2: an AuthorId that names both identity keys

Status: specified, not active. No release emits format 2. Activation needs
every implementation that must read an author (Android, iOS) to parse format 2
first; see "Activation".

## Why

An author is encoded as the list `[formatVersion, name, publicKey]` and named
by

```
AuthorId = H("org.zerionproject.core/AUTHOR_ID", u32(1), name, ed25519PublicKey)
```

(`AuthorFactoryImpl`; `H` is `CryptoComponent.hash`, BLAKE2b-256 over the
uint32-length-prefixed label and inputs). The id commits to the Ed25519 key
only. The account's ML-DSA-65 key is an attribute that each protocol learns on
its own: it is authenticated with a hybrid signature at link pairing and stored
with the contact, pinned for GroupTr members and committed into channel ids,
but where a protocol takes it from the same message it authenticates (channel
comments and reactions), a hybrid signature rests on Ed25519 alone. The
version 2 safety number covers both keys of a contact (see
SECURITY_CLAIMS.md), which closes the gap for the people comparing numbers, but
not for software that names an identity by its AuthorId.

## Format 2

Encoding:

```
[2, name, ed25519PublicKey, mlDsa65PublicKey]
```

| Element | Type | Rule |
| --- | --- | --- |
| formatVersion | integer | 2 |
| name | string | 1 to `MAX_AUTHOR_NAME_LENGTH` bytes of UTF-8 |
| ed25519PublicKey | raw | as format 1, parsed by the signature key parser |
| mlDsa65PublicKey | raw | exactly `ML_DSA_65_PUBLIC_KEY_BYTES` (1952) |

Identifier:

```
AuthorId = H("org.zerionproject.core/AUTHOR_ID", u32(2), name,
             ed25519PublicKey, mlDsa65PublicKey)
```

The format version is hashed, so a format 1 and a format 2 author never share
an id, and an id cannot be moved from one format to the other.

Parsing: a format 2 list of any size other than four, or with an ML-DSA key of
another length, is malformed. A format 1 list keeps its rules (three elements).

Wherever a format 2 author is checked against an ML-DSA key from another
source (the hybrid-signed key in the contact record at pairing, a GroupTr
member key, a channel author key) the two must be equal byte for byte, or the
record is refused. Hybrid signatures by a format 2 author are verified with the
ML-DSA key from the author itself, never with a key carried next to it.

## Existing identities

Every existing account keeps its format 1 author and its id for ever: the id
is stored by each contact, each group membership and each signed message, and
replacing it would be a new identity that every contact must pair with again.
Format 1 stays valid and is parsed by every release. Only accounts created
after activation are format 2.

## Activation

1. **Readiness release.** Every implementation parses format 2, stores it,
   re-encodes it unchanged wherever it forwards an author (sharing, group
   invitations, member lists), and checks the ML-DSA key against other sources
   as above. On Android this touches `Author` (an optional ML-DSA key),
   `AuthorFactory` and `ClientHelper.parseAndValidateAuthor` / `toList`, the
   database rows that rebuild authors (local authors and contacts, whose ML-DSA
   key is already stored), and the places that build author lists with a
   literal format version (for example `GroupTrManagerImpl`). Nothing emits
   format 2 yet.
2. **Flag day.** Once the readiness release is the oldest version in use on
   Android and iOS, new accounts are created as format 2.

Before the flag day, a format 2 author would reach older peers as follows:

- **Android 3.0.14 and earlier** reject a format 2 author list as malformed
  (`parseAndValidateAuthor` accepts format 1 only). Pairing with such a peer
  fails at the contact exchange, visibly, on both sides: no contact is added.
- **iOS** was not checked for this document; how it treats a format 2 author
  must be established on the iOS side before the flag day.
- In a group that includes an older Android peer, the format 2 member's messages are
  refused as invalid by that peer without a message to its user. This is the
  silent case the flag day exists to avoid; it is why no release may create a
  format 2 account before every peer parses format 2.

## Not covered

- An existing format 1 account gains nothing: its id still names its Ed25519
  key only, and its ML-DSA key stays bound per protocol as today.
- The safety number does not depend on the author format; version 2 already
  covers both keys for format 1 and format 2 contacts.

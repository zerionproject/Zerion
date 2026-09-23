# Voice memo format 2

A voice memo in a private conversation is a text message. Both peers must
produce and accept the same bytes, so this document is the reference for
every implementation.

## Text forms

- Short memo: `[VOICE:<durationMs>:<base64 payload>]`
- Long memo, split into parts sent as consecutive messages:
  `[VMP:1:<memoId>:<seq>:<total>:<durationMs>:<slice>]`, where `memoId` is
  16 lowercase hex characters, `seq` counts from 0, `total` is at most 24, and
  the slices concatenate to the base64 payload.

Android writes base64 without padding; padded input is accepted.

## Payload

```
version(1) = 2
iv(12)
wrappedKey(80) = salt(32) || AES-256-GCM(wrapKey, iv, sessionKey)(48)
chunkCount(u32)
{ length(u32) || ciphertext || tag(16) } * chunkCount
durationMs(u32)
globalMAC(16)
```

Integers are big-endian. `sessionKey` is a random 32-byte AES key. Chunk `n`
(mu-law audio at 8 kHz, at most 4096 bytes before sealing) is sealed with
AES-256-GCM under `sessionKey` and the nonce `iv` with its last four bytes
XORed with `n`. The global MAC is the tag of an empty message under the next
counter value with the chunk count and duration appended to the associated
data, as in format 1.

## Wrap key

The wrap key is derived from the secret both peers share since pairing, the
same root key the ZWF session keys are derived from, with the key derivation
function every other Zerion key uses (`CryptoComponent.deriveKey`):

```
wrapKey = deriveKey("org.zerionproject.voice/MEMO_WRAP_KEY", rootKey, salt)
```

`salt` is 32 random bytes chosen per memo and stored in the first 32 bytes of
the wrapped-key field. Nothing in the payload opens the session key; the
recording device and the playing device derive the same wrap key from the
salt, and a device that lost the pairing secret cannot play its memos.

## Associated data

Every chunk and the global MAC carry, as associated data:

```
[2] || groupId(32) || timestamp(8) || senderAuthorId(32) || recipientAuthorId(32)
```

- `groupId` is the conversation's messaging group id, as before.
- `timestamp` is the private message's timestamp. The recorder fixes it when
  the audio is sealed and the message is stored with exactly that value.
- `senderAuthorId` is the author id of the device that recorded the memo and
  `recipientAuthorId` that of the contact it was sent to. The playing device
  fills them from its own view of the conversation, so a memo verifies only
  in the direction it was recorded in.

A memo therefore verifies only as the one message it was recorded for. It
cannot be re-sent under a new timestamp, reflected back to its author, or
moved to another conversation.

## Versioning

Format 2 ships with messaging client minor version 7
(`MessagingManager.VOICE_MEMO_V2_MIN_VERSION`). A sender refuses to record a
memo for a peer whose messaging client is older and tells the user the
contact's app needs an update. A receiver refuses a memo whose payload
version byte is not 2, in both the short form and the first part of a long
memo (later parts carry no version byte), over the sync path and the mesh
path alike; the message is treated as invalid and is not stored.

Memos stored before the change carry version 1: the wrap key in clear in the
first 32 bytes of the wrapped-key field and associated data of
`[1] || groupId`. Android still opens those for playback of existing history
and never produces them.

## iOS port requirement

Until the iOS client implements this document it cannot exchange voice
memos with Android 3.0.12 and later: it advertises messaging minor version 6
or lower, so Android refuses to record memos for it, and its format 1 memos
are refused on receipt. The port consists of:

1. `VoiceMemoCodec.swift`: write version byte 2; put a random 32-byte salt in
   the first 32 bytes of the wrapped-key field instead of the wrap key; derive
   the wrap key with the client's `deriveKey` equivalent from the contact's
   pairing root key, the label above and the salt; build the associated data
   as described with the message timestamp and the two author ids; refuse to
   decode any version but 2, except for memos already stored locally.
2. Fix the message timestamp before sealing and store the private message
   with that timestamp.
3. Advertise messaging client minor version 7 and refuse to record a memo for
   a peer below 7.
4. In the private message validator, refuse incoming memo text whose payload
   version byte is not 2, checking the short form and part 0 of the long
   form, on the sync and mesh receive paths.

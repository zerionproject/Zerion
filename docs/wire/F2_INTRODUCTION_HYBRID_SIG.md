# F-2: Introduction Protocol - Hybrid Ed25519 + ML-DSA-65 Signatures

> **Shipped; updated to the current Android code (3.0.x).** The hybrid
> Ed25519 + ML-DSA-65 introduction signatures described here are the
> production wire format. The downgrade fallback has been removed: Android
> signs and accepts only hybrid signatures, and an introduction also requires
> ML-KEM-768 keys and a KEM ciphertext. The v1.5 legacy-peer rows are retained
> for history (annotated *historical*); those combinations no longer complete.
>
> Since 3.0.15 contacts added by introduction are stored with
> `postQuantum=true`: their root key includes ML-KEM-768 secrets and AUTH is
> hybrid-signed. Contacts introduced by 3.0.14 and earlier keep the
> `postQuantum=false` flag they were stored with, so the app still labels them
> "Classical Security".

iOS-side parity for the Zerion introduction protocol (originally landed for
v1.6; shipped and current as of v2.0.x). Android implementation: commit
`11f0e95` (dev + master).

## TL;DR
The introduction protocol's `AuthMessage` signs the AUTH nonce with a **hybrid Ed25519 + ML-DSA-65** key. Each side advertises its ML-DSA-65 public key (and an ML-KEM-768 key) in the **AcceptMessage**. AuthMessage carries a 3373-byte hybrid signature, except between two introducees that both announced introduction minor version 2 through a version 2 introducer (3.0.19, section 10), where the signature slot is empty and the AUTH MAC alone authenticates; a peer that does not advertise an ML-DSA-65 key cannot complete an introduction.

Not backward-compatible: a peer that does not send ML-DSA-65 and ML-KEM-768 keys fails validation or the session aborts.

---

## 1. Wire format changes

### AcceptMessage body (slots 7 and 8)

Legacy (v1.5):
```
[ ACCEPT.value, sessionId, prevMsgId, ephPubKey, acceptTs, transportProps ]                  // size 6 - no timer
[ ACCEPT.value, sessionId, prevMsgId, ephPubKey, acceptTs, transportProps, autoDeleteTimer ] // size 7 - with timer
```

Current:
```
[ ACCEPT.value, sessionId, prevMsgId, ephPubKey, acceptTs, transportProps, null|timer, mlDsaPubKey, mlKemEphemeralPublicKey ]        // size 9
[ ACCEPT.value, sessionId, prevMsgId, ephPubKey, acceptTs, transportProps, null|timer, mlDsaPubKey, mlKemEphemeralPublicKey, true ]  // size 10, from 3.0.19 (section 10)
```

- **Slot 7: `mlDsaPubKey`** - raw byte array, exactly `ML_DSA_65_PUBLIC_KEY_BYTES = 1952`, required.
- **Slot 8: `mlKemEphemeralPublicKey`** - ML-KEM-768 encapsulation key, exactly 1184 bytes, required and validated.
- **Slot 6**: `autoDeleteTimer` (Long) or `null` when no timer is set.
- The sender always writes 9 slots.

### AuthMessage body

```
[ AUTH.value, sessionId, prevMsgId, mac, signature, kemCiphertext ]   // size 6, the only accepted size
```

- `mac`: 32 bytes.
- `signature`: the validator accepts 0..3373 bytes (0 from 3.0.19), but only a 3373-byte hybrid signature (Ed25519 64 B || ML-DSA-65 3309 B) verifies, and an empty slot is accepted by the protocol engine only under the conditions of section 10.
- `kemCiphertext`: exactly 1088 bytes (ML-KEM-768).

### Validator
- AcceptMessage: 9 slots, or 10 with slot 9 the boolean `true` (from 3.0.19; `false` or any other type is rejected); slots 7 and 8 as above.
- AuthMessage: exactly 6 slots, slot 4 of 0 to 3373 bytes (up to 3.0.18: 1 to 3373), slot 5 exactly 1088 bytes.

---

## 2. Signing (AuthMessage construction)

```
nonce = MAC(macKey, label="org.zerionproject.app.introduction/AUTH_NONCE")
```

`MAC` is keyed BLAKE2b-256 (`crypto.mac`) over the length-prefixed label, with no other input. `macKey` is the sender's ALICE/BOB MAC key derived from the pre-master key: `PRE_MASTER_KEY(X25519 master key, the sender's own ML-KEM-768 encapsulation secret)`, whose ciphertext is sent in AuthMessage slot 5. The receiver decapsulates that ciphertext to derive the peer's MAC key.

```
IF localMlDsaPriv == nil OR remoteMlDsaPub == nil:
    abort the introduction session          // no Ed25519-only signature is produced
hybridPriv = HybridSignaturePrivateKey(ed25519PrivateKey, localMlDsaPriv)   // 32 || 4032 = 4064 bytes
signature  = hybridSign(label="org.zerionproject.app.introduction/AUTH_SIGN", toSign=nonce, hybridPriv)
             // Ed25519 (64) || ML-DSA-65 (3309) = 3373 bytes
```

- `localMlDsaPriv` comes from the local identity's ML-DSA-65 private key.
- `remoteMlDsaPub` was learned from the peer's AcceptMessage slot 7, which the validator requires.

Label binding (must match exactly):
- `"org.zerionproject.app.introduction/AUTH_NONCE"`
- `"org.zerionproject.app.introduction/AUTH_SIGN"`

Both component algorithms sign `M = uint32_be(len(label_utf8)) || label_utf8 || uint32_be(len(toSign)) || toSign` (4-byte big-endian length prefixes, no separator byte), as in `crypto.hybridSign` / `crypto.verifyHybridSignature` in the Android core. ML-DSA-65 is used in pure mode with an empty context.

---

## 3. Verifying (AuthMessage receive)

```
nonce = MAC(remoteMacKey, label="...AUTH_NONCE")

IF remoteMlDsaPub == nil: abort
IF signature.length != 3373: abort          // a 64-byte signature is rejected
hybridPub = HybridSignaturePublicKey(remoteAuthorPubKey, remoteMlDsaPub)   // 32 || 1952 = 1984 bytes
IF NOT verifyHybridSignature(signature, label="...AUTH_SIGN", signed=nonce, hybridPub): abort
// both the Ed25519 half and the ML-DSA-65 half must verify; there is no fallback
```

Never verify only the first 64 bytes of a signature: the Ed25519 half of a hybrid signature is a valid standalone Ed25519 signature, so accepting it would let anyone strip the ML-DSA-65 half. Android rejects any signature that is not 3373 bytes and any session without the peer's ML-DSA-65 key.

---

## 3a. Key confirmation (ActivateMessage)

Once an introducee has verified the peer's AUTH it derives the final master key

```
finalMaster = KDF(LABEL_MASTER_KEY, X25519 master key, aliceKemSecret, bobKemSecret)
```

which the new contact's root key is taken from, and from it the two ACTIVATE keys:

```
aliceActivateKey = KDF("org.zerionproject.app.introduction/ALICE_ACTIVATE_KEY", finalMaster)
bobActivateKey   = KDF("org.zerionproject.app.introduction/BOB_ACTIVATE_KEY", finalMaster)
mac              = MAC(own activate key, label="org.zerionproject.app.introduction/ACTIVATE_MAC")
```

Each side MACs its ACTIVATE with its own key and verifies the peer's with the
peer's key, so a verified ACTIVATE confirms that both introducees derived the
same final master key.

Compatibility: Android 3.0.14 and earlier keyed ACTIVATE with each side's own
pre-master MAC key, which the peer cannot derive (each pre-master includes the
sender's own ML-KEM secret). Every introduction therefore ended in an abort at
ACTIVATE after the contact had been added, between any two Android releases
since the ML-KEM pre-master was introduced. A 3.0.15 introducee and a 3.0.14
introducee still abort at ACTIVATE in the same way; two 3.0.15 introducees
complete. The contact added at AUTH stays in place in every case, as before;
it reaches the peer only if both derived the same root key.

---

## 3b. Which contact may send a message of a session

A message names its session by a session id the sender chooses. A message is
accepted only from a contact that takes part in that session: in an
introducer's session, from one of the two introducees (the message's group must
be one of theirs); in an introducee's session, from the introducer. Any other
message is refused as malformed, so a contact who learns the three author ids
can no longer abort someone else's introduction (3.0.14 and earlier accepted it,
and a foreign ABORT to an introducer left a message that failed validation at
every start).

---

## 4. Session state additions

Each side of the introduction (Local + Remote) carries an `mlDsaPubKey` field and an `mlKemEphemeralPublicKey` field; Local also holds `mlKemEphemeralPrivateKey` and `ownKemSecret`.

- **Local.mlDsaPubKey**: set on `onLocalAccept` from the local identity. Persisted in session state.
- **Remote.mlDsaPubKey**: set on `onRemoteAccept` from `AcceptMessage.mlDsaPubKey`. Persisted in session state.
- Both must survive serialization through the protocol state machine - store in the session dictionary under key `"mlDsaPubKey"` (same key both sides; scope is via Local vs Remote nesting).

Android key constant: `SESSION_KEY_ML_DSA_PUB_KEY = "mlDsaPubKey"`.

---

## 5. Introducer relay

The introducer relays each introducee's AcceptMessage to the other introducee. The introducer **must forward the `mlDsaPubKey` slot unchanged** - it does not sign over it, just copies it. In Android this is in `IntroducerProtocolEngine.onRemoteAccept` / `onRemoteAcceptWhenDeclined`:

```
sendAcceptMessage(otherIntroducee, ..., transportProperties, mlDsaPubKey: m.mlDsaPubKey)
```

The introducer forwards `mlDsaPubKey` and `mlKemEphemeralPublicKey` unchanged, and forwards each AuthMessage (including `kemCiphertext`) unchanged. Android rejects an Accept that does not have exactly 9 slots; extra or missing trailing entries are not tolerated.

---

## 6. Backward-compat matrix

Four combinations. The v1.5 rows are *historical*: current Android completes an
introduction only on the hybrid path (both Accepts carry slots 7 and 8, AUTH
carries a 3373-byte hybrid signature and a KEM ciphertext), and the legacy
fallbacks have been removed from the code, so the v1.5 combinations no longer
complete:

| Sender | Receiver | Accept slot 7? | Auth sig | Verify path |
|---|---|---|---|---|
| v1.5 | v1.5 *(historical)* | absent both ways | 64 B Ed25519 | Ed25519-only |
| v1.5 | v1.6 *(historical)* | sender absent | 64 B Ed25519 | length=64 → Ed25519-only fallback |
| v1.6 | v1.5 *(historical)* | receiver absent → sender sees `remoteMlDsaPub == nil` → 64 B | 64 B Ed25519 | Ed25519-only |
| v1.6 | v1.6 *(current - live path on v2.0.x)* | present both ways | 3373 B hybrid | hybrid verify |

A current sender aborts instead of signing when the peer did not advertise an ML-DSA pubkey.

---

## 7. iOS structures to update (rough map)

- `AcceptMessage` (struct/class): `mlDsaPubKey` and `mlKemEphemeralPublicKey`, both required
- `MessageEncoder.encodeAcceptMessage(...)`: always emit 9 slots
- `MessageParser.parseAcceptMessage(...)`: read slots 7 and 8
- `IntroductionValidator.validateAcceptMessage(...)`: accept count 9 only; `slot[7].count == 1952`, `slot[8].count == 1184` and a valid ML-KEM-768 key
- `IntroductionValidator.validateAuthMessage(...)`: count 6 only, signature 1..3373, `slot[5].count == 1088`
- `IntroduceeSession.Common` (or your equivalent): add `mlDsaPubKey: Data?`
- `SessionEncoder` / `SessionDecoder`: persist + restore `"mlDsaPubKey"` key in both Local and Remote dicts
- `IntroductionCrypto.sign(...)`: hybrid-sign; fail when the local ML-DSA priv or the remote ML-DSA pub is missing
- `IntroductionCrypto.verifySignature(...)`: hybrid only; reject any signature that is not 3373 bytes
- `IntroduceeProtocolEngine.onLocalAccept`: fetch local ML-DSA pubkey from identity, pass through
- `IntroduceeProtocolEngine.onRemoteAccept`: capture `m.mlDsaPubKey` into session.Remote
- `IntroduceeProtocolEngine.onLocalAuth`: pass `local ML-DSA priv` + `session.Remote.mlDsaPubKey` to sign
- `IntroduceeProtocolEngine.onRemoteAuth`: key ACTIVATE with the activate keys of section 3a
- Message routing: check the sender of a message against the session, as in section 3b
- `IntroducerProtocolEngine.onRemoteAccept` / `onRemoteAcceptWhenDeclined`: relay `m.mlDsaPubKey` to the outbound Accept

---

## 8. Constants reference

```
ML_DSA_65_PUBLIC_KEY_BYTES   = 1952
ML_DSA_65_PRIVATE_KEY_BYTES  = 4032
ML_DSA_65_SIGNATURE_BYTES    = 3309
HYBRID_SIGNATURE_BYTES       = 3373    (64 Ed25519 + 3309 ML-DSA-65)
HYBRID_SIGNATURE_PUBLIC_KEY_BYTES   = 1984   (32 + 1952)
HYBRID_SIGNATURE_PRIVATE_KEY_BYTES  = 4064   (32 + 4032)
KEY_TYPE_HYBRID_SIGNATURE    = "Hybrid-Ed25519-ML-DSA-65"
INTRODUCTION_ML_KEM_PUBLIC_KEY_BYTES = 1184
INTRODUCTION_KEM_CIPHERTEXT_BYTES    = 1088
```

Labels (UTF-8, no trailing 0):
```
LABEL_AUTH_SIGN  = "org.zerionproject.app.introduction/AUTH_SIGN"
LABEL_AUTH_NONCE = "org.zerionproject.app.introduction/AUTH_NONCE"
LABEL_PRE_MASTER_KEY = "org.zerionproject.app.introduction/PRE_MASTER_KEY"
LABEL_ACTIVATE_MAC   = "org.zerionproject.app.introduction/ACTIVATE_MAC"
LABEL_ALICE_ACTIVATE_KEY = "org.zerionproject.app.introduction/ALICE_ACTIVATE_KEY"
LABEL_BOB_ACTIVATE_KEY   = "org.zerionproject.app.introduction/BOB_ACTIVATE_KEY"
```

---

## 9. Interop test plan

1. **v1.5 Android ↔ v1.5 iOS** (*historical*): expected today: rejected or aborted.
2. **Current Android ↔ v1.5 iOS**: iOS Accept without slots 7 and 8 fails Android validation; expected: rejected or aborted.
3. **v1.5 Android ↔ current iOS**: symmetric to (2); expected: rejected or aborted.
4. **Current Android ↔ current iOS**: both ship slots 7 and 8. AuthMessage sig is 3373 B with a KEM ciphertext. Hybrid verify on both sides. Introduction completes.
5. **Negative tests**:
   - Tamper one byte of an ML-DSA pubkey advertised in Accept → AuthMessage hybrid verify fails → session aborts.
   - Send 3373-byte sig where the ML-DSA portion is random garbage → hybrid verify fails.
   - Send 64-byte sig, or a 3373-byte sig whose ML-DSA-65 half is invalid → rejected, session aborts.
   - Send an empty signature to a peer that did not receive slot 9 in the relayed Accept, or did not send it → session aborts (section 10).

---

## 10. MAC-only AUTH between 3.0.19 peers (introduction minor version 2)

Up to 3.0.18 every AUTH carried the sender's identity signature. The
introducer relays both AUTH messages, so it ended up holding both introducees'
signatures over values that only the introduction's shared key produces, and
with one introducee's cooperation (who can reveal that key) those signatures
prove to anyone that the other introducee accepted this introduction. From
3.0.19 the signature is left out between peers that can both do without it.

**What the MAC already binds.** The AUTH MAC is computed under the sender's
per-direction MAC key, derived from the X25519 master key and the sender's
ML-KEM-768 secret, so only the two introducees can derive it. Its input is the
introducer's author id and, for each introducee, the author id, the accept
timestamp, the ephemeral X25519 key, the transport properties and the ML-KEM
key (section 2 and `IntroductionCryptoImpl.getAuthMacInputs`). The MAC
therefore authenticates everything the signature authenticated, to the one
party that needs it. The signature only added a proof that was transferable
to third parties. It never protected against the introducer: the introducer
relays the Accept messages and could already substitute the ephemeral keys and
the ML-KEM keys in both directions, which is why both introducees are told to
compare safety numbers afterwards. Nothing is lost against anyone else.

**Version announcement.** `IntroductionManager.MINOR_VERSION` is 2 from
3.0.19 (`MAC_ONLY_AUTH_MIN_VERSION = 2`); up to 3.0.18 it was 1. The client
versioning exchange tells every contact pair which minor version the other
side runs, so an introducee knows the introducer's version and the introducer
knows both introducees' versions. The two introducees are not yet contacts, so
the introducer carries the announcement between them in the relayed Accept:

- An introducee that runs version 2 sends its Accept with slot 9 = `true` if
  and only if the introducer's introduction client is at minor version 2 or
  later (an older introducer's validator accepts exactly 9 slots and would
  reject the message). The introducee records `Local.macOnlyAuth = true`.
- The introducer, when relaying an Accept that carried slot 9, keeps it if and
  only if the receiving introducee is at minor version 2 or later, and drops it
  otherwise. An Accept that arrived without slot 9 is relayed without it. The
  introducer stores nothing for this; it relays each Accept as it arrives
  (`IntroducerProtocolEngine.onRemoteAccept` and `onRemoteAcceptWhenDeclined`).
- An introducee that receives a relayed Accept with slot 9 records
  `Remote.macOnlyAuth = true`. Both flags are persisted in the session under
  the key `"macOnlyAuth"` of the Local and Remote dictionaries and read as
  `false` when absent, so a session saved by 3.0.18 continues unchanged.

**Rule.** MAC-only AUTH is in force for a session exactly when
`Local.macOnlyAuth && Remote.macOnlyAuth` (`IntroduceeProtocolEngine.isMacOnlyAuth`).

- Sending: in force, the signature slot is the empty byte string and no
  identity key is used; not in force, the AUTH is signed exactly as in
  section 2.
- Receiving: the MAC is verified first and unchanged. In force, a non-empty
  signature slot aborts the session; not in force, the slot must be a verifying
  3373-byte hybrid signature (section 3), so an empty slot aborts the session.
  An empty signature is therefore never accepted from a peer that did not
  announce version 2, and a peer that announced it cannot be made to accept a
  signed AUTH in its place: there is no downgrade by omission in either
  direction.

Both introducees reach the same decision because each flag reaches the other
side only through the introducer: with an honest introducer, A sends slot 9
iff the introducer is new, the introducer keeps it iff B is new, and
symmetrically, so both see both flags exactly when all three run version 2.

| Introducer | Introducee A | Introducee B | Slot 9 relayed to A | Slot 9 relayed to B | AUTH |
|---|---|---|---|---|---|
| old | any | any | no (A never sends it) | no | signed both ways, as up to 3.0.18 |
| new | new | old | no (B never sends it) | no (dropped for B) | signed both ways |
| new | old | new | no (dropped for A) | no (A never sends it) | signed both ways |
| new | new | new | yes | yes | MAC only, both ways |

**What a dishonest introducer can still do.** The introducer carries the
announcements, so it can drop one of them and make the other introducee
believe its peer is older; that introducee then signs, as every introducee did
up to 3.0.18, and the introduction completes or aborts depending on what the
introducer relays back. The introducer gains exactly the signature it always
received, and nothing else changes: the keys stay bound by the MAC, and the
flags are not inputs of the MAC on purpose, so the MAC verification stays as it
was. When a future release makes minor version 2 the minimum, the signed form
can be removed entirely.

**Older peers.** An introducee on 3.0.18 never sees slot 9 (its introducer
drops it, or its own introducer is old and the other introducee does not send
it), always receives a signed AUTH and sends one, and its validator still
rejects an empty signature. An introducer on 3.0.18 never receives slot 9.

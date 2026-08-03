# F-2: Introduction Protocol - Hybrid Ed25519 + ML-DSA-65 Signatures

> **Shipped; current as of v2.0.x.** The hybrid Ed25519 + ML-DSA-65 introduction
> signatures described here are the production wire format. The algorithm and
> all size constants below are current. The v1.5 legacy-peer interop rows are
> retained for history (annotated *historical*) and describe behaviour against
> pre-hybrid peers. The open downgrade-fallback question in §3 / §9 remains
> open - not yet tightened.

iOS-side parity for the Zerion introduction protocol (originally landed for
v1.6; shipped and current as of v2.0.x). Android implementation: commit
`11f0e95` (dev + master).

## TL;DR
The Briar introduction protocol's `AuthMessage` signs the AUTH nonce with the introducee's Ed25519 author key. We now optionally sign with a **hybrid Ed25519 + ML-DSA-65** key. Each side advertises its ML-DSA-65 public key in the **AcceptMessage** (new optional slot). When both sides advertise a key, AuthMessage carries a 3373-byte hybrid signature; otherwise it stays at 64-byte Ed25519. The receiver length-dispatches.

Backward-compatible in both directions - v1.5 ↔ v1.6 introductions still complete.

---

## 1. Wire format changes

### AcceptMessage body (new optional slot 7)

Legacy (v1.5):
```
[ ACCEPT.value, sessionId, prevMsgId, ephPubKey, acceptTs, transportProps ]                  // size 6 - no timer
[ ACCEPT.value, sessionId, prevMsgId, ephPubKey, acceptTs, transportProps, autoDeleteTimer ] // size 7 - with timer
```

v1.6 (new):
```
[ ACCEPT.value, sessionId, prevMsgId, ephPubKey, acceptTs, transportProps, null|timer, mlDsaPubKey ]  // size 8
```

- **Slot 7 (new): `mlDsaPubKey`** - raw byte array, length must equal `ML_DSA_65_PUBLIC_KEY_BYTES = 1952`.
- **Slot 6**: still `autoDeleteTimer` (Long) or `null` when no timer is set. When slot 7 is present, slot 6 must be present (use `null` if no timer).
- Sender writes size 8 only if local identity has an ML-DSA-65 keypair.

### AuthMessage body (signature length raised)

No structural change - still:
```
[ AUTH.value, sessionId, prevMsgId, mac, signature ]   // size 5
```

But `signature` length range is now `1 .. HYBRID_SIGNATURE_BYTES = 3373` (was `1 .. 64`).
- 64-byte signature: Ed25519 only (legacy or downgrade).
- 3373-byte signature: hybrid (Ed25519 64 B || ML-DSA-65 3309 B).

### Validator
- AcceptMessage: accept body size **6, 7, or 8**. If size == 8, slot 7 (when non-null) MUST be exactly 1952 bytes.
- AuthMessage: signature length range raised to `[1, 3373]`.

---

## 2. Signing (AuthMessage construction)

```
nonce = HMAC(macKey, label="org.zerionproject.app.introduction/AUTH_NONCE")
```
(Unchanged - same label, same MAC.)

```
IF localMlDsaPriv != nil AND remoteMlDsaPub != nil:
    hybridPriv = HybridSignaturePrivateKey(ed25519PrivateKey, localMlDsaPriv)   // 32 || 4032 = 4064 bytes
    signature  = hybridSign(label="org.zerionproject.app.introduction/AUTH_SIGN", toSign=nonce, hybridPriv)
                 // = ed25519Sign(nonce) || mlDsa65Sign(nonce) = 64 || 3309 = 3373 bytes
ELSE:
    signature  = ed25519Sign(label="org.zerionproject.app.introduction/AUTH_SIGN", toSign=nonce, ed25519Priv)
                 // = 64 bytes
```

Decision rule for `IF`:
- `localMlDsaPriv` comes from the local identity's ML-DSA-65 private key (already shipped in v1.6 identity model).
- `remoteMlDsaPub` was learned from the peer's AcceptMessage slot 7. If the peer is v1.5 and sent no slot 7, this is `nil` → Ed25519 only.

Label binding (must match exactly):
- `"org.zerionproject.app.introduction/AUTH_NONCE"`
- `"org.zerionproject.app.introduction/AUTH_SIGN"`

The hybrid `sign` and `verify` helpers must use the same label-binding rule we use elsewhere in v1.6 (label || 0x00 || toSign as the actual signing input for each component algorithm - same as `crypto.hybridSign` / `crypto.verifyHybridSignature` in Bramble).

---

## 3. Verifying (AuthMessage receive)

```
nonce = HMAC(remoteMacKey, label="...AUTH_NONCE")

IF signature.length == HYBRID_SIGNATURE_BYTES (3373) AND remoteMlDsaPub != nil:
    hybridPub = HybridSignaturePublicKey(remoteAuthorPubKey, remoteMlDsaPub)   // 32 || 1952 = 1984 bytes
    ok = verifyHybridSignature(signature, label="...AUTH_SIGN", signed=nonce, hybridPub)
ELSE:
    // Either peer is v1.5 (no ML-DSA pubkey), or peer downgraded for compat.
    // Take first 64 bytes of signature; that's the Ed25519 component (or the whole sig if it was 64-byte already).
    ed25519Sig = (signature.length == 3373) ? signature[0..64] : signature
    ok = verifyEd25519(ed25519Sig, label="...AUTH_SIGN", signed=nonce, remoteAuthorPubKey)

IF NOT ok: abort introduction session.
```

Order matters: hybrid is preferred when both conditions are true, fallback ONLY when the peer is legitimately legacy or downgraded. **Do not** accept a 64-byte sig from a peer that sent an ML-DSA pubkey - that would be a downgrade attack. (Android currently allows this fallback when `signature.length != 3373`; if/when we tighten, do it in both clients together.)

---

## 4. Session state additions

Each side of the introduction (Local + Remote) needs an optional `mlDsaPubKey: Data?` field.

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

If the introducer is on v1.5 and doesn't know about slot 7, BdfList parsing should ignore the extra slot - verify your iOS BdfList parser tolerates extra trailing entries (Android Bramble does).

---

## 6. Backward-compat matrix

Four combinations. The v1.5 rows are *historical* - they describe interop with
pre-hybrid (v1.5) peers; on a current all-v2.0.x fleet the hybrid row is the
live path, but the legacy fallbacks remain in the code for any lingering legacy
peer:

| Sender | Receiver | Accept slot 7? | Auth sig | Verify path |
|---|---|---|---|---|
| v1.5 | v1.5 *(historical)* | absent both ways | 64 B Ed25519 | Ed25519-only |
| v1.5 | v1.6 *(historical)* | sender absent | 64 B Ed25519 | length=64 → Ed25519-only fallback |
| v1.6 | v1.5 *(historical)* | receiver absent → sender sees `remoteMlDsaPub == nil` → 64 B | 64 B Ed25519 | Ed25519-only |
| v1.6 | v1.6 *(current - live path on v2.0.x)* | present both ways | 3373 B hybrid | hybrid verify |

A v1.6 sender NEVER ships a 3373-byte sig to a peer that didn't advertise an ML-DSA pubkey. This keeps a v1.5 receiver's validator (which caps signature at 64) from rejecting the AuthMessage.

---

## 7. iOS structures to update (rough map)

- `AcceptMessage` (struct/class): add `let mlDsaPubKey: Data?`
- `MessageEncoder.encodeAcceptMessage(...)`: accept and emit the optional slot
- `MessageParser.parseAcceptMessage(...)`: read optional slot 7 when body.count == 8
- `IntroductionValidator.validateAcceptMessage(...)`: accept counts 6/7/8; if 8, validate `slot[7].count == 1952`
- `IntroductionValidator.validateAuthMessage(...)`: raise signature max length to 3373
- `IntroduceeSession.Common` (or your equivalent): add `mlDsaPubKey: Data?`
- `SessionEncoder` / `SessionDecoder`: persist + restore `"mlDsaPubKey"` key in both Local and Remote dicts
- `IntroductionCrypto.sign(...)`: hybrid-sign when local ML-DSA priv + remote ML-DSA pub both present
- `IntroductionCrypto.verifySignature(...)`: length-dispatch hybrid vs Ed25519-prefix fallback
- `IntroduceeProtocolEngine.onLocalAccept`: fetch local ML-DSA pubkey from identity, pass through
- `IntroduceeProtocolEngine.onRemoteAccept`: capture `m.mlDsaPubKey` into session.Remote
- `IntroduceeProtocolEngine.onLocalAuth`: pass `local ML-DSA priv` + `session.Remote.mlDsaPubKey` to sign
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
```

Labels (UTF-8, no trailing 0):
```
LABEL_AUTH_SIGN  = "org.zerionproject.app.introduction/AUTH_SIGN"
LABEL_AUTH_NONCE = "org.zerionproject.app.introduction/AUTH_NONCE"
```

---

## 9. Interop test plan

1. **v1.5 Android ↔ v1.5 iOS** (regression): introduction completes, AuthMessage sig is 64 B.
2. **v1.6 Android ↔ v1.5 iOS**: Android Accept ships slot 7. iOS validator (legacy) parses body size 8 - verify iOS BdfList tolerates extra slot. iOS Accept ships size 6 or 7 (no slot 7). Android sees `remoteMlDsaPub == nil` → falls back to 64 B Ed25519. Introduction completes.
3. **v1.5 Android ↔ v1.6 iOS**: symmetric to (2).
4. **v1.6 Android ↔ v1.6 iOS**: both ship slot 7. AuthMessage sig is 3373 B. Hybrid verify on both sides. Introduction completes.
5. **Negative tests**:
   - Tamper one byte of an ML-DSA pubkey advertised in Accept → AuthMessage hybrid verify fails → session aborts.
   - Send 3373-byte sig where the ML-DSA portion is random garbage → hybrid verify fails.
   - Send 64-byte sig when peer DID advertise ML-DSA pubkey → currently accepted as Ed25519-only fallback. Flag this if iOS wants to tighten (we can tighten in both clients together).

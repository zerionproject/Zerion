# B.3 record-placement decision (shared, iOS ↔ Android)

> **SHIPPED in v1.5.0; `B3_PROOF_ENABLED` permanently on. Historical design
> record.** B.3 hybrid pairing shipped in v1.5.0; the proof-at-slot[4] layout
> below is the production wire format. The feature gate is no longer a toggle - 
> it is permanently enabled. The future-tense rollout/flip steps in §5 and the
> open follow-ups in §8 are retained for history and are marked completed or
> superseded inline.

**Status:** SHIPPED in v1.5.0. Originally agreed by both teams and implemented
behind `B3_PROOF_ENABLED` (BuildConfig boolean on Android, `static let`
constant on iOS), which is now permanently on.
**Replaces:** the fictional 4-slot `[majorVersion, minorVersion, signingPubKey,
pqPubKey]` layout drafted in `B3_B4_SPEC_v1.5.0.md` §1 ("Wire - BDF slot in
contact-info record"). That layout never existed on either platform.
**Targets:** byte-identical wire format on iOS Zerion v1.5.0 ↔ Android
Zerion v1.5.0 production (`com.professor.zerion`).

---

## 1. What's actually on the wire today

Both platforms use the upstream Bramble two-stage handshake unchanged:

### Stage 1 - over-Tor handshake (record stream)

`zerion-core/src/main/java/org/zerionproject/core/contact/HandshakeManagerImpl.java`
`.performHybridHandshake()` (Android) ↔ iOS:
`PendingContactView.swift:1830-1980`.

Wire records, raw bytes (no BDF), 6 record types from
`HandshakeRecordTypes.java`:

| Type | Constant                       | Payload                                |
|-----:|--------------------------------|----------------------------------------|
| 0x00 | `RECORD_EPHEMERAL_PUBLIC_KEY`  | (legacy non-hybrid path)               |
| 0x01 | `RECORD_PROOF_OF_OWNERSHIP`    | master-key proof of ownership          |
| 0x02 | `RECORD_MINOR_VERSION`         | handshake minor-version negotiation    |
| 0x03 | `RECORD_HYBRID_STATIC_KEY`     | 1216 B = X25519(32) ‖ ML-KEM-768(1184) |
| 0x04 | `RECORD_KEM_CIPHERTEXT`        | 1088 B ML-KEM-768 ciphertext           |
| 0x05 | `RECORD_MODE3_CAPABILITY`      | 1 B PCS Mode 3 capability flag         |

`RECORD_HYBRID_STATIC_KEY` (type 0x03) is reused for both static and ephemeral
hybrid keys - same record type, same 1216-byte raw layout, position in the
stream determines which is which. Encoded via `k.getEncoded()` straight out
of `HybridAgreementPublicKey`, validated only by length on receive.

After this stage both sides hold:

- `theirStaticHybridPub` (1216 B raw)
- `ourStaticHybridKeyPair`
- `theirEphemeralHybridPub` (1216 B raw)
- `ourEphemeralHybridKeyPair`
- `masterKey` (derived from KEM secret + key agreements)
- `mode3Capable` (Mode 3 PCS capability)

### Stage 2 - encrypted CONTACT_INFO record

Runs over the master-key-encrypted channel.

`zerion-core/.../contact/ContactExchangeManagerImpl.sendContactInfo()`
(Android: `ContactExchangeManagerImpl.java:181-190`) ↔ iOS:
`PendingContactView.swift:2090-2091`.

Top-level record is a 4-slot BDF list:

```
CONTACT_INFO :=
  [ authorList,         // BdfList - slot 0
    propsDict,          // BdfDictionary - slot 1
    signature,          // 64-byte Ed25519 sig over (author ‖ props ‖ ts) - slot 2
    timestamp ]         // i64 ms since epoch - slot 3
```

Where `authorList` is itself:

```
authorList := [ formatVersion, displayName, signingPubKey ]   // 3 slots
                                            ^^^^^^^^^^^^
                                            32-byte Ed25519 pubkey
```

So the only signing key carried in the entire handshake is in
`authorList[2]`, sent in the `CONTACT_INFO` record over the encrypted
channel **after** master-key derivation completes.

**The PQ pubkey is in stage 1; the Ed25519 signing pubkey is in stage 2.
They are in different records.**

The spec's `[majorVersion, minorVersion, signingPubKey, pqPubKey]` layout
was a fiction - no record on either platform looks like that.

---

## 2. Why the original Option C had a chicken-and-egg

Earlier proposal: add `RECORD_HYBRID_STATIC_KEY_PROOF = 0x06` to the stage-1
handshake stream, payload = the 64-byte B.3 sig.

Problem: at the moment record 0x06 is received, the verifier doesn't yet
have `authorSigningPub`. That's in `authorList[2]` of `CONTACT_INFO`,
which only arrives in stage 2 over the master-key-encrypted channel.

Repair attempts within Option C land at:

- **C-1** - also add `RECORD_AUTHOR_SIGNING_KEY = 0x07` so the verifier
  has the pubkey before processing the proof. Two new wire record types.
- **C-2** - defer verification to stage 2. But then there's no reason to
  put the proof in stage 1 - we can just put it in `CONTACT_INFO` itself.

C-2 is what B-revised below is.

---

## 3. Decision - B-revised: proof = `CONTACT_INFO` slot[4]

`CONTACT_INFO` BDF list grows from 4 slots to 5 when
`messaging.minorVersion >= 5`:

```
CONTACT_INFO_v5 :=
  [ authorList,         // slot 0
    propsDict,          // slot 1
    signature,          // slot 2 (existing Ed25519 sig over author+props+ts)
    timestamp,          // slot 3
    b3ProofSig ]        // slot 4 - NEW, 64 bytes Ed25519 (B.3 proof)
```

The `b3ProofSig` value comes from `B3PqProof.sign(signingPriv, ourEph,
theirEph, ourStaticPqPub)` per the helper at
`zerion-core/src/main/java/org/zerionproject/core/contact/B3PqProof.java` (Android) and
`Packages/ZerionCrypto/.../B3PqKeyProof.swift` (iOS). Byte-identical
across platforms - pinned by `docs/wire/test_vectors/B3_v1.txt`.

### Why B-revised, not C-1

| Concern                              | C-1 (two new record types) | B-revised (slot[4]) |
|--------------------------------------|----------------------------|----------------------|
| Wire surface added                   | 2 new record types          | 1 BDF slot           |
| Verification timing                  | Stage 1 (fast-fail)         | Stage 2 (post-channel) |
| Single attachment for QR + over-Tor  | Requires separate spec      | Both end with CONTACT_INFO ✅ |
| Aligns with existing patterns        | New                         | Existing CONTACT_INFO sig already cross-references channel state ✅ |
| Cost on verify-fail                  | None (no KEM done)          | One wasted KEM op + ~1.25 KB transient |
| Cost on success                      | Same                        | Same                 |

C-1's only advantage is fail-fast. The cost - one extra KEM operation
(~1 ms on Android, ~0.5 ms on iOS) and ~1.25 KB of transient memory per
in-progress handshake - is trivially preferable to two new wire records
and a split verification pipeline.

### Single attachment covers both contact-add paths

| Path                | Stage-1 carrier of static PQ pubkey | Stage-2 record       |
|---------------------|--------------------------------------|---------------------|
| Face-to-face / QR   | QR payload (iOS slot[3])             | `CONTACT_INFO`       |
| Remote / over-Tor   | `RECORD_HYBRID_STATIC_KEY` (raw)     | `CONTACT_INFO`       |

Both paths end with the same `CONTACT_INFO` exchange, so one slot[4]
extension covers both.

---

## 4. Receiver state machine

### After stage 1 (handshake completes)

Buffer in memory until stage 2 arrives, with a 60-second hard timeout:

```
b3ReceiverState := {
  theirStaticPqPub : 1184 B   // theirHybridStaticKey[32..1216]
  ourEphX25519     : 32 B     // ourHybridEphemeralKeyPair.public[0..32]
  theirEphX25519   : 32 B     // theirHybridEphemeralKey[0..32]
  expiresAt        : now + 60_000 ms
}
```

Total: 1248 bytes per pending handshake. Stored in process memory only,
never persisted.

### On `CONTACT_INFO` arrival

```
fn onContactInfoReceived(record: BdfList, peerMinorVersion: int):
  if peerMinorVersion >= 5:
    if record.length != 5:
      reject("missing slot[4] from v5 peer")              # tampering / downgrade
    proofSig = record[4] as raw 64 B
    if proofSig == null or proofSig.length != 64:
      reject("malformed slot[4]")
    if b3ReceiverState == null or b3ReceiverState.expired():
      reject("no buffered handshake state - proof unverifiable")
    authorSigningPub = record[0][2]                       # authorList.signingPubKey
    if !B3PqProof.verify(authorSigningPub,
                          b3ReceiverState.theirEphX25519,
                          b3ReceiverState.ourEphX25519,
                          b3ReceiverState.theirStaticPqPub,
                          proofSig):
      reject("B.3 proof verification failed")             # tampering
    # … then the existing slot[2] signature check, then promote to pending-contact
  elif peerMinorVersion <= 4:
    if record.length != 4:
      reject("unexpected slot[4] from v4 peer")           # malformed
    # legacy path - no B.3 verification, accept as-is
  else:
    reject("unknown minor version")

  zero(b3ReceiverState)
```

**`reject()` behaviour:** zero the buffered state, tear down the encrypted
channel, **do not** write any keychain / keystore entries, **do not** enter
pending-contact state, surface a non-actionable user-facing error
("contact verification failed"). Silently reject - **emit NO log** of any
kind (no logger, no telemetry, no `android.util.Log`, no `System.err`), per
the project's absolute no-logging policy. The rejection reason
(`missing | malformed | verify_failed | state_expired`) MUST NOT be written
anywhere; it exists only as control flow.

### On 60-second timeout (no `CONTACT_INFO` arrives)

Zero the buffered state, tear down the channel. The wasted KEM op is
absorbed.

---

## 5. Versioning + rollout

| Component                  | v1.4 (pre-B.3) | v1.5.0 (shipped) |
|----------------------------|--------------|------------------|
| `messaging` clientId       | `org.zerionproject.app.messaging` | (unchanged) |
| `messaging.majorVersion`   | 0            | 0 (unchanged)    |
| `messaging.minorVersion`   | 4            | **6** (current shipped; `B3_PROOF_ENABLED ? 6 : 5`) |
| `CONTACT_INFO` BDF list    | 4 slots      | **5 slots**      |
| Handshake record types     | 0x00..0x05   | (unchanged)      |

### Rollout matrix

|              | 1.4 receiver               | 1.5 receiver (`B3_PROOF_ENABLED=true`) |
|--------------|----------------------------|-----------------------------------------|
| **1.4 sender** | 4 slots → legacy path ✅ | minor=4, 4 slots → legacy path ✅       |
| **1.5 sender (flag on)** | 5 slots; 1.4 BDF reader tolerates trailing slot via end-marker form, 1.4 ignores slot[4] ✅ | 5 slots, B.3 verified ✅ |

Behaviour symmetric; both sides' BDF readers use end-marker termination
(Android `BdfReaderImpl.readList():361-369`; iOS
`BdfReader.swift:167`), so trailing-slot tolerance is bidirectional.

### Gate behaviour

- Flag **off** (default): write `messaging.minorVersion = 4`, send 4-slot
  `CONTACT_INFO`, ignore any received slot[4]. Byte-identical with v1.4.
- Flag **on**: write `messaging.minorVersion = 5`, send 5-slot
  `CONTACT_INFO` with computed B.3 proof at slot[4]. On receive, enforce
  the receiver state machine above.

### When to flip - COMPLETED (shipped v1.5.0)

This sequence was executed; `B3_PROOF_ENABLED = true` shipped in both v1.5.0
release builds and is now permanently on. Retained for history:

1. ~~Joint debug build with `B3_PROOF_ENABLED = true` on both sides.~~ Done.
2. ~~Real iOS↔Android contact-add over Tor, captured on-wire.~~ Done.
3. ~~Confirm B.3 proof is present, verifies, contact-add succeeds.~~ Done.
4. ~~Confirm 1.4 ↔ 1.5 cross-version still succeeds (legacy fall-through).~~ Done.
5. ~~Ship `B3_PROOF_ENABLED = true` in both v1.5.0 release builds.~~ Done.

The current shipped `messaging.minorVersion` is **6**
(`MINOR_VERSION = B3_PROOF_ENABLED ? 6 : 5`); legacy 4-slot acceptance
is retained for cross-version interop.

---

## 6. Helper API - already implemented both sides

Both helpers are pure-crypto, no DB / no UI dependency, no network state.
They take exactly the inputs the receiver state machine has and produce /
verify the slot[4] sig. Byte-identical against the canonical vector at
`docs/wire/test_vectors/B3_v1.txt`.

### Android

`zerion-core/src/main/java/org/zerionproject/core/contact/B3PqProof.java`

```java
public static byte[] sign(byte[] signingPriv,
        byte[] localEph, byte[] remoteEph, byte[] pqPubKey);

public static boolean verify(byte[] signingPub,
        byte[] signerEph, byte[] verifierEph,
        byte[] pqPubKey, byte[] sig);
```

Tests: `B3PqProofTest` in `zerion-core/src/test/java/org/zerionproject/core/contact/B3PqProofTest.java`, 13 cases
including `canonicalVectorMatchesIOS`.

### iOS

`Packages/ZerionCrypto/Sources/ZerionCrypto/Primitives/B3PqKeyProof.swift`

```swift
public static func sign(signingPriv: Data,
                        localEph: Data, remoteEph: Data,
                        pqPubKey: Data) -> Data

public static func verify(signingPub: Data,
                          signerEph: Data, verifierEph: Data,
                          pqPubKey: Data, sig: Data) -> Bool
```

Tests: `B3PqKeyProofTests`, 17 cases including the same canonical
vector pinning.

---

## 7. Security properties (the part that matters)

Once shipped on both sides:

- **Static PQ-key binding.** The 1184-byte ML-KEM-768 portion of the
  long-term hybrid identity is signed with the long-term Ed25519 key.
  A confused-deputy Ed25519 oracle can no longer be reused to swap a
  victim's static PQ pubkey for an attacker's during a fresh contact-add.
- **Session binding.** The proof is bound to the specific session via
  the two ephemeral X25519 keys exchanged in stage 1. Replaying a proof
  from one session in another is impossible - the role byte and
  sessionId differ.
- **Domain separation.** Both labels (`ZERION_PQ_KEY_PROOF_v1` for the
  sig input, `ZERION_HANDSHAKE_SESSION_v1` as the BLAKE2b key for
  sessionId) are unique to this purpose. No cross-protocol reuse.
- **Symmetric role.** Both sides compute role + sessionId from the same
  unsigned-byte lex sort of the ephemerals. No initiator/responder
  asymmetry.

What this **doesn't** fix:
- Attacker who already owns the long-term Ed25519 signing key. (Out of
  scope; that's full identity compromise.)
- Pre-handshake QR-payload tampering on the face-to-face path. (Covered
  by the existing commitment binding in `RECORD_HYBRID_STATIC_KEY`'s
  commitment check - not B.3's job.)
- Forward secrecy or post-compromise security. (Handled by PCS Mode 2/3,
  not B.3.)

---

## 8. Open follow-ups (status as of v2.0.x)

- **Field-level encryption on transport properties** (Android) - see
  `B3_B4_SPEC_v1.5.0.md` Q2 / file-level audit findings.
- ~~**Onion concurrent hidden services for B.4** - onionwrapper PR needed.~~
  **DONE - shipped in v1.5.0.** B.4 onion rotation is live; the concurrent
  hidden-services API exists in the Zerion onionwrapper fork. Mode 3-Full
  per-message also shipped (default since v1.7), superseding the open PCS
  notes here.
- **Rotating the long-term Ed25519 key** - out of scope; would break
  the safety-number / fingerprint UI.

---

*Authored: 2026-04-29. Source of truth: this file. Mirrored on iOS at the
equivalent path. Any change to wire layout requires a co-edit on both
sides + bumping the canonical test vector in
`docs/wire/test_vectors/B3_v1.txt`.*

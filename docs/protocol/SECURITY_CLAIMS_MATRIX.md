# Mode 3-Full / ZWF / ZPP security-claims matrix

Each row is a claim about the **currently shipped** protocol, the production code
that enforces it, and the regression test that proves it. Claims not backed by
both a production invariant and a test are marked as such and either weakened or
listed as future work. Do not add proposed/future properties as current
guarantees.

| # | Claim (current guarantee) | Production invariant / code | Test evidence |
|---|---|---|---|
| 1 | Our ML-KEM key pair rotates at least every `MODE3_FULL_SEND_ROTATION_INTERVAL` (16) of **our own** sends, independent of the peer's receive pattern. | `Mode3FullRatchetImpl.pqEncapsulateSend(state, ownSendsSinceRotation)` rotates when `ownSendsSinceRotation >= INTERVAL-1`; the counter is owned by the send side (`ownSendsSinceRotation` in `PcsStreamEncrypterImpl` / `ZwfMode3FullStreamEncrypter`, updated under `directionLock`), never by the receive path. Scope: **per connection** (fresh state + counter on reconnect). | `Mode3FullRatchetImplTest`: `testRotatesWithinBoundDespiteAlternatingReceives`, `rotationBoundHoldsForArbitrarySendReceiveSchedules`, `rotationBoundaryIsExactlyTheInterval`, `testSenderRotatesActiveKeyPairPeriodically` |
| 2 | Every **application** frame carries a real post-quantum secret; the classical-only opening sentinel is only ever a **cover** frame. Scope: the ZWF/ZPP path; the post-pairing `PcsStream*` continuation is a separate contract (see below). | `ZppSendScheduler.tick()` dequeues an application record only when `pqReady` is true; `ZwfDuplexConnection.isPqReady()` is true once `theirActivePqPk != null`; the peer key is monotonic so no TOCTOU. Until then only cover flows (cover still advertises our key, so the bootstrap completes). | `ZppSendSchedulerTest`: `holdsAllApplicationRecordsUntilPqReady`, `idleTicksSendCover` |
| 2b | The **receive** direction enforces the sentinel policy: once a direction has accepted a frame with a real ML-KEM contribution, any later zero-sentinel frame on that direction is rejected, so an attacker holding the symmetric chain state cannot hold the receiver on a branch that absorbs no fresh PQ entropy. | `ZwfMode3FullStreamDecrypter` keeps a per-direction `pqConfirmedRecv` latch, set only after a frame with a PQ secret passes full body/padding authentication; a zero-sentinel after the latch throws `FormatException` and drops the stream. | `ZwfMode3FullStreamIntegrationTest`: `postBootstrapZeroKemCiphertextIsRejected`, `pqConfirmationIsMonotonicPerReceiveDirection` |
| 2c | Mode 3-Full receive state is **published only after full frame commit**: a frame that fails body or padding authentication cannot advance the shared state the send side or close-time persistence observes. | `ZwfMode3FullStreamDecrypter.readFrame` holds the decapsulated candidate state in locals and publishes to `recvState`/the shared cell only after body decryption, authentication and padding validation succeed. | `ZwfMode3FullStreamIntegrationTest.rejectedFrameDoesNotPublishMode3FullState` |
| 3 | ML-KEM rotation heals against a snapshot of a pre-rotation private key: an attacker who copied our old decapsulation key cannot derive the secret the peer encapsulates to our new key. | `withSendAdvance` rotates our key pair and advertises the new public key; the peer encapsulates to it; only the new private key decapsulates it; old keys are evicted from the retention LRU and zeroized. | `Mode3FullRatchetImplTest.attackerSnapshotCannotDeriveSecretAfterRotation` |
| 4 | ZTP reconnect starts a **fresh** Mode 3-Full ratchet per connection; the ZTP path never consumes persisted Mode 3-Full state, and close-time persistence keeps no retained ML-KEM private keys (only the current key pair, as the legacy consumer's existence gate). Scope: persisted PCS state **is** consumed by the post-pairing `PcsStream*` continuation, a distinct production integration with its own properties (variable-length BTP frames, no `pqReady` gate, no cover scheduler); no path downgrades an established ZWF session into it in the shipped Tor-only configuration. | `ZtpConnectionEstablisher.resume()` calls `deriveSession(rootKey, alice)` → `mode3FullRatchet.createInitialState()`; the `persistedMode3Full` argument is ignored. `ZtpSessionProviderImpl.saveMode3FullState` strips the retained key-pair LRU before persisting. | `ZwfSessionResumeTest` documents the factory capability is **not** the production path; production resume covered by `ZtpConnectionHandler` tests |
| 4b | An authenticated peer cannot multiply live sessions on the same transport: concurrent same-contact connections are capped at the honest-glare pair, and the registry's glare interruption performs a real, idempotent socket close. There is no key or nonce reuse across concurrent sessions (the stream counter is a persistent process-wide singleton). | `ZtpConnectionHandlerImpl.acquireSession` caps same-transport sessions at two; its `InterruptibleConnection` closes the underlying streams on `forceClose`/`interruptOutgoingSession`. `ZwfStreamCounter` allocates strictly-monotonic ids under a singleton lock. | `ZtpSessionGuardTest.thirdSameTransportConnectionIsRefused`; `ZwfStreamCounterTest` monotonicity/concurrency tests |
| 4c | A process restart cannot re-accept a previously committed receive stream id: the persisted receive high-water mark acts as a startup floor even though the in-memory seen-set is lost. | `ZwfStreamCounter` records the loaded receive high-water as `startupRecvFloor` on first access after startup and rejects any id at or below it. | `ZwfStreamCounterTest`: `restartDoesNotReacceptCommittedOldStreamId`, `startupFloorIsPerContact` |
| 5 | The classical X25519 DH ratchet provides forward secrecy through its one-way chain but is **not** an active post-compromise mechanism; the carried X25519 key is authenticated but drives no ratchet. | `ZwfMode3FullStreamDecrypter.applyReceiveDhRatchet` returns immediately unless `keyParser != null && recvState.isMode2()`; in Mode 3-Full `isMode2()` is false. Whitepaper §6.3. | Covered by protocol/whitepaper text; the receive path's Mode 3-Full frames never invoke `performReceiveDhRatchet` |
| 6 | Fixed 4096-byte frames at a paced, jittered, cover-filled cadence **within a live connection**: a real frame and a cover frame are the same size, so an observer of an established connection cannot distinguish message from cover or infer size/timing within it. Pacing is self-paced (a stall lengthens the cadence, no catch-up bursts); connection existence, lifetime, reconnects and session count are not hidden, and global traffic confirmation against Tor is out of scope. | `ZppSendScheduler.tick()` emits exactly one fixed-size ZWF frame per iteration; `ZppConnectionRunnerImpl.computeInterval` zero-mean jitter; `FRAME_LENGTH` padding in the encrypter. | `ZppSendSchedulerTest.everyTickSendsExactlyOneFrame`; jitter/framing tests |

## Compromise model (claim 3 / PCS scope)

Post-compromise security in Mode 3-Full rests on the per-message ML-KEM ratchet,
not the (inert) classical DH ratchet. For each compromised state:

| Attacker copies | Zerion currently provides |
|---|---|
| Current symmetric stream chain key only | Forward secrecy for earlier messages; the compromised chain state exposes messages until the next ML-KEM secret is mixed in, then future keys heal as rotation advances. |
| Active local ML-KEM private key | Heals on the next rotation to a key pair the attacker never saw, once the peer encapsulates to the new key (claim 3). Bounded by the rotation interval of our own sends. |
| Complete current in-memory Mode 3-Full state (active + retained keys) | Heals after enough rotations that all snapshotted key pairs are evicted from the retention LRU and a fresh key pair is in use. |
| Persisted contact root key | No healing within the protocol: the root seeds every connection's session. Out of scope for per-connection PCS. |
| Static identity material | Out of scope (identity, not session PCS). |

"Post-compromise recovery" is therefore **per-connection, ML-KEM-driven, bounded
by the own-send rotation interval** — not the classical "PCS after one
round-trip". Do not restate the one-round-trip claim.

## Not current guarantees (future work)

- An independent, actively-ratcheting classical X25519 PCS layer (the DH ratchet
  is inert; would require wiring the receive key parser and advancing the send
  root — a cryptographic-design change, out of scope for corrective hardening).
- Cross-connection ratchet continuity (deliberately not done: fresh per
  connection is the model).
- Unifying the post-pairing `PcsStream*` continuation onto ZWF framing and
  invariants (or retiring it), after which Mode 3-Full persistence can be
  removed entirely; until then close-time persistence is minimised (no retained
  private keys) rather than eliminated.
- Removal of the dead `resumeSession` / `persistedMode3Full` legacy path (cleanup).

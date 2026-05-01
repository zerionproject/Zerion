# B.4 onionwrapper concurrent-services API

**Status:** design note for the upstream onionwrapper fork (Phase 3 of the B.4 plan). Not implemented in the Briar `org.briarproject:onionwrapper-core:0.1.4` library; needs to be added before Android-side B.4 (Phase 4) can land.

**Sibling docs:**
- iOS-mirrored full plan: `Zerion Ios/docs/wire/B4_PLAN.md`
- Protocol design: `Zerion Ios/docs/wire/B3_B4_SPEC_v1.5.0.md` Part 2

---

## 1 — Why

The current `onionwrapper` API exposes one onion service per Tor process — `tor.publishHiddenService(port, port, privKey)` returns a single `HiddenServiceProperties`, and `tor.removeHiddenService(onion)` clears it. B.4 needs **two** services running concurrently during the `announcing` rotation phase: the old `onion_current` (still listening for peers who haven't migrated yet) plus the new `onion_next` (listening for peers who have).

The Tor protocol itself (`ADD_ONION` / `DEL_ONION`) supports multiple concurrent services per controller connection — this is purely a wrapper-API gap.

---

## 2 — Where the PR goes

Upstream Briar at `org.briarproject:onionwrapper-core` (and the Android variant `:onionwrapper-android`). Per project decision, Zerion is forking this library into the Zerion org rather than waiting on a Briar upstream review cycle. Once the fork is published (Maven Central or JitPack), update [bramble-core/build.gradle:12](../../bramble-core/build.gradle#L12) and [bramble-android/build.gradle:61](../../bramble-android/build.gradle#L61) coordinates to point at the fork.

Today's call sites in this tree:
- [TorPlugin.java:231](../../bramble-core/src/main/java/org/briarproject/bramble/plugin/tor/TorPlugin.java#L231) — `tor.publishHiddenService(port, port, privKey)`
- [TorPlugin.java:405](../../bramble-core/src/main/java/org/briarproject/bramble/plugin/tor/TorPlugin.java#L405) — `tor.removeHiddenService(onion)`

These keep working as the v1 single-onion API. The PR adds a v2 concurrent-services API alongside.

---

## 3 — Required additions

```java
/**
 * Add a hidden service at the given port using the given private key.
 * Safe to call multiple times against the same Tor process — each call
 * registers an additional concurrent service.
 *
 * Idempotent on the (privKey, port) pair: if the service is already
 * registered the existing onion is returned without a second ADD_ONION
 * dispatch.
 *
 * @param privKey  v3 onion service private key (Ed25519, 64 bytes
 *                 expanded form expected by Tor's ADD_ONION).
 * @param port     onion-side virtual port.
 * @return the .onion address Tor assigned (56 chars base32, no scheme,
 *         no trailing ".onion" — caller appends).
 * @throws IOException on Tor controller error or descriptor publication
 *                     timeout (90s per Tor's HSDir convention).
 */
String addHiddenService(byte[] privKey, int port) throws IOException;

/**
 * Remove a previously-added hidden service. Safe to call against a
 * service that was never added or was already removed (no-op). Other
 * concurrently-registered services continue running.
 *
 * @param onion the 56-char base32 .onion address (without ".onion" suffix)
 *              previously returned by {@link #addHiddenService}.
 */
void removeHiddenService(String onion) throws IOException;

/**
 * Snapshot of currently-registered hidden services. Useful for crash-
 * recovery — on next startup, the rotation state machine can compare
 * its persisted "expected onions" against this set and reconcile.
 *
 * @return immutable set of .onion addresses currently bound.
 */
Set<String> getRegisteredHiddenServices();
```

---

## 4 — Semantic contract

| Invariant | Why it matters for B.4 |
|---|---|
| `addHiddenService` is concurrency-safe — two threads calling at once produce two distinct services, no race on the controller socket | The opportunistic trigger fires from the sync-session-start hook; multiple sessions can race the rotation begin path (single-flight guard prevents the *intent*, but the actual `ADD_ONION` should be safe regardless) |
| `removeHiddenService` does not affect other registered services | Retirement step retires `onion_current` while `onion_next` keeps listening. Cross-contamination here would drop active connections from migrated peers |
| Adding the same `(privKey, port)` twice is idempotent and returns the same onion | Crash-recovery: on app restart with an in-progress rotation, replaying the publisher state machine must not produce a *different* onion than what was already published |
| Removing a non-existent onion is a silent no-op | Same crash-recovery reason — re-running the retirement step after a crash mid-promotion must be safe |
| Existing single-onion API (`publishHiddenService` / `removeHiddenService(String)`) keeps its current behaviour byte-for-byte | Any existing Briar consumer of the library must not regress |

---

## 5 — Implementation notes for whoever opens the fork PR

The current single-onion implementation likely tracks a `HiddenServiceProperties` field as a singleton and clears it on `removeHiddenService`. Two ways to extend:

**(a) Replace singleton with a `ConcurrentHashMap<String, HiddenServiceProperties>` keyed by onion address.** The single-onion API becomes a thin wrapper that maintains the "first-added" entry as a special case for back-compat. Cleanest separation; some existing tests assume `getCurrentHiddenService()` returns the one onion and would need to grow a "primary onion" semantic.

**(b) Add the multi-service map alongside the existing singleton.** The singleton field continues to track the v1-API service; the new map tracks v2-API services. Each API operates on its own state. Less invasive but doubles the bookkeeping.

Recommend **(a)** — the underlying Tor socket has no concept of "the" onion, so the wrapper shouldn't either. Tests that assume singleton behaviour are probably testing wrapper behaviour rather than Tor behaviour and should be updated.

Threading: Tor controller socket is single-threaded, so `addHiddenService` / `removeHiddenService` calls need to serialize onto the controller's command queue. The current implementation likely already does this for `publishHiddenService`; the new methods reuse that mutex.

Descriptor publication: `ADD_ONION` returns immediately, but the v3 onion isn't reachable until its descriptor is published to HSDir nodes (~30–90 seconds). The wrapper should expose this delay rather than hide it — the rotation receiver state machine needs to know "new onion isn't dialable yet, fall back to old" and that's easier if `addHiddenService` returns once `ADD_ONION` returns rather than blocking until first reachable.

---

## 6 — Verifying against the upstream once the PR lands

1. Bump `onionwrapper_version` in [build.gradle:38](../../build.gradle#L38) to the fork's first concurrent-services release.
2. Smoke test in `TorPluginTest`: `addHiddenService(k1, 9001)`, `addHiddenService(k2, 9002)`, dial both from a second Tor process, both succeed, `removeHiddenService(o1)`, dial `o2` continues to work, dial `o1` fails.
3. Phase 4 implementation can then proceed — no further wrapper changes needed for B.4.

---

*Drafted 2026-04-30 in support of Phase 3 of the joint iOS/Android B.4 onion rotation work.*

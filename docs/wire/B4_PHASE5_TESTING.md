# B.4 Phase 5 — Cross-platform interop testing

**Branch:** `b4-rotation` (gate ON)
**Master:** untouched (gate OFF, no behavioural change). If `b4-rotation` build breaks or rotation misbehaves, `git checkout master` and run without rotation.

---

## 1 — What's wired

| Subtask | Done | Notes |
|---|---|---|
| 4.1 Orchestrator state machine | ✅ | `B4OnionRotation` — single-flight via lock, crash-safe via promotion sentinel |
| 4.2 Trigger on sync session | ✅ | `TorPlugin.eventOccurred` listens for `ContactConnectedEvent` and calls `evaluateTrigger()` + `onPeerSyncSessionEstablished(cid)` |
| 4.3 Tor publish/remove | ✅ | `TorPlugin` implements `B4OnionRotation.B4TorAdapter`; orchestrator mints + retires onions via the existing `org.briarproject:onionwrapper-core:0.1.4` API (no library patch needed — it already supports concurrent onions natively) |
| 4.4 Publisher: announce outbound | ✅ | `beginRotation` calls `mergeTorLocalProperties` with `tor.onion3_next` + `tor.onion3_announced_at_ms`; TPM propagates to all peers automatically on next sync session |
| 4.5 Receiver: announce inbound | ✅ | `TransportPropertyManagerImpl.incomingMessage` parses Tor-transport updates and routes `tor.onion3_next` to `b4OnionRotation.onAnnounceReceived` |
| 4.6 Dialer prefers pending onion | ✅ | `TransportPropertyManagerImpl.getRemoteProperties` substitutes pending onion in the returned `tor.onion3` for Tor-transport queries |
| 4.7 Completion + retire | ✅ | `evaluateCompletion` retires when all peers `MIGRATED` or 90 days elapsed |
| 4.8 Force-expire timer | ✅ | `evaluateForceExpire()` callable; integrated into `evaluateCompletion` |
| 4.9 Manual "Rotate Now" UI | ✅ | Settings → Security → Privacy → "Rotate onion now". Wired in `SecurityFragment.showRotateOnionDialog`, forceRotate runs on `@IoExecutor`. |
| 4.10 Gate | ✅ | `B4_ROTATION_ENABLED = true` on this branch, `false` on master |
| 4.11 Per-contact storage keys | ✅ | All `b4.*` keys field-encrypted under SQLCipher master key via `FieldEncryption` (AES-256-GCM, byte-shape parity with iOS CryptoKit `SealedBox.combined`) |

---

## 2 — Building the debug APK

```
git checkout b4-rotation
./gradlew :zerion-android:assembleDebug
```

APK output: `zerion-android/build/outputs/apk/debug/zerion-android-debug.apk`

Install on Pixel 10 Pro:
```
adb install -r zerion-android/build/outputs/apk/debug/zerion-android-debug.apk
```

To verify the gate is actually ON (not a stale APK):
```
adb shell pm path com.professor.zerion
adb pull <path-from-above>/base.apk /tmp/zerion.apk
$ANDROID_HOME/build-tools/35.0.0/dexdump -d /tmp/zerion.apk 2>/dev/null \
  | grep -B1 "B4_ROTATION_ENABLED" | head
```

If the bytecode shows `B4_ROTATION_ENABLED = true`, the gate is correctly flipped.

---

## 3 — Triggering a rotation without waiting 5 days

The opportunistic trigger fires once a contact has been online and `days_since_last_rotation >= ROTATION_MIN_DAYS` (5). For Phase 5 cross-platform testing, you don't want to wait 5 real days.

**Option A — manual rotate button (preferred).** On the device: open the app → ☰ menu → Settings → Security → Privacy → tap "Rotate onion now" → confirm. `forceRotate()` runs on the IO executor, mints a fresh onion via Tor, advertises it to all contacts, and shows a toast. Ignores the 5/7/14 day window — fires on demand. Use this for every Phase 5 scenario that needs an immediate rotation.

**Option B — temporarily lower the trigger window in B4Constants.** Only needed if you want to test the *automatic* trigger path (i.e. "rotation fires on its own when conditions are met") rather than the manual path. On the `b4-rotation` branch, edit [bramble-api/.../api/plugin/B4Constants.java](../../bramble-api/src/main/java/org/briarproject/bramble/api/plugin/B4Constants.java):

```java
int ROTATION_MIN_DAYS = 0;
int ROTATION_MAX_DAYS = 1;
int FORCE_EXPIRE_DAYS = 1;
```

Rebuild + install. Any `ContactConnectedEvent` will then trigger a rotation (because days_since=0 already meets the 0-day floor). After Phase 5 testing succeeds, **revert these constants to 5/14/90 before merging to master**. The iOS team must agree to mirror any temporary debug values they're using too — the values don't go on the wire so they don't have to match exactly, but they do affect when each side fires.

---

## 4 — Phase 5 test scenarios

Each scenario assumes Pixel 10 Pro running this `b4-rotation` debug APK paired with iPhone 17 Pro running iOS `b4-rotation-test` (also gate ON, both with debug-shortened days).

### Scenario 1: Both peers online during rotation
1. Both come online and pair as usual.
2. Wait for any sync session (a normal message exchange is enough). Trigger fires on `ContactConnectedEvent`.
3. Android logs should show `b4OnionRotation.evaluateTrigger` → `executeRotation` (set the orchestrator to log behind `BuildConfig.DEBUG` if you want logs; production builds stay silent per Zerion's no-logging-in-prod rule).
4. Android publishes `tor.onion3_next` via TPM update record. iOS receives it.
5. iOS migrates: dials Android on the new onion. Android marks iOS `MIGRATED`.
6. All peers migrated → Android retires old onion via `DEL_ONION` and promotes new → current.

Verify: `tor.onion3` Android advertises after rotation matches the onion iOS dials successfully.

### Scenario 2: One peer offline during rotation
1. Android pairs with iOS.
2. iOS goes offline.
3. Android triggers rotation while alone (force-expire path; if testing with `ROTATION_MAX_DAYS = 1`, both onions stay live until iOS returns).
4. iOS comes back, dials Android. Old onion still listening — connection succeeds. Android sends announce on this session.
5. iOS migrates on next session.

Verify: contact never lost. Android still has both onions running until iOS comes back.

### Scenario 3: iOS rotates, Android receives announce
1. iOS triggers rotation (manual button or short window).
2. Android sync session brings new property update with `tor.onion3_next`.
3. Android `TransportPropertyManagerImpl.incomingMessage` parses the announce, routes to `b4OnionRotation.onAnnounceReceived`.
4. Next time Android dials iOS, `getRemoteProperties` substitutes the pending onion.
5. Android dials successfully on iOS's new onion.

Verify: Android persists `b4.contact_onion3_pending.<cid>` (encrypted), and the dial-target onion in `getRemoteProperties` matches what iOS published.

### Scenario 4: Force-expire (debug-shortened to 1 day)
1. With `FORCE_EXPIRE_DAYS = 1` in B4Constants, pair Android with iOS.
2. Android triggers rotation, iOS goes offline.
3. After 24h, Android's old onion retires anyway (force-expire) even though iOS never migrated.

Verify: `b4.alice_onion3_current` after retirement is the new onion; `b4.alice_onion3_next` cleared; phase back to `IDLE`.

### Scenario 5: Crash-safe atomic promotion
1. Trigger rotation.
2. Wait for all peers to migrate.
3. Just before completion (set a breakpoint in `executePromotion` between sentinel-write and final state-cleanup), force-stop the app: `adb shell am force-stop com.professor.zerion`.
4. Restart the app.
5. `TorPlugin.publishHiddenService` calls `b4.resumeIfPromotionInterrupted()` which re-runs `executePromotion` idempotently.

Verify: the partial-promoted state recovers correctly — the new onion ends up as `alice_onion3_current`, sentinel cleared.

---

## 5 — Logs / instrumentation

Per Zerion project rule, no production logging. For Phase 5 internal testing, if you want orchestrator visibility, add a temporary `BuildConfig.DEBUG` gate in `B4OnionRotation` similar to how `[B3]` logs were added during B.3 Phase 3 testing. Filter:

```
adb logcat | grep B4
```

Strip the `BuildConfig.DEBUG`-gated calls before merging to master (same playbook as B.3).

---

## 6 — Reverting

If `b4-rotation` testing reveals problems and you want to ship v1.5 without B.4:

```
git checkout master
./gradlew :zerion-android:assembleDebug
```

Master has B.3 + v5.1 strict-reject + onionwrapper concurrent-services-confirmed-not-needed but `B4_ROTATION_ENABLED = false` everywhere. Full v1.5 functionality minus B.4. Phase 5 result feeds the go/no-go decision for the joint v1.5 launch.

---

*Drafted 2026-04-30. Update after each successful Phase 5 scenario.*

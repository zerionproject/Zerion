# Multi-Profile — Android design + iOS parity handoff (shipped; current as of v2.0.x)

iOS parity for the SimpleX-style multi-profile feature shipped on Android (originally landed for v1.6; shipped and current as of v2.0.x) in commits `ebf1c01` (phase 1), `2c043c1` (phase 2), `46475b7` (phases 3–5).

## Design choices (decided with user, must match on iOS)

1. **Password-only hidden profiles.** The login screen never shows profile names or a count. The user types one password; the app tries each stored profile in turn until one decrypts.
2. **Restart-on-switch.** Switching profile is a clean logout + relaunch — services stop, DB closes, process exits, fresh launch picks the new profile via password match.
3. **Secure wipe on delete.** Deleted profile data is overwritten with zeros + fsync'd before unlink (same pattern we use for voice/video cache).

## On-disk layout

```
<app private files dir>/
  profiles/
    <profile-uuid-1>/
      db/        ← SQLCipher DB for this profile
      key/
        db.key             ← password-encrypted SecretKey (the DB key)
        db.key.bak         ← backup
        pending_identity_name   ← (only present until first sign-in to a freshly-created profile)
      tor/       ← per-profile Tor data dir (own onion key)
    <profile-uuid-2>/
      …
  login.lockout   ← global failed-attempt counter (NOT scoped per profile)
```

Per-profile keys are independent: each has its own Argon2id salt (baked into the ciphertext blob) and its own SecretKey, so compromise of one profile's password cannot decrypt another.

## Login flow (must match on iOS)

```
On signIn(password):
    if lockoutActive: throw INVALID_CIPHERTEXT (no detail)
    profileIds = sortedListOf(profiles/*)
    if profileIds.isEmpty: recordFailedAttempt; throw INVALID_CIPHERTEXT
    for id in profileIds:
        setActiveProfileId(id)
        hex = read profiles/<id>/key/db.key (or .bak)
        if hex == null: continue
        try:
            ciphertext = fromHex(hex)
            plaintext  = decryptWithPassword(ciphertext, password)
            key        = SecretKey(plaintext)
            if pending_identity_name exists:
                name = readPendingIdentityName(id)
                identity = identityManager.createIdentity(name)
                identityManager.registerIdentity(identity)
                clearPendingIdentityName(id)
            setDatabaseKey(key)
            resetLockout()
            return
        catch DecryptionException: continue
    restoreActiveProfileId(previous)
    recordFailedAttempt()
    throw INVALID_CIPHERTEXT
```

Three points worth re-reading:
- **Lockout is global**, kept in `<filesDir>/login.lockout`, NOT inside any profile dir. This prevents an attacker resetting the counter by targeting different profiles in turn.
- **No success signal indicates *which* profile matched** to anyone observing the screen — the UI just opens the home view of "the" profile.
- **Pending-identity materialisation** lets profile creation happen at the moment the user submits the create-profile dialog (no DB open required at that point), and defers the actual identity write to the first sign-in into the new profile, where the DB *is* open. This is what avoids dirty Dagger-graph state on Android.

## Create profile (no in-process restart needed)

```
scheduleProfileCreation(displayName, password):
    newId = UUID
    create profiles/<newId>/ with db/, key/, tor/ subdirs
    setActiveProfileId(newId)        ← temporary, only to reuse storeEncryptedDatabaseKey
    freshKey = generateSecretKey()
    ciphertext = encryptWithPassword(freshKey, password)
    write ciphertext (hex) into profiles/<newId>/key/db.key (+ .bak)
    write displayName into profiles/<newId>/key/pending_identity_name
    setActiveProfileId(previousActive)
    zeroize freshKey buffer
    return newId
```

`scheduleProfileCreation` runs while the user is signed in to the previous profile. It does NOT touch the current DB or services. The new identity will be materialised the next time someone successfully signs into the new profile.

## Switch profile

Just a normal sign-out: `signOut(removeFromRecentApps=true, deleteAccount=false)`. On Android that runs Bramble's `LifecycleManager.stopServices()` → DB close → activity tear-down via the existing exit path. The user reopens the app and types the target profile's password. There's no special "switch profile" mode — the password-only login already handles it.

iOS parity: present the same logout-and-relaunch path, no "live switch."

## Delete profile

```
deleteActiveProfile():
    id = activeProfileId
    secureWipeRecursive(profiles/<id>/)        ← overwrite-then-delete every file
    // caller signs out + relaunches after this returns
```

`secureWipeRecursive` on each file:
- open RandomAccessFile(rw)
- write zeros up to file's current length
- `fd.sync()`
- close + delete

Cap at 200 MB per file (skip the zero-fill for anything larger; the regular delete still runs — files this big in a profile dir are unexpected). Match this cap on iOS or pick a similar one consistent with your storage layout.

After wipe, sign out and relaunch. If it was the last profile, the next launch will see an empty `profiles/` directory and route to onboarding as a fresh install.

## Backward compatibility (single-profile installs)

Legacy installs had `<filesDir>/db/`, `<filesDir>/key/`, `<filesDir>/tor/`. On first launch with the new code, the migration:

1. If `<filesDir>/profiles/` already exists → already migrated, skip.
2. Else, if any of the legacy dirs has contents → atomically `renameTo` each into `<filesDir>/profiles/default/{db,key,tor}/`. If `renameTo` fails (cross-volume on weird Android setups), fall back to recursive copy + delete.
3. Else → fresh install; just create empty `<filesDir>/profiles/`.

iOS should do the equivalent on its own data root and pick a stable "default" profile id (we used the literal string `"default"`).

## Files involved (Android, for reference)

- `bramble-android/.../account/ProfileManager.java` — paths, listing, migration, secure wipe
- `bramble-android/.../account/AndroidAccountManager.java` — multi-profile signIn, scheduleProfileCreation, deleteActiveProfile, pending-identity materialisation, global lockout
- `bramble-core/.../account/AccountManagerImpl.java` — base class, no longer caches key-file paths; reads them fresh from `databaseConfig` each call
- `zerion-android/.../AndroidDatabaseConfig.java` — delegates to ProfileManager on each `getDatabase*Directory()`
- `zerion-android/.../AppModule.java` — provides ProfileManager + the path-aware DatabaseConfig + per-profile @TorDirectory
- `zerion-android/.../settings/ProfilesFragment.java` + `SettingsActivity.requestProfileSignOut()` — Settings UI for create / switch / delete

## Threat-model notes (call these out in iOS code review)

- Wrong-password feedback time scales with profile count (N × Argon2id per failed attempt). This is intentional — guessing is slow, and the time-leak of N is acceptable since the device's filesystem already reveals profile count to an attacker with root/forensic access.
- Each profile's onion key is independent → contacts in profile A cannot correlate it with profile B's onion.
- An attacker who briefly observes the unlocked phone screen sees only the active profile; no UI hint exists that other profiles are present (assuming the user is not on Settings → Profiles).
- Deleting the active profile while logged in is supported. The wipe runs first, then the standard signOut path closes services. There is a brief window where the DB file is gone but services haven't yet finished shutting down — Bramble's DB layer treats this as a hard crash on next access, which is fine.

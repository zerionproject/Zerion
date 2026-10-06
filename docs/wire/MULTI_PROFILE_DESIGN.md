# Multi-Profile - Android design + iOS parity handoff

iOS parity for the SimpleX-style multi-profile feature shipped on Android (originally landed for v1.6; current as of v2.0.x) in commits `ebf1c01` (phase 1), `2c043c1` (phase 2), `46475b7` (phases 3 to 5). The isolation described below (no enumeration inside a session, per-profile vault, wallets, stickers and settings, password-collision refusal) is in 3.0.15, not yet released; 3.0.14 and earlier listed every profile by name in Settings, Profiles and shared the vault, wallet files, stickers and per-contact settings across profiles. iOS has not been changed for 3.0.15.

## Design choices (decided with user, must match on iOS)

1. **Password-only hidden profiles.** No screen, before or after sign-in, shows another profile's name, identifier or a count of profiles. The user types one password; the app tries each stored profile in turn until one decrypts. A hidden profile is reached only by entering its own password.
2. **Restart-on-switch.** Switching profile is a clean logout + relaunch - services stop, DB closes, process exits, fresh launch picks the new profile via password match. One process serves one profile: once a profile's session has started, no other profile can be made active in that process (`ProfileManager.startSession`, `setActiveProfileId`).
3. **Everything a profile keeps is the profile's.** The database, its key, its settings outside the database, its vault, its wallet files, its stickers and its channel blobs all live in the profile's directory and are reached only through its session. Deleting a profile deletes them.
4. **Secure wipe on delete.** Deleted profile data is overwritten with zeros + fsync'd before unlink (same pattern we use for voice/video cache).

## On-disk layout

```
<app private files dir>/
  profiles/
    <profile-id>/
      db/        <- SQLCipher DB for this profile
      key/
        db.key, db.key.bak, db.key.state   <- password-encrypted SecretKey (the DB key)
        display_name                        <- sealed with the device metadata key
        pending_identity_name               <- only until first sign-in to a freshly-created profile
      settings/
        profile.prefs  <- the profile's own settings, AES-GCM under a key derived from the DB key
      vault/           <- the profile's vault
      vault.legacy     <- present when the vault came from the device-wide location of an earlier version
      wallet/xmr/      <- the profile's Monero wallet files
      stickers/        <- the profile's stickers
      channel-blobs/   <- the profile's channel attachments
    <profile-id>/
      ...
  tor/               <- Tor's state (caches, guards), one for the device
  login.lockout      <- global failed-attempt counter (NOT scoped per profile)
  password.check.lockout  <- counter of candidate-password checks (profile creation, import, password change), see below
  last_active_profile     <- the last-used profile, sealed; forgotten when that profile is deleted
<no-backup dir>/
  vault/, xmr/       <- device-wide vault and wallet files of earlier versions, until they move (below)
  vault.claim        <- which profile proved it owns the device-wide vault (transitional)
```

Per-profile keys are independent: each has its own Argon2id salt (baked into the ciphertext blob) and its own SecretKey, so compromise of one profile's password cannot decrypt another.

Device-level state that stays shared, by design: Tor's state directory (one guard set per device, so a network observer does not see a second set appear when a second profile signs in; each profile's onion keys stay in its own database), the two throttles, and the settings that act before sign-in or protect the device as a whole: theme, language, text sizes, accent and bubble colour, screenshot protection, hardened mode, USB panic, panic responder, duress password, erase after failed sign-ins, the decoy screen, the app icon, the background connection mode and the sign-in reminder. These stay shared because they are read before any profile has signed in (the sign-in and decoy screens, the app icon, the panic responders) or because they protect the device rather than a conversation, and a profile's own copy would have nothing to be sealed under until that profile signs in. None of these names or counts a profile. The call, typing-indicator, quick-reply and default disappearing-message toggles concern a profile's conversations and are per profile (`@ProfilePrefs`) since 3.0.15; the values an earlier version kept for the device go to the profile that owns the device data.

## Login flow (must match on iOS)

```
On signIn(password):
    if lockoutActive: throw INVALID_CIPHERTEXT (no detail)
    profileIds = sortedListOf(profiles/*), last-used profile first
    if profileIds.isEmpty: recordFailedAttempt; throw INVALID_CIPHERTEXT
    for id in profileIds:                  <- every profile, even after a match
        setActiveProfileId(id)
        hex = read profiles/<id>/key/db.key (or .bak, when the key state vouches for it)
        if hex == null: continue
        try decryptWithPassword(hex, password); remember the first match
    repeat the derivation until it has run at least 2 times (counting derivations actually performed, not profiles tried)
    if matched:
        setActiveProfileId(matched); materialise the pending identity; setDatabaseKey
        startSession(matched)              <- binds settings, vault, wallets, stickers
        resetLockout(); return
    restoreActiveProfileId(previous)
    if the last-used profile's key files are damaged: throw KEY_FILES_DAMAGED (no attempt recorded)
    recordFailedAttempt()
    throw INVALID_CIPHERTEXT
```

Points worth re-reading:
- **Lockout is global**, kept in `<filesDir>/login.lockout`, NOT inside any profile dir. This prevents an attacker resetting the counter by targeting different profiles in turn.
- **The work does not depend on which profile matched, and not on whether a hidden profile exists.** Every profile is tried, and the derivation runs at least twice on every device. Before 3.0.15 the minimum applied only once a second profile had ever existed (a `.multi` marker), so a device that never had one signed in faster; the marker is removed on upgrade. A device with three or more profiles still takes longer. The padding counts derivations actually performed: a profile whose key files are damaged derives nothing and is padded for, and a profile whose key state vouches for its backup costs two derivations (an interrupted write; the next successful sign-in realigns the files). Whether a failed sign-in reports damaged key files, and whether it counts toward the lockout, depends on the last-used profile alone, as it would on a device with one profile.
- **No success signal indicates *which* profile matched** to anyone observing the screen - the UI just opens the home view of "the" profile.
- **Pending-identity materialisation** lets profile creation happen at the moment the user submits the create-profile dialog (no DB open required at that point), and defers the actual identity write to the first sign-in into the new profile, where the DB *is* open.

## Create profile (no in-process restart needed)

```
Settings, Profiles, Create: name + password (twice), then the signed-in profile's password (AccountPasswordGate)
scheduleProfileCreation(displayName, password):
    refuse while sign-in is locked out
    count one password check; refuse while the checks are locked out (LOCKED_OUT, no derivation)
    if password opens any existing profile's key: refuse (PASSWORD_UNAVAILABLE)
    newId = UUID
    create profiles/<newId>/ with db/, key/
    freshKey = generateSecretKey()
    write encryptWithPassword(freshKey, password) into profiles/<newId>/key/ (the active profile is not changed)
    write displayName into profiles/<newId>/key/pending_identity_name and display_name
    return newId
```

`scheduleProfileCreation` runs while the user is signed in to the previous profile. It does NOT touch the current DB or services, and it never makes the new profile active, so the running session keeps resolving its own database and key.

**Password collisions.** Two profiles never share a password, since a sign-in could then reach only one of them. The check is applied to profile creation, to a password change (against every profile but the signed-in one, after the current password has been verified) and to a backup import or transfer received in a session (`importProfile`). It opens every profile's key files with the password, whatever the outcome, through the same derivation, padded to the same minimum, without repairing or changing any file and without making another profile active, so it tells the user exactly what signing in with that password would tell them (that the password opens a profile), at the same cost. The refusal says "This password cannot be used" (an import says "Import failed"), without naming or counting anything.

It is nevertheless a guess at every profile's password by whoever holds the unlocked phone, so two things limit it. Every entry point asks for the signed-in profile's password first (`AccountPasswordGate`: Create profile, the Change password screen, and the export, import, transfer-send and transfer-receive cards of Settings, Backup whenever a session exists; a transfer received during first-run setup has no session and no profile to guess). And every check counts in a throttle of its own, `password.check.lockout` (`AndroidAccountManager.PASSWORD_CHECK`): three checks per day are free, the fourth and every later one are refused for five minutes, doubling, without any derivation, whatever the outcome of the earlier ones. This throttle is separate from the sign-in throttle, which must keep counting only sign-in failures (the erase-after-failures policy acts on it), and it is not reset by a correct account password or a successful sign-in, since entering the decoy's password is exactly what the holder of the phone can do. It is reset only by a day of quiet or by an erase. A check is also refused while sign-in itself is locked out.

## Switch profile

Just a normal sign-out: `signOut(removeFromRecentApps=true, deleteAccount=false)`. On Android that runs the core `LifecycleManager.stopServices()` → DB close → activity tear-down via the existing exit path. Signing out also removes every notification the profile posted, and a new main process removes any notification a process left behind when it ended without signing out. The user reopens the app and types the target profile's password. There's no special "switch profile" mode - the password-only login already handles it.

iOS parity: present the same logout-and-relaunch path, no "live switch."

## Delete profile

```
Settings, Profiles, Delete, then the signed-in profile's password:
    erase the profile's own vault and its keystore key (a device-wide vault no profile has claimed yet is left alone; a claim this profile made on it is withdrawn)
deleteActiveProfile(expectedId):
    if the active profile is not expectedId: refuse
    if no other profile holds a key: write a placeholder profile first
        (a random key sealed under a random password nobody knows)
    else: run the same encryption and discard it, so the deletion takes the same time
    secureWipeRecursive(profiles/<id>/key/)    <- the key files: no password opens the profile from here on
    forget last_active_profile if it names <id>
    caller signs out + relaunches after this returns
next process start (ProfileManager constructor, before any path is resolved):
    secureWipeRecursive every profiles/<dir>/ that holds neither key/db.key nor key/db.key.bak
```

The placeholder means the device looks the same after any deletion: an account still exists, so the app opens on the sign-in screen, and one profile directory is there. Without it, a coercer who has the decoy deleted could tell from the next screen (sign-in or first-run setup) whether another profile remains. On a device with no real profile left, "Forgot your password?" on the sign-in screen resets the app.

The rest of the profile's directory is wiped by the next process rather than by the session that asked for the deletion: that session is still running, and a write that arrives late in it (a settings `apply()`, a channel blob download) would recreate the directory after the wipe and leave a directory with the deleted profile's ciphertext that every later start kept. Any profile directory without a key file is wiped at start, which also covers a profile whose creation died before its key was written and the empty directories earlier versions created. Until the next start the deleted profile's directory still exists without a key; the relaunch alarm the deletion schedules normally starts that process within a second.

`secureWipeRecursive` on each file: open RandomAccessFile(rw), write zeros up to the file's current length, `fd.sync()`, close + delete. Cap at 200 MB per file (skip the zero-fill for anything larger; the regular delete still runs).

## Moving device-wide data of earlier versions into profiles

Versions up to 3.0.14 kept the vault (`no_backup/vault`), the Monero wallet files (`no_backup/xmr`), stickers (`files/stickers`), per-contact chat settings, pinned contacts, channel drafts and mutes and the vault settings once per device, and resolved the channel blob directory and the Tor directory once at app start, which is always the default profile. On the first sign-in after the update (`ProfileStorage`, `ProfileManager`):

- **Settings, stickers, the old plain chat-settings file:** move into the `default` profile, the account the device had before profiles existed; where that profile no longer exists, into the first profile that signs in. Each entry is copied into the profile's settings unless the profile already holds that key, the settings are made durable, and only then are the device-wide copies removed, so a move cut short loses nothing and completes on the next sign-in. Settings that are device-level by design (above) stay where they are. **When more than one profile holds a key at that moment**, the entries keyed by a contact id (`mute_N`, `vibration_N`, `timer_N`), the pinned contacts and the channel drafts are removed without being copied: both databases number their contacts from 1, so an entry kept for another profile's contact N would apply to this profile's contact N, and a draft would show in this profile's composer for a channel both follow. The vault settings (autolock, clipboard, hide content, sort) and the messaging toggles name no contact and are copied. The sticker set then stays in `files/stickers` and moves with the shared vault when a profile claims it (below); on such a device without a shared vault nothing can claim it, so it is deleted at the owner's first sign-in rather than shown to a profile it may not belong to. Devices that upgrade with a single profile are not affected: everything moves into that profile.
- **Vault and Monero wallet files:** on a device with one profile, they move into it at once (`vault.legacy` marks a vault that keeps the original keystore alias). With several profiles nobody can tell whose vault it was, so it stays where it was and every profile sees it, as before the update, until one profile unlocks it with the vault password **and confirms the move**: after the unlock the vault asks, once per session, whether to move the vault into this profile, warning that it will no longer be visible from any other profile (whoever uses another profile would find the vault empty, which shows that another profile exists). "Keep shared" leaves everything as it is and asks again at a later unlock; "Move here" records the profile in `no_backup/vault.claim`, and the vault, its wallet files and the device-wide sticker set move into it at its next sign-in. From then on the other profiles each get a vault of their own. A claim by a profile that no longer holds a key is ignored and removed; deleting the claiming profile withdraws its claim.
- **Channel blobs:** a profile that opens a channel finds that channel's blobs in its own directory, or moves them there from the default profile's directory (the blob directory names are derived from the profile's own secret, so only the profile that holds the channel finds them).
- **Tor state** moves from `profiles/default/tor` to the device's `tor` directory; empty profile directories that earlier versions created for profiles that did not exist, and the `.multi` marker, are removed.

Every profile's vault has its own Android Keystore key (`zerion_vault_master_key_<tag>`, the tag derived from the profile id; the moved device-wide vault keeps `zerion_vault_master_key`), so erasing one vault never destroys another's key. An erase of the whole account removes every vault key.

## Backward compatibility (single-profile installs)

Legacy installs had `<filesDir>/db/`, `<filesDir>/key/`, `<filesDir>/tor/`. On first launch with the new code, the migration:

1. If `<filesDir>/profiles/` already exists → already migrated, skip.
2. Else, if any of the legacy dirs has contents → atomically `renameTo` each into `<filesDir>/profiles/default/{db,key,tor}/`. If `renameTo` fails (cross-volume on weird Android setups), fall back to recursive copy + delete.
3. Else → fresh install; just create empty `<filesDir>/profiles/`.

iOS should do the equivalent on its own data root and pick a stable "default" profile id (we used the literal string `"default"`).

## Files involved (Android, for reference)

- `zerion-core-android/.../account/ProfileManager.java` - paths (never creating a directory for a profile that does not exist), listing, sessions, legacy migration, secure wipe
- `zerion-core-android/.../account/AndroidAccountManager.java` - multi-profile signIn, scheduleProfileCreation and importProfile with the collision check, changePassword, deleteActiveProfile with the placeholder, pending-identity materialisation, global lockout
- `zerion-core/.../account/AccountManagerImpl.java` - base class; reads key-file paths fresh from `databaseConfig` each call
- `zerion-android/.../profile/ProfileStorage.java` - per-profile settings, vault location, wallet context, stickers, moving device-wide data
- `zerion-android/.../AndroidDatabaseConfig.java` - delegates to ProfileManager on each `getDatabase*Directory()`
- `zerion-android/.../AppModule.java` - provides ProfileManager, the path-aware DatabaseConfig, the device @TorDirectory, the per-profile `@ProfilePrefs`, VaultManager and the wallet context
- `zerion-android/.../settings/ProfilesFragment.java` + `SettingsActivity.requestProfileSignOut()` - Settings UI for create / switch / delete, showing the signed-in profile only

## Threat-model notes (call these out in iOS code review)

- Inside a session nothing lists, counts or names another profile; the Profiles screen shows the signed-in profile and says how to reach another one without saying whether there is one.
- Wrong-password feedback time scales with profile count (N × Argon2id per failed attempt, at least 2). One or two profiles take the same time; three or more take longer.
- Each profile's onion key is independent → contacts in profile A cannot correlate it with profile B's onion. Tor's guard state is shared by the device's profiles.
- Residual, root or forensic access only: the number of directories under `profiles/` (one per profile, plus at most one placeholder), the per-profile vault keystore aliases, the encrypted `last_active_profile` hint, the two throttle files and a transitional `vault.claim` are visible to code running as the app or to an image of its data. They name no profile and hold no content, but they do show that more than one profile exists. A design that hides the count from such an attacker needs fixed-size padding or a single opaque container and is not implemented.
- Residual, legacy devices: on a device that upgraded with several profiles, the shared vault stays visible to every profile until one of them claims it, and the move it then makes is visible to the others (the warning says so before the move); the shared sticker set is dropped or moves with the vault; contact-keyed settings, pins and drafts of the old installation are lost. A profile whose stored key still uses older KDF parameters or no strengthener costs a different amount per derivation than the padding derivation until its next successful sign-in upgrades it. The padding cannot shorten a sign-in: a profile whose key state vouches for its backup (an interrupted write) costs two derivations until a sign-in realigns its files.
- Residual, in-session password checks: the holder of an unlocked phone who also knows that profile's password gets three candidate-password checks per day through profile creation, import, transfer or password change, each telling whether the candidate opens some profile; the fourth and later ones are refused for five minutes, doubling. Without that password none, since every entry point asks for it first.
- Deleting the active profile while logged in is supported: the key files are shredded at once and the standard signOut path closes services; the rest of the directory is wiped by the next process start, so the database stays intact (and unopenable) until then. If the relaunch does not happen the directory, without any key, waits for the next start.

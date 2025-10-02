# Vault Feature - Implementation Todo List

Below is a **developer-facing design** for a Briar "**Vault**" feature that is **encrypted, isolated from the rest of the app**, reachable from the hamburger menu under *Private groups*. Optimized for **safety-by-default**, minimal metadata, and Android hardening.

---

## Vault — Secure Design & Implementation Plan

### 0) Threat model (assumptions)

* Device may be seized while *unlocked* or *locked*.
* Adversary can read the filesystem and app data; cannot extract TEE/StrongBox keys without user auth.
* We protect **contents + metadata** (filenames, types, thumbnails, EXIF), and resist **cloud/backups leakage**.
* No server/cloud sync for the vault by default (local-only). Optional **user-initiated encrypted export**.

---

### 1) UX flow

* Entry: Hamburger menu → **Private groups** → **Vault**.
* **First open**: ask user to set a **Vault password** (optionally enable **Biometric unlock**).
* **Unlock screen** each time Vault is opened or after auto-lock timeout.
* Inside Vault:
  * **Notes**: create/read; default encrypted; *optional per-note extra PIN/password*.
  * **Gallery**: save received photos/videos **with all metadata stripped**; show only encrypted thumbnails.
  * **Documents**: add/receive; stored encrypted.
* Settings: change password, enable/disable biometrics, set auto-lock timer, export/import encrypted backup, "panic wipe".

---

### 2) Key hierarchy (defense-in-depth)

**Two factors by default**: device-bound key + user secret.

* `K_store` — **Android Keystore** RSA/ECDH or AES key (StrongBox if available, **auth-bound** with setUserAuthenticationRequired, timeout e.g. 15–60s).
* `PW_user` — user's Vault password (never stored).
* `KDF(PW_user, salt_v)` → `K_pwd`: **Argon2id** with high memory (e.g., m=192–256MB, t=2–3, p=1; tune per device). Store `salt_v` + params alongside the vault header.
* `HKDF( K_pwd ⊕ wrap(K_store), "vault master" )` → `K_vm`: **Vault Master Key** (256-bit).
  * `wrap(K_store)` = a random secret unwrapped by Keystore (so both device **and** password are required).
* **Per-item keys**: For every note/file/photo/video, generate random `K_item` (256-bit).
  * Store `Enc_{K_vm}(K_item || item_nonce || version)` in the item header (envelope encryption).
* **Content encryption**: `XChaCha20-Poly1305` (AEAD) for files/notes (great nonce-misuse resistance; large nonces).
  * For large files: **chunked encryption** (e.g., 1–4 MB chunks), each with unique subkey from `HKDF(K_item, chunk_index)`; authenticate an overall **manifest**.

**Optional per-note extra password/PIN**

* `K_note_pwd = Argon2id(PW_note, salt_note, lowered parameters)`
* Wrap `K_item` as: `Enc_{K_note_pwd}( Enc_{K_vm}(K_item …) )`.
* Unlock path: require both Vault unlock and note-level secret.

**Biometric unlock**

* On opt-in, generate a random `token_bio` and protect it with an **auth-bound Keystore key** through `BiometricPrompt`.
* `token_bio` is used to unwrap `wrap(K_store)` **without typing PW_user**, *only if* the user recently authenticated biometrically.
* Always allow **fallback to password**; never store PW_user.

---

### 3) Storage & isolation

* **App-internal storage only** (`/data/data/<pkg>/files/vault/...`).
* `android:allowBackup="false"` and put vault under `noBackupFilesDir` if you absolutely must avoid OS-level backups.
* **No external storage**, no MediaStore publishing.
* Room/SQL layer: either **SQLCipher** or store **opaque blobs** (encrypted Protobuf/CBOR) in a simple file-tree:

  ```
  vault/
    header.v1 (salt_v, KDF params, version, feature flags)
    items/
      <uuid>/
        header.bin (AEAD)
        content.bin (AEAD; chunked)
        thumb.bin (AEAD; tiny)
  ```
* **Thumbnails** are generated **after decryption in-memory** and then **re-encrypted** to `thumb.bin`. Never write plaintext thumbnails to disk.

---

### 4) Metadata minimization

* **File/Note names**: never store human-readable names on disk. UI names live only in encrypted headers.
* **EXIF stripping** for images/videos:
  * Decode → re-encode (e.g., JPEG → JPEG or WebP lossless for thumbs) with **no EXIF**.
  * For videos, remux/re-encode streams and **drop all metadata**; set creation time to 0 or a randomized fixed epoch **inside** the encrypted header, not on filesystem.
* **Uniform encoding parameters** to avoid fingerprinting (e.g., images to WebP or JPEG quality 85; videos to H.264 preset X; audio in notes to Opus 16 kHz mono if relevant).
* **Timestamps**: store only inside encrypted headers; filesystem mtime/ctime normalize (e.g., set to a constant or current time for every write).

---

### 5) Receiving media "into Vault"

When a user chooses "Save to Vault" from a chat:

1. Decrypt the message payload in memory.
2. **Never** write the plaintext to shared storage.
3. Strip metadata (image/video pipeline).
4. Encrypt with new `K_item` and write directly to `content.bin`.
5. **fsync** after write; zero/temp buffers; delete any temp files if used.

---

### 6) UI/OS hardening

* Set **FLAG_SECURE** on Vault activities (no screenshots/recents preview).
* **Auto-lock** the vault on: app background, screen off, or inactivity timeout (e.g., 60s default, user-configurable).
* **Keyboard leakage**: use **`TYPE_TEXT_VARIATION_VISIBLE_PASSWORD`** or `setPrivateImeOptions` to hint IMEs not to learn; consider **in-app secure keyboard** for password fields if your user base needs it.
* **Clipboard**: never auto-copy sensitive data; if user explicitly copies, set **`clipDescription.setExtras()`** with `SOURCE` and **expire** after N seconds (API 33+ supports auto-clear).
* **Crash logs**: ensure no plaintext content in logs; add lint rule or strict Timber tree to redact.
* **Backups**: besides `allowBackup=false`, opt out of **Auto Backup** and **Key/Device transfer** paths.

---

### 7) Passwords & rate limiting

* Password entry: **constant-time** comparison; scrypt/Argon2id KDF on-device.
* On failure: **exponential backoff** (e.g., 1s, 2s, 4s… capped), **no timing oracle**; optional **n-of-m** failures → *panic wipe* (off by default).
* Persuade strong passwords with a **passphrase UX** (diceware-like hints), show **entropy meter**, but do not persist checks.

---

### 8) Export/Import (user-initiated only)

* Export produces a **single encrypted bundle** (`vault-export.vN`) containing:
  * header (public: version only), encrypted manifest, encrypted items.
* Derive export key from **user-supplied passphrase** with **Argon2id(hi-mem)**; then encrypt bundle with **XChaCha20-Poly1305**.
* Allow **Shamir Secret Sharing** (optional power-user mode) to split the export key.
* Import validates **MAC**, version, and **per-item hashes** before commit.
* Never auto-upload; user chooses destination (share sheet). Warn about cloud risks.

---

### 9) Testing & verification checklist

* Unit tests for: KDF params, envelope encryption, chunking, rekey/rotation, metadata scrubbing.
* Instrumented tests: screen capture blocked, backgrounding locks vault, temp files never plaintext.
* Fuzz: item header/manifest parsers.
* Side-channel checks: timing on password failure, clipboard, notifications (vault must not show content in notifications).
* Key rotation path: support **rekey** when the user changes `PW_user` (derive new `K_vm`, rewrap item keys **streaming**).
* "Compromised device" drill: confirm that without **both** Keystore and password, data is unrecoverable.

---

### 10) Minimal Kotlin-ish sketches

#### Create/Unlock Vault

```kotlin
// Pseudocode – uses libsodium for Argon2id & XChaCha20-Poly1305.
fun deriveKvm(password: CharArray, header: VaultHeader): ByteArray {
    val kPwd = argon2id(
        password = password,
        salt = header.saltV,
        memBytes = header.kdfMemBytes, // e.g., 256 * 1024 * 1024
        iters = header.kdfIters,       // e.g., 3
        parallel = 1,
        outLen = 32
    )
    val unwrapped = unwrapWithKeystore(header.wrapKeystoreBlob) // requires user auth (StrongBox if possible)
    return hkdfSha256(kPwd.xorInPlace(unwrapped), "vault master")
}
```

#### New item (file/note)

```kotlin
fun storeItem(plaintext: ByteArray, kVm: ByteArray): ItemRefs {
    val kItem = randomBytes(32)
    val (cipher, nonce) = aeadXChaCha20PEncrypt(plaintext, aad = MANIFEST_AAD, key = kItem)
    val headerEnc = aeadXChaCha20PEncrypt(
        data = concat(kItem, nonce, VERSION),
        aad = HEADER_AAD,
        key = kVm
    )
    writeSecure(itemHeaderPath, headerEnc)
    writeSecure(itemContentPath, cipher)
    return ItemRefs(itemId, headerEncDigest, contentDigest)
}
```

#### Optional note-level password/PIN wrap

```kotlin
fun wrapItemWithNotePwd(headerEnc: ByteArray, notePwd: CharArray, saltNote: ByteArray): ByteArray {
    val kNote = argon2id(notePwd, saltNote, memBytes = 64*1024*1024, iters = 2, parallel = 1, outLen = 32)
    return aeadXChaCha20PEncrypt(headerEnc, aad = NOTE_WRAP_AAD, key = kNote).ciphertext
}
```

#### EXIF stripping (concept)

```kotlin
fun stripImageMetadata(input: ByteArray): ByteArray {
    val bmp = decodeToBitmap(input)     // in-memory
    return encodeJpeg(bmp, quality = 85, stripAllMetadata = true)
}
```

#### Secure Activity flags

```kotlin
override fun onCreate(...) {
    window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
}
```

---

### 11) Parameters to lock in (defaults)

* **Argon2id**: m=256MB, t=3 (fallback to 128MB/2 on low RAM), p=1.
* **AEAD**: XChaCha20-Poly1305; 32-byte keys; 24-byte nonces.
* **Auto-lock**: default 60s inactivity; lock on background.
* **StrongBox**: require if available; else device TEE; set `setUserAuthenticationRequired(true)` and reasonable timeout.
* **Wipe**: optional after 10 consecutive failures + exponential backoff.

---

### 12) What *not* to do

* No plaintext filenames, thumbnails, or temp files.
* No external storage or MediaStore entries.
* No silent cloud backups.
* No variable codec/quality per item (fingerprinting).
* No background services that touch vault content while locked.

---

## Implementation Todo List

### Phase 1: Core Infrastructure
- [ ] Create vault package structure (`com.professor.zerion.android.vault`)
- [ ] Implement Android Keystore integration with StrongBox detection
- [ ] Add Argon2id JNI bindings or use existing library
- [ ] Implement XChaCha20-Poly1305 encryption layer
- [ ] Create VaultHeader and ItemHeader data structures
- [ ] Implement secure file I/O with fsync

### Phase 2: Key Management
- [ ] Implement VaultKeyManager with key derivation
- [ ] Add envelope encryption for per-item keys
- [ ] Implement secure key wrapping/unwrapping
- [ ] Add biometric unlock support with BiometricPrompt
- [ ] Implement key rotation mechanism

### Phase 3: UI Components
- [ ] Create VaultActivity with FLAG_SECURE
- [ ] Implement VaultUnlockFragment with password entry
- [ ] Create VaultSetupFragment for initial setup
- [ ] Add vault entry to hamburger menu under Private groups
- [ ] Implement auto-lock on background/timeout
- [ ] Create vault settings screen

### Phase 4: Note Feature
- [ ] Create SecureNoteFragment
- [ ] Implement note creation/editing UI
- [ ] Add optional per-note password/PIN
- [ ] Implement secure text storage
- [ ] Add note listing/search (encrypted indexes)

### Phase 5: Gallery Feature
- [ ] Create VaultGalleryFragment
- [ ] Implement image metadata stripping
- [ ] Add video metadata stripping
- [ ] Generate and encrypt thumbnails
- [ ] Implement "Save to Vault" from chat
- [ ] Create secure image/video viewer

### Phase 6: Document Feature
- [ ] Create VaultDocumentFragment
- [ ] Implement document storage
- [ ] Add document viewer/handler
- [ ] Support common formats (PDF, etc)

### Phase 7: Security Hardening
- [ ] Implement exponential backoff on failed attempts
- [ ] Add optional panic wipe feature
- [ ] Implement secure clipboard handling
- [ ] Add keyboard security measures
- [ ] Ensure no plaintext in logs/crashes

### Phase 8: Export/Import
- [ ] Implement vault export with encryption
- [ ] Add import functionality with validation
- [ ] Optional: Shamir Secret Sharing
- [ ] Create export/import UI

### Phase 9: Testing
- [ ] Unit tests for crypto operations
- [ ] Instrumented tests for UI security
- [ ] Fuzz testing for parsers
- [ ] Side-channel resistance tests
- [ ] Key rotation tests
- [ ] Device compromise scenarios

### Phase 10: Documentation
- [ ] User documentation
- [ ] Security audit documentation
- [ ] API documentation
- [ ] Migration guide if needed
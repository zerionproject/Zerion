# Zerion Wallet Architecture (Account → ZVault → Wallet → BTC / XMR)

The top-level map of the wallet environment. It describes the behavior shared by
both wallets — identity, password layering, storage, locking, network privacy,
recovery, release — so the per-coin documents can focus on protocol specifics.
This is derived from the current production code, not from historical plans.

- Bitcoin specifics: `BTC_ARCHITECTURE.md`
- Monero specifics: `XMR_ARCHITECTURE.md`
- Non-negotiable invariants and threat model: `WALLET_SECURITY_INVARIANTS.md`
- Native (Monero) JNI boundary: `XMR_JNI_CONTRACT.md`
- Release gates: `WALLET_RELEASE_CHECKLIST.md`
- Native supply chain: `packaging/monero-android/PROVENANCE.md` (Monero) and
  `zerion-android/src/main/cpp/PROVENANCE.md` (Argon2)

## Layers

```
Account (app) ─ vault master password ─▶ ZVault (VaultManager)
                                          │  device-bound keystore + Argon2id
                                          ▼
                                      Vault items (one per wallet seed)
                                          │  wallet id = immutable vault item id
                                          ├──▶ BTC wallet (bitcoinj / Electrum)
                                          └──▶ XMR wallet (wallet2 / libzmonero.so)
```

- **ZVault (`VaultManager`)** is the single encrypted store. It holds every
  wallet seed as a vault item, plus per-wallet settings. Unlocking requires the
  vault master password **and** a device-bound Android Keystore key: the master
  key is `HKDF( Argon2id(password) XOR keystoreUnwrap(randomSecret) )`. Neither
  the password alone nor a copied disk image alone can unlock it.
- **A wallet** is a vault item of type WALLET whose content is
  `[version][mnemonic]`, plus a settings namespace keyed by the wallet id. BTC
  derives keys with bitcoinj; XMR derives a native wallet2 wallet (see the
  Store-1 model in `XMR_ARCHITECTURE.md`).

## Trust boundaries

| State | What is exposed |
|---|---|
| Account / ZVault locked | Nothing but ciphertext: the header, encrypted item blobs, and non-secret settings. No seed, key, balance, or history. |
| ZVault unlocked | The master key is in memory. Wallet **seeds** can be decrypted on demand (each decrypt needs the item's context; a per-wallet password adds a second factor). BTC key derivation and XMR view/spend material are reachable only through the steps below. |
| BTC wallet authenticated | The BTC section credential (separate from the vault password) has been verified for this lock generation; the wallet can scan, derive addresses, and — with a fresh per-send authorization — sign and broadcast. |
| XMR background (view-only) wallet open | Balance and incoming history sync over Tor with **no spend key in memory** (Store-1). Cannot spend. |
| XMR spend wallet authenticated | The wallet password has opened the spend key for one transient session to build and relay exactly one transaction, then it is closed. |

A vault lock increments a **lock generation**; every wallet session, send
authorization and view is bound to the generation captured when it opened, so a
lock invalidates them all and unlocking the vault never implies wallet access.

## Wallet identity

- **The wallet id is the vault item id** — a random UUID and the name of the
  on-disk item directory. All per-wallet state (settings namespaces, XMR native
  files, spend journal, restore height, pending records, auth-token and journal
  ownership) is keyed to this id.
- **The display name is mutable presentation metadata.** For both coins it is
  stored in settings keyed to the wallet id (`settings.xmr.<id>.nm` /
  `settings.btc.<id>.nm`) and is not part of any key derivation, path, native
  filename, address fingerprint, journal or auth ownership. (The vault item's
  content is bound by AEAD to the item's fixed creation-time name, a per-item
  value set once and never changed by a rename; that is authenticated metadata,
  not the mutable display name.)
- **Rename is a display-name-only change for both coins: the wallet id is
  immutable for the life of the wallet.** Renaming verifies the wallet password
  (so it still authenticates) and then updates only the settings display name —
  it never re-encrypts the seed, migrates state, or changes the id. The fund
  identity (the seed and every derived key) and the password are unchanged. XMR
  and BTC follow the identical model; there is no per-coin exception. Because all
  per-wallet BTC state (privacy metadata, receive index, pending reservations) is
  already keyed to the immutable id, an id-stable rename preserves it with no
  migration. (Existing BTC wallets that were renamed under the earlier
  re-create-on-rename model keep whatever id they currently have; that id is
  treated as the immutable identity from this version onward — historical ids are
  not reconstructed.)

## Password layering and secret ownership

Three independent secrets, never conflated:

1. **Vault master password** — unlocks ZVault (Argon2id 256 MiB / t=3, plus the
   keystore factor).
2. **Per-wallet password** — a second factor sealing an individual wallet's seed
   (Argon2id 64 MiB / t=3, per-item salt). For XMR it also derives the spend
   wallet's main-file key.
3. **Per-send / per-transaction authorization** — a fresh authentication bound to
   the exact reviewed transaction, consumed once at broadcast/relay.

**Secret-buffer ownership rule.** A `char[]`/`byte[]` secret is owned by the
top-level caller that created it; the caller wipes it in its own `finally`. A
callee that needs the value derives a key from a private copy and wipes only that
copy — it never mutates the caller's buffer. `VaultManager.getItemContentWithPassword`
no longer wipes the password it is handed (its contract is explicitly
non-consuming), so callers do not clone before calling and a caller's secret can
never be unexpectedly zeroed out from under a later use. This closes the
clone-discipline footgun that produced the earlier zero-password reseal defect.

## Storage and crash safety

- **AEAD**: AES-256-GCM, random 12-byte nonce per encryption. Item **content** is
  bound (AAD) to the item's fixed creation-time name; item **metadata** to the
  item id; the two-layer per-wallet-password wrapping to the same fixed name.
  Encrypt/decrypt AADs always match, and an XMR rename never changes them.
- **Atomic single-file writes**: temp file, fsync, atomic rename, parent-dir
  fsync (`SecureFileIO`). An item writes content first and its metadata header
  last, so a crash mid-create leaves a header-less directory that listing and
  unlock skip — never a listed wallet with a missing seed.
- **Master-password change is crash-safe**: item keys are re-wrapped into a temp
  set, the new header is staged to a marker, the item set is swapped, and the
  marker is renamed onto the live header as the single atomic commit. A
  reconciliation run before every unlock rolls a crash before the commit back to
  the old password with items intact, and a crash after it forward to the new. It
  is a no-op for a healthy vault.
- **Rename/delete order** the old item's removal last, so a crash never
  substitutes or loses a wallet.

## Locking and lifecycle

The vault auto-locks after inactivity and can be locked explicitly. A lock stops
XMR sync, invalidates any in-flight send authorization, wipes transient
decrypted material, and increments the lock generation so no stale spend
authority survives into a later session. Reopening always re-authenticates.

## Network privacy

All wallet network I/O is **Tor by default with no silent clearnet downgrade**.
Fallbacks are other configured servers over the same proxy; a direct (non-Tor)
mode, where offered, is explicit opt-in behind a warning. Each purpose (scan,
broadcast, price, and per wallet) uses its own Tor stream-isolation context, and
broadcast prefers a different server from scanning. Daemon/server addresses are
onion or IP (no clearnet DNS). See each coin's doc for the exact endpoints.

## Receive privacy

Ordinary Receive hands out a **fresh** address every time — BTC BIP84 HD
addresses (gap limit 20), XMR subaddresses — tracked in a durable, crash-safe
index so a crash never causes accidental address reuse.

## History and transaction state

A common vocabulary is used across both wallets where the protocols allow:
**Sending/Pending** (broadcast/relayed, not yet confirmed), **Confirmed**,
**Failed**, and, for the reconciliation edge, **Unresolved/Uncertain**. Outgoing
transactions are shown from the moment they are broadcast and never disappear on
a transient network or native error; the last known good history is preserved
rather than replaced by an empty list. See `XMR_ARCHITECTURE.md` for how a
view-only Monero wallet keeps permanent local outgoing history.

## Recovery

Distinguish **fund recovery** from **local-metadata recovery**:

- **Fund recovery** is always possible from the seed alone: the seed reconstructs
  every key and, by scanning the chain, every incoming output — funds are never
  lost as long as the seed is backed up.
- **Local-metadata recovery** is not guaranteed from the seed alone. Monero's
  outgoing destinations and the exact local fee/send-state metadata are not
  recoverable by a view-only wallet from the chain, because the chain does not
  encode them recoverably. Zerion keeps its own durable, encrypted record of the
  sends it made, so that outgoing history survives restart, in-place rescan,
  background-cache rebuild, migration and rename **while that local Zerion
  metadata still exists**. A clean install holding only the 25-word seed recovers
  the funds and incoming history, but cannot reconstruct historical outgoing
  destinations or the exact local send metadata.

Within Zerion, wallets damaged by the earlier rename defect (seed sealed under a
zeroed password) recover automatically when opened with the real password.

## Release model

Wallets ship only after the gates in `WALLET_RELEASE_CHECKLIST.md`: clean source
(no developer comments, no logging), reproducible hash-pinned native libraries,
device regression on the developer test device, and — for a freeze — the user's
own funded acceptance on their device. Developers never broadcast a funded
transaction; freeze happens only after the user reports a pass.

## Feature status

See `BTC_ARCHITECTURE.md` and `XMR_ARCHITECTURE.md` for the exact, current status
of each feature (shipping / default-off / build-gated / not implemented /
deferred). Nothing disabled is described anywhere as if it were active.

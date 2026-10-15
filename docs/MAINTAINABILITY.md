# Large classes: boundaries and plans

This note records which of the largest classes were split, where the new
boundaries are, and why the others stay whole. A class is split only where its
size creates a concrete engineering risk: security-relevant code mixed with
unrelated code, mutable state shared between threads without a visible
boundary, persisted or wire formats with no single owner, or logic that cannot
be tested without the whole class. Every split below preserved behaviour
exactly (no wire, persisted-format or user-visible change) and was made after
tests that pin the behaviour had been written and run against the old code.

## Split

### `VaultViewModel` (zerion-android, 3391 to 2546 lines)

Before the split one ViewModel held the documents vault screens, the BTC wallet
section credential check, the wallet settings document, the Electrum routing
decision (Tor, direct or LAN) and the stored formats of protected notes and
password entries. The settings document was rewritten in fifteen places from
three executors.

| Unit | Package | Owns |
|---|---|---|
| `BtcWalletSettings` | `vault.wallet.btc` | The wallet settings document and its lock (`WalletStore.settingsLock`): `currency`, `priceRates`, `priceAt`, `recv`, `chg`, `btc.<id>.nm`, `privacy.<id>`, `pending.<id>`; the pending-send log and the coin privacy store of a wallet; moving the per-wallet records (`privacy`, `recv`, `chg`, `pending`, `btc`) to a new wallet id and removing them |
| `BtcNodeRouting` | `vault.wallet.btc` | The node keys (`node`, `nodes`, `pins`), the default nodes, and the scan, broadcast and fallback endpoints for each routing mode |
| `WalletCredential` | `vault.wallet.btc` | The wallet section credential record (`authType`, `authSalt`, `authHash`, `authIter`, `authFailures`), the PBKDF2 check and the failure throttle |
| `ProtectedNoteCodec` | `vault.crypto` | The protected note layout: version 2 written, versions 2 and 1 read |
| `PasswordEntryCodec` | `vault.model` | The stored JSON shape of a password entry |
| `SilentPaymentCoins` | `vault.wallet.btc` | The silent payment coins of the open wallet, behind one lock and tied to that wallet's id, so a scan or sweep never mixes coins of two wallets |

The ViewModel keeps the LiveData, its three executors, the wallet session flags
and the orchestration of open, scan, send, silent payment sweep and payjoin.
The review-to-sign binding stays in `SendGate`, `SpSweepGate` and
`PayjoinFlowController`; the ViewModel still decides what a failed credential
does (it drops reviewed plans and relocks after the throttle limit).

Rule: a new key in the wallet settings document is added to one of the three
`vault.wallet.btc` units, never read or written from the ViewModel.

Tests that pin it: `VaultWalletSettingsTest`, `VaultWalletRoutingTest`,
`VaultWalletCredentialTest`, `VaultItemFormatTest` (written against the old
class, unchanged after the split), and `BtcWalletSettingsTest`,
`BtcNodeRoutingTest`, `WalletCredentialTest`, `ProtectedNoteCodecTest`,
`PasswordEntryCodecTest`, `SilentPaymentCoinsTest` for the units.

### `VaultWalletFragment` (zerion-android, 2622 to 2551 lines)

The conversion of a typed amount (BTC, EUR or USD) to satoshis and the amount
texts were private to the fragment and untested. They moved to
`vault.wallet.btc.BtcAmountText`. The dialogs stay: the fragment's state is
touched on the main thread only, and splitting dialogs would change their
lifecycle on a screen that is only verified on a device.

Tests: `VaultWalletAmountTest` (against the old fragment, unchanged after),
`BtcAmountTextTest`.

### `VoiceCallService` (zerion-android, 2807 to 2744 lines)

The audio stream format and the jitter buffer shared by the receive and
playout threads were inline in a 430-line method.

| Unit | Owns |
|---|---|
| `AudioJitterBuffer` | The playout ring buffer, its lock, the playout start threshold, reset and wipe; on overflow the oldest audio is dropped |
| `CallAudioWire` | The audio stream framing: sync, ready, keepalive and hang-up markers, the 28-byte frame header and its CRC32; an oversized frame is read past, and one beyond the skip limit closes the media stream |

The service keeps its lifecycle, the signalling state machine, the key
schedule, reconnects and video. These stay together because eleven test classes
reach the service's private fields by name and because their timing can only
be verified on a device.

Rule: any change to the audio stream format is made in `CallAudioWire` and
needs the matching change on the other clients.

Tests: `CallAudioMediaTest` (drives the real service end to end under both key
schedules: frames sent, frames played, corrupted, replayed and oversized
frames, markers, hang-up; written against the old service),
`AudioJitterBufferTest`, `CallAudioWireTest`, and `CallKeyWipeTest` for the
key material the service zeroes.

### `GroupTrManagerImpl` (zerion-app, 4007 to 3658 lines)

The recent-posts cache, the buffer for posts from a later epoch and their count
and byte bounds were spread through the manager and shared its locks.
`GroupTrPostCache` now owns them, with the eviction policy that the history
loader also uses.

Locking contract: callers hold the group's stripe lock. The cache takes the
monitor of a group's queue or buffer, and when it evicts another group to stay
within the total budget it only tries that group's stripe lock and skips the
group if the lock is held.

Tests: the existing `GroupRecentPostsRoomTest`, `GroupMetadataBoundsTest` and
history tests, `GroupPostCacheTest` (epoch tolerance, total budget, removal;
written against the old manager), `GroupTrPostCacheTest`.

## Left whole

| Class | Lines | Why it stays whole now |
|---|---|---|
| `JdbcDatabase` | 4859 | One SQL method per `Database` method, each on the caller's connection with its own statement; no shared state beyond the connection pool; migrations already live in their own classes. Splitting would move the subclass overrides and test hooks without reducing any risk. The tables `pqRatchetState`, `groupSenderKeys`, `groupKeyHistory` and `groupCryptoState` hold no data the code reads or writes; they are candidates for removal in a future schema migration. |
| `ChannelManagerImpl` | 4251 | Real risk (signing and key material mixed with scheduling and I/O, lock discipline by convention, a state constructor copied in many places), but its signing paths are changing now. Plan below. |
| `MessagingManagerImpl` | 2642 | Every public method is one database transaction and the class holds no mutable state; the risk is feature rules spread through handlers. Plan: move edit and delete handling, then attachment lifecycle, into package-private helpers built from the same dependencies, once the current messaging changes have settled. |
| `XmrWalletManager` | 2629 | Already split into the send flow, gate, journal, reconciler, sync and native engine classes; what remains shares the open session and the exclusive slot. Plan: move the settings document handling and the pending-send ledger out once the current Monero work has landed. |
| `ConversationActivity` | 2525 | A single-threaded screen; the logic sits in `ConversationViewModel`. Optional: a camera capture helper. |
| `payjoin_ffi.kt`, `bitcoin.kt` | 7914, 3070 | Generated bindings, never edited by hand. |

### Plan for `ChannelManagerImpl`

Lowest risk first, each step preceded by tests that pin it:

1. Replace the tests' reflective calls into private methods with
   package-private entry points, so moved code fails at compile time.
2. `ChannelStateEdits`: the copies of the channel state with one field changed.
3. `ChannelLocks`: one shared, reentrant lock per channel for all units.
4. `ChannelRequestAuth`: request challenges, proofs and the nonce ring.
5. `ChannelOnionHost`: binding, healing, rotating and retiring the channel
   onion services.
6. `ChannelPullServer` and a request router that keeps which requests run under
   the channel lock.
7. After the current signing changes have landed: comments and reactions,
   membership (apply, approve, announce), publishing, and last the subscriber
   sync loop.

Unchanged throughout: wire types and acknowledgement strings, signed inputs and
labels, the approval envelope, persisted namespaces and marker files, write
order and the order of events.

Rules every step keeps (tested in `ChannelManagerConcurrencyTest`):

- Every read-then-write of the channel state, the posts, comments, reactions,
  subscribers, applications and the post tombstones runs under the channel's
  lock, also when the write follows a request to another device; that write
  first checks that the channel still exists.
- No request to another device's onion runs under the lock: pulls, posts
  submitted by an editor, comments, reactions, announcements, applications,
  approval checks and attachment fetches send first and take the lock to store
  the result.
- Binding, healing, rotating and retiring the channel's own onion services stay
  under the lock. They are commands to the local Tor process, not round trips
  to another device, and run outside the lock two binders could publish two
  services for one channel, or a scheduled rotation could undo a revocation.
- A post's attachments are wrapped with the content key read under the same
  lock hold that signs the post, so a key rotation cannot leave them on the
  previous key.
- The publisher's private key is loaded in one place and wiped after each
  use; member keys derived for a signature, and the key halves copied out
  for it, are wiped as well.
- The subscription refresh chain survives a start the executor refuses: it
  tries again after 2 s, doubling up to 60 s. A chain that has been quiet for
  three minutes past its due time is replaced by a new one on the next event,
  pull or binding check, and a late tick of the old chain then does nothing.

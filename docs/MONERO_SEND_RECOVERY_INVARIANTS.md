# Monero send and recovery: state machine and invariants

This document is the single reference for the Monero send path, the spend
journal, the balance reservation, the spend session and vault locking, so
that a change to one state cannot silently reopen a defect in another. It
was written during the 3.0.11 security remediation (findings XMR-01, XMR-03,
XMR-05, JNI-01 and their interaction with vault locking, transaction
journaling and authorization-token invalidation) and describes the code as
it is after those fixes.

## Actors and state

- `XmrSendFlow` (one per send, lives on the session executor): the send
  state machine. States: INPUT, VALIDATING, PREPARING, REVIEW_READY,
  AUTHENTICATING, AUTHORIZED, RELAYING, SUCCESS, FAILED, RELAY_UNCERTAIN,
  CANCELLED.
- `XmrSendSnapshot`: the immutable review snapshot, with a fingerprint over
  the exact signed transaction (destination, amount, fee, dust, tx count,
  txids).
- `XmrAuthToken` (issued by `XmrSendGate`): the single-use authorization,
  bound to the snapshot fingerprint, the prepared native object, the spend
  session, the wallet id, the session epoch, the lock generation and the
  flow token. Time-limited (30 s), consumed exactly once.
- `XmrSpendJournal` (durable, written by `XmrSpendJournalStore` through
  `XmrStore`): the write-ahead record that a relay was attempted for a set
  of txids on a named endpoint. A present or unreadable journal
  spend-quarantines the wallet.
- `XmrPendingSend` (durable, in the wallet's settings record): the
  outstanding-send record that drives the outgoing-history overlay and the
  balance reservation. Reservation states: RESERVED, RELAY_UNCERTAIN,
  CONVERGED.
- Spend session: the transient spend-capable native wallet opened for one
  send; the view-only background session is the runtime session.
- Exclusive slot: single holder (send, delete, rename, rescan).
- Vault lock generation: increments on every vault lock; every session and
  authorization is bound to the generation it was created under.

## The send state machine

```
INPUT
  | prepareSend: quarantine check (journal absent), session valid,
  |   exclusive slot taken, sync stopped, spend session opened over the
  |   relay-isolated Tor circuit, refresh quiesced
  v
PREPARING --(sign)--> REVIEW_READY          [spend session held, watchdog armed]
  | authorize(password): fresh authenticated decrypt, token bound to
  |   snapshot + ownership + epoch + lock generation
  v
AUTHORIZED
  | confirmAndRelay (one serialized operation):
  |   validateForRelay (ownership, liveness, native re-read, fingerprint,
  |   single-use consume)  ->  endpoint captured  ->  journal RELAYING
  |   written durably  ->  commit
  v
RELAYING
  |-- commit returned true  --> SUCCESS
  |-- commit returned false --> RELAY_UNCERTAIN
  |-- commit threw          --> RELAY_UNCERTAIN   (XMR-01)
  |-- validation/journal write failed (before commit) --> FAILED

Any state before RELAYING:
  cancel (user, watchdog, view destroyed, explicit close) --> CANCELLED
  vault lock                                              --> CANCELLED
RELAYING, SUCCESS, RELAY_UNCERTAIN: lock and cancel invalidate the token
  only; they cannot unsend, and no new relay can begin.
```

After a terminal relay result the manager runs, in order, on the session
executor: daemon reconciliation on the still-open spend session (XMR-01),
convergence (`convergeAfterRelay`), then clears the flow, closes the spend
session, releases the exclusive slot and re-arms sync.

## Journal and reconciliation

```
absent --(relay about to run)--> RELAYING (durable, before commit)
RELAYING/UNCERTAIN --(every txid positively accepted)--> cleared
RELAYING/UNCERTAIN --(expiry + MISSED everywhere + not in history
                       + wallet password + user decision)--> cleared (release)
RELAYING/UNCERTAIN --(delete with acknowledgement)--> removed with the wallet
corrupt --(never automatically)--> only removable with the wallet
```

Positive acceptance of a txid: the daemon reports it in its pool or in a
block (`lookupTxs`), or the wallet's own outgoing history records it.
MISSED and lookup errors never resolve anything, whatever the count, node
or elapsed time. Reconciliation runs:

1. right after every relay, on the spend session (which is connected);
2. after every view-session open, once the sync loop has connected;
3. on every user refresh;
4. on the password-gated open of the spend wallet (`reconcileExternalSpends`),
   using the spend wallet's authoritative outgoing history.

A daemon's rejection answer is deliberately not treated as definitive: the
node may have broadcast and lied. No negative proof exists for a signed
transaction (a node holding the bytes can re-inject it later), so the only
negative exit is the explicit release, which is safe against fund loss
because the network can pay out at most one of two transactions consuming
the same inputs.

## Reservation lifecycle

```
(relay accepted)   RESERVED         reservationDebit = consumed inputs
(relay uncertain)  RELAY_UNCERTAIN  reservationDebit = consumed inputs
RESERVED ---------(spend wallet stored post-relay state)-----> CONVERGED (0)
RELAY_UNCERTAIN --(spend wallet observes the OUT tx on the
                   password-gated open: convergeObservedSends)-> CONVERGED (0)
RELAY_UNCERTAIN --(explicit release)---------------------------> removed
CONVERGED -------(rescan rebuilds the view cache)--------------> RESERVED again
```

The reservation is display-side only; wallet2's own balance is never
edited. It is released only by positive convergence (the cache reflects the
spend) or by the explicit release; observing the txid in history alone
never releases it (XMR-05).

## Invariants and where each is enforced

1. reviewed tx == signed tx == journalled tx. The snapshot is taken from
   the signed native transaction; `validateForRelay` re-reads the native
   object and compares fingerprints in constant time immediately before the
   journal write and the commit; the journal carries the snapshot's txids.
   (`XmrSendGate.validateForRelay`, `XmrSendFlow.confirmAndRelay`)
2. An uncertain relay preserves enough state for reconciliation: the
   journal (txids, endpoint, time) is durable before the commit and is kept
   on false and on exception; the pending record is durable and marked
   uncertain. (`XmrSendFlow.confirmAndRelay`, `convergeAfterRelay`)
3. No unsafe double spend or re-broadcast: nothing in the code re-broadcasts
   a journalled transaction; a quarantined wallet cannot construct a new
   transaction; the release path requires the password, the expiry, an
   answering daemon and absence from history, and even then a later send
   consuming the same inputs can pay out at most once.
   (`XmrSendFlow.prepare`, `XmrWalletManager.releaseOnSession`,
   `XmrSpendReconciler.releasable`)
4. The reservation survives uncertainty: the uncertain path never marks the
   record converged. (`convergeAfterRelay`, test
   `uncertainRelayKeepsTheReservationAcrossRestart`)
5. Lock destroys spend capability: the lock listener invalidates the token
   and the flow (no native access, safe off-executor), then on the executor
   frees the native transaction, closes the spend session, releases the
   slot and closes the view session; the lock generation changes so every
   binding of the token and the session fails closed. (`invalidateSession`,
   `XmrSendFlow.invalidate`, `XmrAuthToken.bindsTo`)
6. Orphaned UI does not orphan the native spend session: the manager's
   watchdog cancels a flow still at review after the TTL; the detail screen
   cancels an active flow when its view is destroyed; an explicit session
   close tears the flow down; and the vault lock always wins. (XMR-03)
7. Reconciliation does not require wallet deletion: the daemon-backed
   reconciliation runs on every connection; the expiry-gated release exists;
   deletion remains possible with an explicit acknowledgement. (XMR-01)
8. Native transaction lifetime is single-owner and executor-serialized:
   `disposePrepared` runs only on the session executor; `invalidate` from
   the lock thread never touches the native object; the history read uses
   wallet2's own refresh quiescence (JNI-01, native recipe patch).
9. Relay and sync do not share a Tor circuit: the spend session and the
   sync loop use distinct SOCKS5 credentials, per wallet and per purpose.
   (XMR-04, `XmrTorIsolation`)

## Cross-finding review

- XMR-01 x XMR-05: clearing the journal on positive evidence must not
  release the reservation. It does not: journal resolution and convergence
  are separate; the record stays RELAY_UNCERTAIN until the spend wallet
  converges it.
- XMR-01 x XMR-03: the release path runs on the session executor through
  `syncManager.submit`, never while a send flow holds the exclusive slot
  (a quarantined wallet cannot start a flow), so it cannot race a relay.
- XMR-03 x XMR-01: the watchdog cancels only at REVIEW_READY; a flow at
  RELAYING is never interrupted, so a cancel can never turn a relay into a
  forgotten uncertain one.
- XMR-03 x vault lock: both paths share `teardownSendFlowOnExecutor`; the
  lock additionally bumps the generation, so a watchdog cancel arriving
  after a lock is a no-op on an already-cleared flow.
- JNI-01 x XMR-03: freeing the native transaction on the executor is the
  same discipline that removed the history use-after-free; the watchdog
  reuses the cancel path rather than touching native state directly.
- Token invalidation x journaling: a journal is written only after the
  token was consumed once; a token invalidated by lock, cancel or expiry
  fails `validateForRelay` before any journal write, so no journal exists
  for a relay that did not run.

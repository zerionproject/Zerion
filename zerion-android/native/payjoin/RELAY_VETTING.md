# BIP77 directory / relay vetting

Payjoin stays disabled until a directory/relay is vetted over Tor. Zerion does
not operate its own Payjoin coordinator or relay. If nothing here can be vetted,
the wallet offers only a normal send.

## Why a directory is needed

BIP77 (async Payjoin v2) uses a store-and-forward directory so sender and
receiver need not be online at the same time. The directory sees only OHTTP
encapsulated payloads. The OHTTP layer means the directory cannot read message
contents, and the relay in front of it cannot link request to response beyond
size and timing. It never sees wallet keys, the wallet graph, balances,
unrelated UTXOs, or labels.

## Vetting criteria (all required before enabling)

1. Reachable over Tor with no clearnet fallback. Failure is fail-closed.
2. Serves valid OHTTP key configuration that the pinned `bitcoin-ohttp` 0.6.0
   accepts.
3. Operated by an identifiable third party with a public, non-custodial policy;
   it never holds funds and cannot alter a transaction.
4. Independent of Zerion. No Zerion-run relay is introduced to make the feature
   work.
5. Stable OHTTP gateway/relay separation so no single party links sender to
   receiver.
6. A dedicated Tor isolation context is used, distinct from Electrum and
   messaging circuits.

## Candidate

- Reference directory: `payjo.in` (BIP77 default, operated by the Payjoin Dev
  Kit maintainers), reached through its published OHTTP relay.

Status: NOT YET VETTED. The live checks in the next section must pass over Tor,
on the networked device, before Payjoin is enabled. Recording a candidate here
is not approval.

## Live checks to perform (networked device, over Tor)

- Fetch and parse the OHTTP key config over Tor; confirm `bitcoin-ohttp` 0.6.0
  accepts it.
- Confirm request/response round-trip through the relay with a throwaway
  session, with clearnet blocked, and confirm it fails closed when Tor is down.
- Confirm the isolation context is separate from Electrum/messaging.

## Result

To be filled after the live checks. Until every criterion is met and recorded,
the Payjoin feature flag remains off.

# Network paths: onion-only and clearnet via Tor

Every connection Zerion opens, where it goes, how it gets there and what authenticates the other end. A connection that is not in this table is not made by the app.

## Route meanings

- **Onion only**: the connection goes to a Tor v3 onion service. It never leaves the Tor network, so no exit relay sees it, and the onion address itself is the public key of the service, so reaching the address authenticates the service.
- **Clearnet via Tor exit**: the connection goes through a Tor circuit to an exit relay, which opens the connection to a public host. The host does not learn the device address, but the exit sees the destination and every byte that is not protected by authenticated TLS. Wallet requests use a SOCKS isolation context per wallet and purpose, so unrelated requests do not share a circuit. If Tor is not ready the request fails; it never falls back to a direct connection.
- **Direct**: the device connects without Tor. The other end and the networks in between see the device address, and a host name is resolved by the DNS resolver of the network. Used only where the row says so, and for user choices only after a warning.
- **Direct LAN**: the device connects without Tor to an address on its own local network.
- **Radio**: Bluetooth, with no IP connection at all.

## Table

| Feature | Code | Endpoint | Route | Authentication | Fallback |
|---|---|---|---|---|---|
| Contact messaging | `ZtpTorTransport.dial` | the contact's v3 onion (the client authorized onion once it is negotiated with that contact, the open onion before that) | Onion only | onion service authentication plus end to end session authentication | none; an undelivered message stays on the sender's device and is retried |
| Channels | `TorChannelTransport` | the channel's v3 onion | Onion only | onion service authentication | none |
| Groups | group sync over the contact sessions of `ZtpTorTransport` | the members' v3 onions | Onion only | as contact messaging | none |
| Account transfer | `AccountTransferManager` | an ephemeral v3 onion published for the transfer; the scanned address must be a v3 onion | Onion only | onion plus key agreement and a confirmation code typed on the sending phone | none |
| Bitcoin wallet scan and broadcast, default | `ElectrumClient`, `BtcNodeRouting.DEFAULT_ONION_NODE` | `kittycp2gatrqhlwpmbczk5rblw62enrpo2rzwtkfrrr27hq435d4vid.onion:50001` | Onion only | onion service authentication | the shipped fallback node below |
| Bitcoin fallback node and fee cross-check | `BtcNodeRouting.DEFAULT_NODE`, `feeCheckEndpoint` | `electrum.blockstream.info:50002`; the fee cross-check asks the other shipped node, or a separately set broadcast node, and is skipped for a user node | Clearnet via Tor exit | TLS 1.2/1.3 with the system CA store and hostname verification | none; never direct |
| User Electrum node, public host | `ElectrumEndpoint` in TLS mode | the host the user entered | Clearnet via Tor exit | TLS 1.2/1.3 with CA and hostname verification, or instead a SHA-256 leaf pin that the user confirmed after it was captured over two independent circuits; plaintext refused | none |
| User Electrum node on the local network | `ElectrumEndpoint.isLanHost` | an RFC 1918 or loopback address | Direct LAN | TLS on port 50002, otherwise plaintext, which is allowed on the local network only | none |
| Bitcoin Direct routing (opt in, warned) | `BtcNodeRouting.ROUTING_DIRECT` | `electrum.blockstream.info:50002` | Direct, host name resolved by the device | TLS 1.2/1.3 with CA and hostname verification | none |
| Bitcoin price | `BtcPrice` | `mempoolhqx4isw62xs7abwphsq7ldayuidyx2v2oethdhhj6mlo2r6ad.onion:80` | Onion only | onion service authentication | none |
| Silent payment oracle | `SilentPaymentScanner` | `silentpayments.dev` or an https URL the user entered | Clearnet via Tor exit | HTTPS forced, TLS 1.2/1.3 with CA and hostname verification | none |
| Payjoin | | | disabled in every build; the native library is excluded | | |
| Monero vetted nodes, default | `XmrNodeSelector.VETTED_DEFAULT` | four vetted v3 onions on port 18089 | Onion only | onion service authentication | the next vetted onion |
| Monero own or custom onion node | `XmrNodeConfig`, `XmrNode` | the onion the user entered | Onion only | onion service authentication | none for an own node; custom nodes fail over among themselves, and to the vetted onions only when none of them can be used |
| Monero own or custom node with a public IP address or host name | `XmrNodeConfig.isAllowed` | the address the user entered | Clearnet via Tor exit | none at the TLS layer: the pinned library accepts any certificate and can fall back to plaintext; used only after a warning accepted for that node, and never trusted | while paused, the vetted onions, or nothing if the user chose to stop Monero sync |
| Monero Direct (opt in, warned) | `XmrNode.Source.DIRECT` | the address the user entered | Direct, host name resolved by the device | the same unauthenticated TLS | none |
| Monero price | `XmrPrice` | `api.kraken.com` | Clearnet via Tor exit | HTTPS, TLS 1.2/1.3 with CA and hostname verification | none; the rate is shown as a fiat value and converts a fiat amount typed in the send form |
| Update check | `UpdateChecker`, `TorHttpsGet` | `raw.githubusercontent.com`: the release manifest and its `.sig` | Clearnet via Tor exit | HTTPS, TLS 1.2/1.3 with CA and hostname verification, plus a manifest signature by the app's signing certificate | none; the update dialog opens the release page in the browser, and the app never downloads the APK |
| Bridge list update | `BridgeListUpdater`, `BridgeList` | `raw.githubusercontent.com`: `bridges.json` and its `.sig` | Clearnet via Tor exit | HTTPS, TLS 1.2/1.3 with CA and hostname verification, plus an Ed25519 signature, a sequence number and an expiry | the built-in bridges |
| Tor directory authorities and guards | the tor binary | the Tor network | Direct | Tor link TLS, relay identity keys and the signed consensus | none |
| obfs4 and plain bridges | the tor binary, lyrebird for obfs4 | the bridge address | Direct | obfs4 keys and the bridge fingerprint | the next bridge |
| Snowflake and meek, after consent | lyrebird | Snowflake: a CDN77 domain front over HTTPS or, in some country lists, the AMP cache, STUN servers and WebRTC volunteers; meek: a CDN77 domain front over HTTPS | Direct | the inner Tor circuit; the Snowflake bridge fingerprints are pinned | none |
| I2P | | | development builds only, not shipped in release | | |
| BLE mesh and Bluetooth contact add | the Bluetooth radio | nearby phones | Radio, no IP | application layer cryptography | none |
| Links in chats, channels, groups and donation; About, update and explorer | the system browser | the link | not fetched by Zerion | links in conversations and the donation screen open after a confirmation; About copies links; the update dialog and the explorer button open the browser when the user taps them; link previews are drawn from bytes sent by the sender and never fetched | none |

Zerion contains no SSH or other protocol that sends credentials in plaintext; the only protocol that can cross an exit without authenticated TLS is the Monero daemon RPC to a non-onion node the user explicitly accepted, which sends no credentials and cannot cause loss of keys.

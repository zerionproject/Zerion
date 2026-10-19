# Built-in bridge list

The app ships built-in obfs4 and plain (vanilla) Tor bridge lines in
`zerion-core/src/main/resources/bridges-*`. So that bridge maintenance does not
depend only on the next app release, a connected app also reads a signed copy
of these lists from this folder, `bridges.json` with its detached signature
`bridges.json.sig`, and keeps a small local record of which bridges work.

## File format

`bridges.json` is a JSON object with exactly these fields:

| Field | Value |
|---|---|
| `context` | the string `zerion-bridge-list-v1` |
| `seq` | a positive integer, at most 15 digits, raised by one for every published list |
| `issued` | the UTC date the list was made, `YYYY-MM-DD` |
| `expires` | the last UTC date the list may be used, `YYYY-MM-DD`; at most 366 days after `issued` |
| `bridges` | an object of named groups, each an array of bridge lines |

The groups the app uses:

| Group | Replaces the bundled list | Line syntax |
|---|---|---|
| `obfs4-default` | `bridges-d-zz` (the default obfs4 set) | `obfs4 <IPv4>:<port> <FINGERPRINT> cert=<70 base64 characters> iat-mode=<0, 1 or 2>` |
| `obfs4-non-default` | `bridges-n-zz` (the obfs4 set for countries that block Tor) | as above |
| `vanilla` | `bridges-v-zz` (plain bridges) | `<IPv4>:<port> <FINGERPRINT>` |

The fingerprint is 40 uppercase hexadecimal characters. The address must be a
public IPv4 address (no private, loopback, link-local, shared, documentation,
benchmark, multicast or reserved range); IPv6 lines need a new format version.
Any other group name made of lowercase letters, digits and hyphens (for
example `snowflake`, `meek` or `webtunnel`) is read, checked for printable
characters and length, and never used: Snowflake and meek always come from the
bundled lines and stay behind the ask-first prompt, so a downloaded list cannot
add a broker, a front domain or any other new network party.

The parser accepts a strict subset of JSON: the file is printable ASCII with
LF line ends (no CR, no byte order mark), strings have no escapes, numbers are
positive integers, there is no `true`, `false` or `null`, keys are not
repeated, nesting stops at the group arrays, and nothing follows the closing
brace. Caps: 64 KiB per file, 16 groups, 64 lines per group, 256 lines in all,
512 characters per line, 256 bytes per signature file. A line may appear only
once and a fingerprint only once among the used groups, and at least one used
group must hold a line. Any violation rejects the whole list.

## Signature and pinned key

`bridges.json.sig` is the base64 encoding of one 64-byte Ed25519 signature,
followed by a newline. The signed message is the line `zerion-bridge-list-v1`,
a LF, and then the exact bytes of `bridges.json`, so a signature over another
kind of file never verifies as a bridge list. The key is a dedicated offline
key used for nothing else; it is not the APK signing key and not the release
manifest key. Its raw public key is pinned in the app as
`BridgeListKeys.PINNED_KEY`:

    bJJq5mVcoBaEVU3hOWnr+FyR/gPqQKoBk5UbNEYIfN8=

Key custody is a required procedure: the private key is kept on an offline
medium and attached only for signing; it is never stored on a build machine.
Keep it as an encrypted PKCS#8 file made with a high PBKDF2 iteration count,
for example

    openssl pkcs8 -topk8 -v2 aes-256-cbc -v2prf hmacWithSHA256 -iter 600000 -in plain.pem -out bridge-list-signing.pem

with 600000 iterations or more, and wipe the plain copy. `SignBridgeList.java`
refuses an unencrypted key unless `--allow-plain-key` is given, refuses an
encrypted key with fewer than 100000 PBKDF2 iterations, and asks for the
passphrase on the console only.

The app checks the signature before it parses anything (`BridgeList`,
`BridgeListStore.offer`). A downloaded list is checked against the pinned key
only. Development builds also hold a separate test key that is accepted only
for a list placed in the app's private `bridge-test` folder on a test phone;
release builds contain neither the folder nor that key
(`BridgeListDebugTest`).

## Acceptance, rollback and expiry

A list is accepted only when all of these hold, in this order: the size caps,
the signature, the format above, `issued` is at most one day after the
device's UTC date, `expires` is not before that date, and `seq` is higher than
both the highest sequence ever accepted on this device and the sequence of the
lists bundled in the app (`BridgeListStore.BUNDLED_SEQ`). The highest accepted
sequence is kept per signing key in the encrypted account database and
survives restarts and the expiry of the list, so an older signed list is
refused even when it is still valid. The floor is the highest sequence of all
keys that are still pinned, so after a key is removed in an app update its
lists, whatever their numbers, neither apply nor block lists from the other
keys.

Losing the account database, or restoring a backup, resets the floor to what
that database held (nothing, or the value at the time of the backup). That is
acceptable: an older list still has to carry a valid signature from a pinned
key and must not have expired, and the bundled sequence stays the minimum.

Any rejection leaves the previously accepted list in place, or the bundled
lists when none was accepted. An accepted list stops being used after its
`expires` date, and the app goes back to the bundled lists until a newer list
arrives. The stored copy is checked again, signature and format, whenever it is
loaded from the database.

## How the lines are used

When bridges are in use and no custom bridges are set, each group of an
accepted list replaces the bundled list of the same kind wherever the app used
it before; the choice of kinds per country is unchanged. A group with fewer
than three lines is completed from the bundled lines of the same kind. The
list lives in its own database namespace (`tor-bridge-list`), apart from the
Tor settings, so accepting a list does not restart Tor, does not clear the
entry relay warning and does not re-add onion client credentials; the new
lines are used the next time the bridges are applied (a network change, a
restart of the network or of Tor). Custom bridges entered in Settings are
never filtered or replaced.

## Download

Who downloads: only an installation that uses the built-in bridges now
(turned on automatically for its country or chosen by the user in Settings,
Connections) or used them within the last 30 days. An installation that
connects to Tor directly and never used the built-in bridges never downloads
the list, and custom bridges alone do not count. Installations from app stores
download it too; the update check, which they skip, is a separate feature.
There is no separate switch, because the download is part of using the
built-in bridges: it starts with them and stops 30 days after they were last
used. The day of the last use, with the profile that recorded it, is kept in
the same encrypted namespace as the list (`BridgeListStore.noteBuiltInBridges`)
and is deleted as soon as it is more than 30 days old. A restored or
transferred account is a new profile, so the copied day is deleted on its first
check and a copy on a phone that connects directly does not download.

The app downloads the list only when Tor is connected, only through Tor's
SOCKS proxy with the host name resolved by Tor (`TorHttpsGet`), and over HTTPS
with the certificate checked against the host. The request carries only the
host name, `Accept-Encoding: identity` and `Connection: close`: no cookies, no
user agent, no account or device identifier. Responses are capped at the file
limits above, every read times out after 60 seconds, and the list and its
signature together must arrive within two minutes: a watchdog on the phone's
monotonic clock closes the connection when that time is up, whatever phase
(the SOCKS handshake, TLS or a slowly dripping answer) it is in. The sources are listed in
one place, `BridgeListSource.URLS`; today that is only

    https://raw.githubusercontent.com/zerionproject/Zerion/master/docs/bridges/bridges.json

and the same URL with `.sig`. A project onion address can be added to that
list; it needs plain HTTP over the onion service added to `TorHttpsGet`.

Timing (`BridgeListUpdater`): one to ten minutes after Tor connects, and every
six hours while the app runs, the app checks whether a download is due. It is
due once after every app update or new account, one to three days (random)
after a download that gave an answer (a new list, a valid list that is not
newer, or a genuine list that has expired), and three to six hours (random)
after a failure (no connection, missing file, bad signature, malformed list or
a list dated in the future). A next check set
more than three days ahead (a clock that was set wrong) counts as due. Two
downloads never run at the same time.

## Local bridge health

`BridgeHealth` keeps, per bridge, a 64-bit identifier (HMAC-SHA256 of the
bridge fingerprint under a random 32-byte secret of the account, so the same
bridge has a different identifier in every account), the number of recent
failures (capped at six), the hour of the last failure and the day of the last
success. Nothing about the user's address or network is stored, and nothing
leaves the device. The record is fed by Tor's OR connection events for the bridges the app handed to
Tor: a completed connection is a success, a failed one is a failure; events
while the network is switched off are ignored, and a failure inside a backoff
period does not count again.

A bridge that failed rests for 1, 2, 4, 8, 16 and then 24 hours (from the end
of the hour it failed in), after which it is tried again, placed after the
healthy bridges; a success clears its failures. Resting bridges are left out
of the set handed to Tor, but the set never drops below three bridges (or all
of them when fewer exist): the bridges whose rest ends first are added back.
When every bridge is resting, all of them are used. When every bridge taken
from a downloaded list has failed, the bundled bridges of the same kinds are
added back next to them, and the downloaded ones keep being retried, until one
of the downloaded bridges works again. While some bridges rest
and Tor has not finished connecting, the app looks every 30 minutes whether a
rest has ended and then applies the bridges again; a connected Tor is not
disturbed. At most 64 records are kept. Every time the app applies its Tor
network choice (at each start of Tor, on every network change and whenever
the Connections settings change), whether it uses the built-in bridges,
custom bridges or no bridges at all, it also maintains the record: entries
not touched for 30 days, and entries dated more than an hour ahead of the
phone's clock (a clock that was set wrong), are deleted, and once no entry is
left the record is deleted together with its secret.

The record and its secret stay in their own namespace (`tor-bridge-health`)
of the encrypted account database; they are never written anywhere else. They
are deleted with the account, by the panic wipe and by every other account
erase, like the Tor settings. A backup or an account transfer copies the
database, so the record is tied to the profile that wrote it: a restored or
transferred account is a new profile, and the first time it applies its Tor
network choice, also on a phone that connects directly or uses only custom
bridges, the copied record and its secret are deleted; a new secret is made
only when the built-in bridges are used again.

## Limits

- A download needs a working Tor connection. A phone whose bridges are all
  blocked cannot fetch a new list through them; it still has Snowflake and
  meek after the prompt, custom bridges and the next app update.
- Health only sees whether the connection to a bridge completes. A bridge
  that completes the link handshake but cannot carry circuits is not noticed.
- Dates are checked against the phone's clock. A clock far in the future
  makes the app fall back to the bundled lists; a clock far in the past can
  keep an expired list in use (Tor itself needs a roughly correct clock).
- GitHub, reached through a Tor exit, sees that someone fetched the public
  list, never who.

## Publishing a new list

Lists change at release time. A release that refreshes the bundled lines also
writes the same lines to `docs/bridges/bridges.json`, with `seq` one higher
than the last published list, and raises `BridgeListStore.BUNDLED_SEQ` to that
number. While `seq` equals `BUNDLED_SEQ`, `PublishedBridgeListTest` requires
the file to carry exactly the bundled lines.

Between releases, a list update is a data-only commit: it changes
`docs/bridges/bridges.json` and `docs/bridges/bridges.json.sig` and nothing
else, carries `seq` one higher than the last published list, and is pushed to
`master` only with the owner's explicit approval. Such a list may carry other
lines; `PublishedBridgeListTest` then requires that it parses with the app's
`BridgeList`, that its `seq` is not below `BUNDLED_SEQ` and that its signature
verifies with the pinned key. The next release bundles its lines.

Steps:

1. Prepare and test the new lines as for a bundled refresh (see
   `docs/SUPPLY_CHAIN.md`): bootstrap Tor through every line on its own.
2. Write `docs/bridges/bridges.json` in the layout of the current file, with
   `issued` today and `expires` at most 366 days later (90 days is the usual
   choice). At release time also update the bundled resources,
   `bridge-sources.txt` and `BUNDLED_SEQ`.
3. Attach the offline medium that holds the signing key, run
   `java SignBridgeList.java <key on the offline medium> docs/bridges/bridges.json`
   from the repository, and detach the medium again. The tool asks for the
   passphrase of an encrypted key on the console, refuses a file with CR, an
   out-of-range date, a sequence that is not above the committed one for a
   changed file, or a key that does not match the pinned public key, and
   writes `bridges.json.sig` only after the new signature verifies.
4. Run
   `./gradlew :zerion-core:test --tests org.zerionproject.transport.PublishedBridgeListTest`,
   which parses the file with the app's `BridgeList` and verifies the
   signature with the pinned key, commit, and push to `master` as above.
   Installed apps that use the built-in bridges pick the list up within one to
   three days.

Cadence: a list runs 90 days, so publish a new one at least every 60 days.
`python3 scripts/check-docs.py --release`, part of the release gate and of the
weekly scheduled check, fails when `docs/bridges/bridges.json` expires less than
30 days after the day it runs; without `--release`, as on every push and pull
request, the same finding is only a warning, so the date alone never turns an
unrelated change red.

## Key rotation and compromise

The pinned key changes only through an app update. To rotate, a release pins
the new key next to the old one; once most installations have it, lists are
signed with the new key and numbering simply continues; a later release
removes the old key.

If the key leaks, whoever holds it can publish a list of bridges they run for
up to 366 days, and those bridges would see the network address of phones
that use them (they still cannot read messages, which travel end to end
encrypted over onion services). The response is an app update that removes
the leaked key and pins a new one: the stored list signed with the leaked key
is then no longer used, its sequence no longer counts, and a new list signed
with the new key is accepted from the next download. Until that update
reaches a phone, deleting `bridges.json` from `master` does not withdraw a list
the phone already accepted, so the update is the only remedy.

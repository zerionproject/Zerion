# Deniability: what a Zerion record proves to a third party

This page says what deniability means in Zerion, which records are deniable
and which are not, how to run the transcript simulator that forges deniable
records, and where deniability stops. The security claim it belongs to is the
row "Deniability of one-to-one content" in
[protocol/SECURITY_CLAIMS.md](protocol/SECURITY_CLAIMS.md); the constructions
are specified in [protocol/ZWF-MODE3FULL.md](protocol/ZWF-MODE3FULL.md),
[protocol/ZWF-ROOT-EVOLUTION.md](protocol/ZWF-ROOT-EVOLUTION.md),
[protocol/ASYNC-SEALED-SENDER.md](protocol/ASYNC-SEALED-SENDER.md) and
[protocol/PAIRING-HANDSHAKE.md](protocol/PAIRING-HANDSHAKE.md).

## What deniability means here

Every one-to-one record listed below is authenticated with a key that both peers
hold: a MAC tag or an AEAD tag under a key derived from the pair's shared
secret. The receiver knows the record came from the peer, because nobody else
holds the key. A third party learns nothing of the kind from the same record:
either peer, or anyone either of them hands the key to, can produce a record
with a valid tag. Deniable authentication therefore means that a transcript
proves to the receiver who wrote it and proves nothing about that to anyone
else.

The mathematics alone does not settle an accusation. Someone shown a
transcript and told "you wrote this" needs to be able to point at a tool with
which anyone, the accuser included, fabricates an identical-looking transcript
from the shared key. Without such a public tool the forgery is a theoretical
possibility that a court or an employer may not credit. This is the argument of
"Real-World Deniability in Messaging" (Collins, Colombo and Huguenin-Dumittan,
PoPETs 2025, https://petsymposium.org/popets/2025/popets-2025-0018.pdf):
deniability in practice needs a usable, published forger, not only a proof
that one could exist. The transcript simulator below is that tool for Zerion.

## Which records are deniable

| Record | Authenticated with | Who can forge it |
| --- | --- | --- |
| One-to-one messages over Tor (ZWF frames, Mode 3-Full) | the stream tag (`ZwfTag`, MAC under the per-direction tag key) and the AEAD tags of the frame, all derived from the contact root key (`ZwfSessionFactory`) | anyone holding the contact root key |
| Calls | call endpoint keys derived from the same pair secrets; media is AEAD | anyone holding the pair secrets |
| Contact requests | MACs and an AEAD seal under keys agreed for the request (`ContactRequestCrypto`) | anyone holding the request's agreed key |
| Root key renewals | confirmation MACs under keys derived from the evolving root (`RootEvolutionCrypto`) | anyone holding the root being renewed |
| Mesh envelopes to a contact that has announced messaging version 17 (3.0.19) at any time | the keyed trailer: a MAC under a key derived from the pair's link key (`AsyncSealedSender`, V2) | anyone holding the link key |
| Pairing by link or invite that creates a new contact between two 3.0.19 phones | the contact record carries one tag under the exchange key over the record's contents (role, author record, ML-DSA-65 key, transport properties, timestamp, static ML-KEM-768 key and session id); the identity keys sign nothing | anyone holding the exchange key, which both phones wipe when the exchange ends |
| Prekey bundles to a contact that has announced messaging version 17 (3.0.19) at any time | bundle version 2 carries no signature; the authenticated contact connection proves who sent it and the identity key inside must be the sending contact's | anyone can fabricate the bundle bytes; only the authenticated contact connection they arrive over binds them to a sender |

None of these records carries a signature by an identity key, so none of them
binds an identity to content in a way a third party can check.

## Which records are not deniable, and why

- **Group records** (posts, edits, deletions, pins, membership changes) are
  signed by the author's per-group member key. They have to be: members who
  are not each other's contacts, and the copies the group owner passes on,
  must be able to verify the author without a shared key. The signature names a
  pseudonymous member key; from 3.0.19 that key's link to the account is
  carried over the authenticated contact connection and is no longer
  certified by an identity signature towards a contact that has announced
  messaging version 17 at any time, so such a contact can assert, but not
  prove, whose key it is. Towards a contact that never announced version 17
  the binding is still signed by the account key.
- **Groups created before 3.0.18** sign with the account key. Those records
  prove to anyone that the account key produced them.
- **Channel feeds** are signed by the channel key, and comments and reactions
  by a key derived per channel from the member's identity key; the publisher
  and every subscriber must verify them without any shared key.
- **Introductions** always carry, in the AUTH step, an identity signature over
  a value derived from the key only the two introducees share, so an
  introduction is provable to a third party only with one introducee's
  cooperation (the third party cannot check the signature without that key).
  The signature is kept because the introducer relays both introducees'
  identities and keys: with a MAC alone it could present one introducee's real
  identity while running the other side itself and bind that identity to a
  contact only the introducer can reach, which the signature makes detectable.
  From 3.0.19, between two introducees that both announce key schedule 2,
  the new contact's root is derived beside the introduction's own keys and
  is never used during the introduction
  ([wire/F2_INTRODUCTION_HYBRID_SIG.md](wire/F2_INTRODUCTION_HYBRID_SIG.md),
  section 3c); the AUTH signature is the same in both forms.
  The simulator does not cover introductions.
- **Prekey bundles to a contact that has never announced messaging version
  17** (3.0.18 and earlier) are signed by the account key, as every bundle was
  up to 3.0.18. The simulator does not cover them.
- **Mesh envelopes to a contact that has never announced messaging version
  17** (3.0.18 and earlier) carry the account signature, and a pairing with a
  phone on an earlier version keeps the identity signatures of the older
  contact exchange.
- **Re-adding a contact you already have, and in-person pairings** (a QR code
  scanned in person, the nearby pairing) carry the identity signatures of the
  contact exchange even between two 3.0.19 phones, because replacing a stored
  contact's keys must prove possession of that contact's identity keys. The
  signatures cover values derived from the exchange key, so they are provable
  only to a holder of that key, which both phones wipe when the exchange ends.

## Which form a phone sends to a contact

From 3.0.19, once a contact has announced messaging version 17 this phone
remembers it and sends that contact only the unsigned forms: the keyed mesh
envelope, the version 2 prekey bundle and the group member key binding
without an identity signature. The memory is one flag per contact in the
encrypted settings (`UnsignedFormsMemory`). It is written in the same
database transaction that stores the contact's version update, so a later
lower announcement is never read without it; if the flag cannot be written,
the update is not stored either and is delivered again later. It survives
restarts and re-keys and is cleared only when the contact is removed. A
contact that announced version 17 and then a lower version while this phone
still ran 3.0.18 or earlier is not remembered, because only the latest
announcement was stored then.

Until a contact's messaging version is known (from its first version update,
which a new contact sends over the first connection) this phone sends it no
prekey bundle, seals no mesh envelope to it and makes no group member key
binding for it: accepting a group invitation from such a contact fails and
can be repeated, a key binding it sent is answered once its version is known,
and the bundle follows when the version arrives. The signed forms go only to a
contact whose known version is below 17 and that never announced 17. Toward
a contact that announced 17, a mesh send for which this phone holds no usable
link key is not sent over the mesh (Tor delivery is unchanged), and a mesh
send to someone who is not a contact, or during a database error, is not
sent instead of being signed. Receiving is unchanged: both forms are still
accepted from a contact.

What a contact can still obtain: a contact that announces a version below 17
from the start and never announces 17, such as a modified 3.0.19 phone,
receives account-signed mesh envelopes, prekey bundles and group bindings,
and so does a contact after the user removes it and adds it again. A backup
made on 3.0.19 can be chosen on 3.0.18, but the account does not open there
(its database is newer), so a contact cannot return to 3.0.18 under the same
identity; one that starts over on 3.0.18 is a new contact and gets the
signed forms like any 3.0.18 contact. A contact that once announced 17 and
then runs a build below 17 cannot take the unsigned group bindings this phone
keeps sending, so group invitations and key probes between the two do not
complete until it announces 17 again; Android does not install an older
version over a newer one without removing the account.

## The transcript simulator

`TranscriptSimulator` is a developer tool in `zerion-core/src/tools/java`. It
is compiled against the production classes, is not part of the app or of any
shipped artefact, and reuses the production code for every cryptographic step:
it never reimplements a tag. Each scenario prints the forged record set as hex
with an explanation, and with `--verify` re-checks the forgery with the
production verification code and prints `OK`.

Run it from the repository root (keys and other values that are not given are
generated and printed, so a run is reproducible):

```
./gradlew :zerion-core:simulateTranscript --args="one-to-one --verify a:Hello Bob b:Hello Alice a:I never wrote this"
./gradlew :zerion-core:simulateTranscript --args="one-to-one --root 00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff --verify a:Hi"
./gradlew :zerion-core:simulateTranscript --args="mesh --verify --send-time 1700000000000 Hello over the mesh"
./gradlew :zerion-core:simulateTranscript --args="pairing --verify --alice-name Carol --bob-name Dan"
./gradlew :zerion-core:simulateTranscript --args=""
```

The last command prints every option. What each scenario forges:

- `one-to-one` takes a 32-byte contact root key (`--root`, hex) or generates
  one, derives both directions' tag, header and ratchet keys exactly as
  `ZwfSessionFactory` does, runs two production `ZwfDuplexConnection`
  endpoints against each other and writes the given messages (`a:` from
  Alice, `b:` from Bob) as Mode 3-Full frames. It prints every frame: the
  16-byte stream tag, the stream header and the 4096-byte frame for the first
  message of a direction, one frame for each later message. The frames are
  what a real connection writes, including the ML-KEM-768 encapsulations to the
  peer's connection key once the peer has spoken. The endpoints draw their
  ML-KEM connection key pairs from a connection seed that is printed with the
  transcript. With `--verify` two fresh endpoints are built from the root key
  and that seed, the forged bytes are fed to them in order, and every frame
  must decrypt to its text; a `ZwfTagRecogniser` built from the root key alone
  must recognise the first frame of each direction. A real receiving phone
  holds its connection key pairs in memory instead of deriving them from a
  seed; the frames are the same either way.
- `mesh` takes or generates a 32-byte mesh link key, the sender's identity
  public key (1984 bytes; no private key is used), the send time, message type,
  time to live and the recipient's published prekey material, and seals a V2
  envelope with the keyed trailer exactly as `AsyncSealedSender.seal` does.
  With `--verify` `AsyncSealedSender.open` must open it with the link key as a
  keyed record naming that sender, text, type and send time.
- `pairing` takes or generates a 32-byte exchange key and both parties' public
  contact information (name and Ed25519 key for the author record, ML-DSA-65
  key, transport properties, timestamp, static ML-KEM-768 key and ephemeral
  X25519 key) and computes the tag each contact record carries between two
  3.0.19 phones when the pairing creates a new contact, over the production
  `ExchangeTranscript` inputs and under
  the production label. With `--verify` both tags must pass
  `CryptoComponent.verifyMac`.

The unit test `TranscriptSimulatorTest` in `zerion-core/src/test` runs every
scenario through the library entry points and the command line and asserts
that the forged records verify, and that they stop verifying under another
key, so the tool cannot fall behind the production code unnoticed:

```
./gradlew :zerion-core:test --tests org.zerionproject.tools.TranscriptSimulatorTest
```

The simulator can also be built as one self-contained file: run
`./gradlew :zerion-core:transcriptSimulatorJar` in a source checkout and take
`zerion-core/build/tools/zerion-transcript-simulator.jar`. It runs on any JDK
17 or later without Gradle or the source tree, for example
`java -jar zerion-transcript-simulator.jar mesh --verify Hello over the mesh`,
and with no scenario it prints the scenarios and every option. The jar holds
the tool and the whole runtime class path of `zerion-core`, and building it
twice from the same source gives the same file.
`./gradlew :zerion-core:checkTranscriptSimulatorJar` builds the jar and runs
each scenario with `--verify` from it; that task is not part of the app
build.

## Honest limits

- **A seized phone holds the keys and the history.** Deniability is about what
  a transcript proves to others. The phone's database holds the plaintext
  history and the shared keys, encrypted under the account password; whoever
  opens it reads the conversation and can also produce valid records for it.
  Deniability does not hide that content existed and does not help a device
  owner who is compelled to unlock the phone.
- **The peer can demonstrate what it holds, not who wrote it.** A contact who
  keeps the root key, a link key or an exchange key can show that it holds a
  key under which the records verify. That shows the two phones share that
  key, not which of them wrote a given record, because the contact could have
  written it as well.
- **Forgery needs the key.** Someone who does not hold the shared key cannot
  fabricate a record that verifies. The simulator demonstrates that the key
  holders are interchangeable as authors; it does not make transcripts
  forgeable by outsiders, and a verifying transcript still shows that one of the
  key holders produced it.
- **Metadata is a separate question.** Which phones talked, when and how much
  is addressed by Tor, the fixed-size frames and the paced cover traffic, with
  the limits in the whitepaper, not by deniable authentication.
- **Signed records stay signed.** Group, channel and legacy records listed
  above are provable, and so are records exchanged with phones on versions
  before the tagged forms were introduced. The release after 3.0.19 no
  longer produces or accepts the signed pairing record, prekey bundle and
  mesh envelope, so from then on only group, channel and introduction
  records are signed.

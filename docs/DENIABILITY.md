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
| Mesh envelopes between two phones that have exchanged mesh link keys (3.0.19) | the keyed trailer: a MAC under a key derived from the pair's link key (`AsyncSealedSender`, V2) | anyone holding the link key |
| Pairing between two 3.0.19 phones | the contact record carries one tag under the exchange key over the record's contents (role, author record, ML-DSA-65 key, transport properties, timestamp, static ML-KEM-768 key and session id); the identity keys sign nothing | anyone holding the exchange key, which both phones wipe when the exchange ends |
| Introductions between two 3.0.19 introducees through a 3.0.19 introducer | the AUTH step carries only the MAC under the key the two introducees derive, which already binds the introducer, both author ids, both timestamps, both ephemeral keys, both transport property sets and both ML-KEM keys; the identity keys sign nothing | either introducee, or anyone holding their shared key |
| Prekey bundles to a contact on 3.0.19 | bundle version 2 carries no signature; the authenticated contact connection proves who sent it and the identity key inside must be the sending contact's | anyone can fabricate the bundle bytes; only the authenticated contact connection they arrive over binds them to a sender |

None of these records carries a signature by an identity key, so none of them
binds an identity to content in a way a third party can check.

## Which records are not deniable, and why

- **Group records** (posts, edits, deletions, pins, membership changes) are
  signed by the author's per-group member key. They have to be: members who
  are not each other's contacts, and the copies the group owner passes on,
  must be able to verify the author without a shared key. The signature names a
  pseudonymous member key; from 3.0.19 that key's link to the account is
  carried over the authenticated contact connection and no longer certified by
  an identity signature towards 3.0.19 peers, so a contact can assert, but not
  prove, whose key it is.
- **Groups created before 3.0.18** sign with the account key, and so do group
  records towards peers on older versions. Those records prove to anyone that
  the account key produced them.
- **Channel feeds** are signed by the channel key, and comments and reactions
  by a key derived per channel from the member's identity key; the publisher
  and every subscriber must verify them without any shared key.
- **Introductions with an older introducer or introducee, and prekey bundles
  to a contact on 3.0.18 or earlier,** keep the signed forms of the earlier
  releases: the introduction's AUTH step then carries an identity signature over
  a value derived from the introduction's shared key, provable to a third party
  only with one introducee's cooperation, and the bundle is signed by the
  account key. Because the introducer relays the version announcements, a
  dishonest introducer can present the other introducee as older and so still
  obtain such a signature; that is what every introducer obtained up to 3.0.18,
  and the signature never protected against the introducer, which could already
  substitute the keys it relays. The simulator does not cover these two record
  kinds.
- **Mesh envelopes towards a phone without mesh link keys** (3.0.18 and
  earlier) carry the account signature, and a pairing with a phone on an
  earlier version keeps the identity signatures of the older contact exchange.

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
  3.0.19 phones, over the production `ExchangeTranscript` inputs and under
  the production label. With `--verify` both tags must pass
  `CryptoComponent.verifyMac`.

The unit test `TranscriptSimulatorTest` in `zerion-core/src/test` runs every
scenario through the library entry points and the command line and asserts
that the forged records verify, and that they stop verifying under another
key, so the tool cannot fall behind the production code unnoticed:

```
./gradlew :zerion-core:test --tests org.zerionproject.tools.TranscriptSimulatorTest
```

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
  before the tagged forms were introduced.

# ZTP and ZPP: Online Transport and Pull Rhythm

Two protocols carry the online path. ZTP is the transport seam that turns a
carrier into a byte stream between two paired contacts. ZPP is the rhythm that
runs over that stream, sending one fixed-size frame per time slot so that sending
and idling look the same to an observer.

Both run above ZWF (see ZWF-MODE3FULL.md). ZTP produces the stream, ZPP decides
what goes in each frame, and ZWF seals the frames.

## ZTP: Zerion Tor transport

ZTP is a transport seam, not a bespoke framing. It dials and accepts connections
and hands the resulting raw stream to the session stack. The default carrier is
Tor v3 onion services, reached through the inherited onion wrapper. The same seam
has an I2P variant (see EMBEDDED-I2P.md).

Behaviour:

- Outbound connections dial a contact's Tor v3 onion on port 80 through a SOCKS
  factory. The outbound side knows which contact it dialled.
- The local side publishes a hidden service and accepts up to 64 inbound
  connections. The socket timeout is 30 seconds.
- There is no per-connection handshake after pairing. The connection handler
  resumes the persisted session for the contact.

Inbound connections are anonymous, since a Tor onion accept does not name the
peer. ZTP resolves the peer by peeking the first 16 bytes of the stream, which are
the ZWF stream tag, and recognising that tag as a known contact and stream. An
unrecognised tag is rejected. First-time pairing uses a separate rendezvous path,
not this one.

On the wire, an online connection is therefore:

```
[ZWF stream tag: 16 bytes]
[ZWF stream header: 50 bytes]
[ZWF frame: 4096 bytes]
[ZWF frame: 4096 bytes]
...
```

Everything below the connection handler is identical for Tor and I2P.

## ZPP: paced pull protocol with two constant rates

ZPP runs over a ZWF duplex connection and shapes its timing. The send side emits
exactly one fixed-size ZWF frame per interval. That frame carries the next queued
record if there is one, or a cover record if the queue is empty. Because a real
record and a cover record are both a 4096-byte ZWF frame, an observer cannot tell
whether an interval carried a message or was idle, or how large a message was.

Timing (`ZppPacingPolicy`, `ZppConnectionRunnerImpl`):

- Two constant base intervals. The active interval, 750 ms, applies while
  application records flowed in the last two minutes or are queued. The idle
  interval, 4 s, applies afterwards; on a metered network it is 8 s unless the
  user disables the mobile-data reduction in Settings.
- Each interval adds uniform zero-mean jitter of up to one third of its length.
- A frame is never sent closer than the active spacing, so the onset of
  activity cannot burst, and a stall lengthens the cadence rather than causing a
  catch-up burst.

What this hides and what it does not: within a rate, message timing, size and
count are hidden. The switch from the idle to the active rate, and back after
two quiet minutes, reveals the coarse onset and end of activity. Connection
existence, lifetime and reconnects are visible to an observer of the Tor link.

The receive side decodes each frame and drops cover before delivering the record
to the sink.

## Application records: ZMM

The records carried inside ZWF frames are ZMM records with a small header:

```
[type: uint16 big-endian][payload]
```

Both the type and the payload sit inside the ZWF frame AEAD, so neither the record
type nor the record length is visible on the wire. A cover record has the cover
type and an empty payload. The receive side identifies cover by comparing the type
word and does not deliver it.

## Relationship to Briar

Briar's own synchronisation transport is not used on this path. ZTP and ZPP
replace it with a constant-rate stream. The onion wrapper that provides the Tor
carrier, and the database that stores contacts and sessions, are inherited Briar
and Bramble components.

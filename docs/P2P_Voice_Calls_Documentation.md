# Zerion P2P Voice Calls - Technical Documentation

## Overview

Zerion provides **end-to-end encrypted peer-to-peer voice calls** that route exclusively through the Tor network, ensuring complete anonymity and privacy for all participants. Unlike traditional VoIP services, Zerion calls never touch centralized servers, and no third party can intercept, monitor, or trace your conversations.

---

## How Zerion P2P Voice Calls Work

### Architecture

Zerion voice calls use a **direct peer-to-peer architecture** over Tor hidden services:

```
[Caller Device] ←──── Tor Network ────→ [Callee Device]
     ↑                                         ↑
     └─────── No servers in between ──────────┘
```

### Technical Flow

#### 1. **Call Initiation**
When you initiate a call:
- Zerion generates a unique **voice call encryption key** using cryptographically secure random generation
- The caller sends a **CALL_OFFER** signal containing:
  - Unique call ID
  - Voice call encryption key (for end-to-end audio encryption)
- **Dedicated Signaling Channel**: Voice call signals use a dedicated `VOICE_SIGNAL` message type (type=2), completely separate from text messages. This ensures:
  - Voice signals never appear in the conversation UI
  - Clean separation between messaging and voice call protocols
  - Reliable signal delivery without message clutter

#### 2. **Hidden Service Creation (Callee Side)**
When the recipient accepts:
- The callee creates a **Tor v3 hidden service** (.onion address)
- A local server socket listens for the incoming Tor connection
- The hidden service descriptor is published to Tor's distributed hash table
- **Synchronization**: Zerion waits for Tor's `onHsDescriptorUpload` callback, ensuring the hidden service is reachable before proceeding
- The callee sends a **CALL_ANSWER** message containing their .onion address

#### 3. **Tor Connection Establishment**
The caller receives the .onion address and:
- Connects directly to the callee's hidden service through the Tor network
- Uses **exponential backoff retry logic** (6 attempts over ~31 seconds)
- Establishes a **bidirectional Tor connection** (DuplexTransportConnection)

#### 4. **Audio Streaming with Opus Compression**
Once connected:
- Audio is captured from the device microphone (16kHz, 16-bit mono PCM)
- **Opus codec compression**: PCM audio is compressed using Opus codec (VOIP mode)
  - 20ms frames (320 samples @ 16kHz = 640 bytes PCM)
  - Compressed at 24 kbps bitrate
  - **~10x compression ratio** (256 kbps PCM → 24 kbps Opus)
  - Forward Error Correction (FEC) enabled for packet loss resilience
  - Packet Loss Concealment (PLC) synthesizes audio for lost frames
- Encrypted with the shared **voice call key** using AES-256-GCM with unique IV per frame
- **Integrity protection**: CRC32 checksum added for additional corruption detection
- Transmitted through the Tor connection with sequence numbers for ordering
- **Stream synchronization**: SYNC marker (0x5A455249 "ZERI") and READY marker ensure proper alignment
- Decrypted and validated on the receiving end
- **Opus decoding**: Compressed audio is decoded back to PCM (or PLC applied if corrupted)
- **Jitter buffer**: 200-350ms circular buffer smooths playback despite network variance
- Played through the device earpiece (USAGE_VOICE_COMMUNICATION for proper audio routing)
- **Audio features**: Mute function, speakerphone toggle (with 2.0x volume gain), and proper permission handling
- **Security features**: Screenshot protection (FLAG_SECURE) prevents screen capture during calls
- **Network quality indicators**: Real-time display of latency, packet loss, signal strength, and codec info

---

## Why Zerion Calls Are Anonymous

### 1. **No IP Address Exposure**

**Traditional Calls (WebRTC, VoIP, Cellular):**
```
Your IP (123.45.67.89) ←→ Server ←→ Recipient IP (98.76.54.32)
     ↑                                            ↑
  Tracked                                     Tracked
```

**Zerion Calls:**
```
Your Device ←→ [Tor Entry] ←→ [Tor Relay] ←→ [Tor Exit/HS] ←→ Recipient Device
                  ↑                                  ↑
           Your IP hidden              Recipient IP hidden
```

- **Your IP address is never revealed** to the recipient
- **Recipient's IP address is never revealed** to you
- Both parties only see .onion addresses (e.g., `abc123...xyz456.onion`)

### 2. **Tor v3 Hidden Services**

Zerion uses **Tor v3 onion services** (56-character addresses):
- Each call generates a **unique, temporary .onion address**
- The address is only valid for the duration of the call
- Hidden services use **rendezvous points** in the Tor network
- Neither party knows the physical location of the other

**Tor Circuit for Hidden Services:**
```
Caller → Guard → Middle → Rendezvous ← Middle ← Guard ← Callee
          ↓                   ↓                           ↓
    Encrypted          Both sides meet          Encrypted
                       No direct path
```

### 3. **End-to-End Encryption**

**Three Layers of Encryption:**

1. **Transport Layer (Tor)**: All traffic is encrypted through Tor's onion routing (minimum 3 hops)
2. **Connection Layer (TLS-like)**: Tor hidden service connections use authenticated encryption
3. **Application Layer (Voice Call Key)**: Audio frames are encrypted with AES-256-GCM using a unique key per call

**Even if an attacker controlled Tor nodes**, they would only see:
- Encrypted Tor traffic (cannot decrypt without breaking Tor's cryptography)
- Cannot determine it's a voice call vs. messaging
- Cannot access the audio content (protected by the voice call key)

### 4. **No Metadata Leakage**

**What Traditional Services Collect:**
- Call duration
- Caller and callee phone numbers/IPs
- Time of call
- Location data (cell towers, GPS)
- Device identifiers
- Contact lists

**What Zerion Knows:**
- **Nothing**. Zerion has no servers, no databases, no logs
- Call signaling uses a dedicated `VOICE_SIGNAL` message type (separate from text messages)
- Audio streams directly peer-to-peer through Tor
- No call records, no CDRs (Call Detail Records)

### 5. **Decentralized Architecture**

```
Traditional VoIP:              Zerion:
┌──────────┐                   ┌──────────┐
│  User A  │──┐                │  User A  │
└──────────┘  │                └──────────┘
              ↓                      ↓
         ┌─────────┐            ┌─────────┐
         │ SERVER  │            │   TOR   │ (distributed)
         │ (logs)  │            │ (no logs)
         └─────────┘            └─────────┘
              ↓                      ↓
┌──────────┐  │                ┌──────────┐
│  User B  │──┘                │  User B  │
└──────────┘                   └──────────┘
```

- No central server = No single point of surveillance
- No company can be compelled to hand over call records
- No service provider to correlate metadata across calls

---

## Privacy Guarantees

### Against Network Observers (ISP, Government)

**What they can see:**
- You're using Tor (encrypted traffic to Tor entry node)
- Traffic volume (cannot determine if it's voice, text, or file transfer)

**What they CANNOT see:**
- Who you're calling
- Content of the call
- Duration of the call (Tor connections stay open)
- Your Tor circuit path or destination

### Against Tor Network Adversaries

**Even if an attacker controls multiple Tor nodes:**
- Cannot correlate caller and callee (hidden service circuits use different paths)
- Cannot decrypt the audio (protected by voice call key)
- Cannot perform traffic analysis (Opus codec uses variable bitrate)

### Against Device Seizure

**If your device is seized:**
- No call history stored on device
- Voice call keys are generated per-call and discarded after
- No persistent .onion addresses for voice calls
- Forensic analysis reveals no record of who you called

---

## Technical Security Features

### Cryptographic Primitives

| Component | Algorithm | Key Size |
|-----------|-----------|----------|
| Voice Call Key Generation | CSPRNG (SecureRandom) | 256 bits |
| Audio Encryption | AES-256-GCM | 256 bits |
| Tor Transport | ChaCha20-Poly1305 | 256 bits |
| Hidden Service Identity | Ed25519 | 256 bits |

### Codec Security

**Opus Codec:**
- Open-source, audited by security researchers
- Designed for real-time communication
- Variable bitrate prevents traffic fingerprinting
- No known vulnerabilities for side-channel attacks

### Voice Signaling Protocol

**Dedicated VOICE_SIGNAL Message Type:**
- Voice call signals use message type `2` (separate from PRIVATE_MESSAGE type `0`)
- **Signal Types:**
  - `CALL_OFFER (0)` - Initiates a call with encryption key
  - `CALL_ANSWER (1)` - Accepts call with .onion address
  - `CALL_REJECT (2)` - Declines incoming call
  - `CALL_END (3)` - Terminates call with optional duration
  - `ICE_CANDIDATE (4)` - Network connectivity data
  - `CALL_BUSY (5)` - Callee is in another call
- Signals are delivered via `VoiceSignalReceivedEvent` to the voice call service
- Complete isolation from text messaging prevents UI clutter and protocol confusion

### Connection Security

**Tor Hidden Service v3:**
- 56-character onion addresses (vs. 16 for v2)
- Resistant to enumeration attacks
- Improved cryptography (Ed25519, SHA3-256)
- Better security against guard discovery

---

## Use Cases for Anonymous Calling

### Journalists & Sources
- Protect source identity during sensitive calls
- No phone number exchange required
- No call records for subpoenas

### Activists & Dissidents
- Organize without government surveillance
- Avoid cell tower tracking
- Bypass telecommunication monitoring

### Privacy-Conscious Individuals
- Personal calls without corporate data collection
- Avoid telecom metadata retention laws
- Prevent call graph analysis

### Whistleblowers
- Contact journalists or lawyers anonymously
- No traceable connection between parties
- Plausible deniability (no call logs)

---

## Comparison to Other Technologies

| Feature | Zerion | Signal | WhatsApp | Cellular | Tor Alone |
|---------|--------|--------|----------|----------|-----------|
| End-to-End Encrypted | ✅ | ✅ | ✅ | ❌ | N/A |
| IP Address Hidden | ✅ | ❌ | ❌ | ❌ | ✅ |
| No Phone Number | ✅ | ❌ | ❌ | ❌ | ✅ |
| No Central Server | ✅ | ❌ | ❌ | ❌ | ✅ |
| No Metadata Logs | ✅ | ⚠️ | ❌ | ❌ | ✅ |
| P2P Voice | ✅ | ❌ | ❌ | ❌ | ❌ |
| Works Offline (Mesh) | ⚠️ | ❌ | ❌ | ❌ | ❌ |

**Legend:**
- ✅ Full support
- ⚠️ Partial/planned support
- ❌ Not supported

---

## Performance Characteristics

### Latency
- **Typical**: 2-5 seconds (Tor overhead)
- **Factors**: Tor circuit quality, geographic distance, network congestion
- **Trade-off**: Privacy vs. latency (privacy prioritized)

### Audio Quality
- **Sample Rate**: 16kHz (optimized for voice)
- **Format**: 16-bit mono PCM (internal)
- **Codec**: Opus VOIP mode (pure Java implementation via Concentus)
  - 20ms frame duration (320 samples)
  - 24 kbps bitrate with variable bitrate (VBR)
  - Forward Error Correction (FEC) enabled
  - Packet Loss Concealment (PLC) for lost/corrupted frames
- **Compression Ratio**: ~10x (256 kbps PCM → 24 kbps Opus)
- **Quality**: Clear voice communication, optimized for speech
- **Bandwidth**: ~24 kbps + ~10% encryption/overhead = ~26 kbps (down from 256 kbps)

### Connection Reliability
- **Retry Logic**: Exponential backoff (6 attempts, ~31s window)
- **Descriptor Upload**: Synchronized (15s timeout) - ensures hidden service is reachable before proceeding
- **Circuit Stability**: Tor automatically rebuilds failed circuits
- **Heartbeat Mechanism**: 30-second keepalive prevents Tor circuit timeout
- **Stream Synchronization**: SYNC and READY markers ensure proper stream alignment
- **Handler Cleanup**: Pending reconnection attempts are properly cancelled on call end

---

## Technical Limitations & Considerations

### Latency Trade-off
- Tor routing adds 1-3 seconds of latency
- Not suitable for real-time gaming or music collaboration
- Excellent for voice conversations

### Network Requirements
- Requires stable internet connection
- Tor must be connected (bootstrap complete)
- Uses more bandwidth than direct calls (~2x overhead)

### Device Requirements
- Microphone and speaker/headphones
- Android 5.0+ (API 21+)
- **RECORD_AUDIO** permission (requested at runtime)
- **Foreground service** permission (microphone type)
- ~24 kbps upload/download bandwidth (with Opus codec)
- Battery usage higher than regular calls (Tor overhead)
- **Audio Routing**: Automatic earpiece routing with speakerphone toggle support
- **Network Quality Monitoring**: Real-time latency, packet loss, and signal strength indicators

### Legal Considerations
- Tor is legal in most countries
- Some jurisdictions restrict or monitor Tor usage
- Use bridges/obfuscation in restricted regions
- Check local laws before use

---

## Future Enhancements

### Planned Features
- **Group Calls**: Multi-party encrypted voice conferencing
- **Connection Padding**: Traffic analysis resistance
- **Onion Routing v4**: When Tor upgrades hidden service protocol
- **Call History UI**: Call event bubbles in conversation view
- **Enhanced Network Diagnostics**: Detailed connection quality graphs and history

### Research Areas
- **QUIC over Tor**: Reduced latency and better reliability
- **Acoustic Fingerprinting Resistance**: Prevent voice identification
- **Forward Secrecy**: Rotate voice call keys during long calls

---

## Frequently Asked Questions

### Q: Can government agencies trace my calls?
**A:** No. Zerion calls route through Tor hidden services, which are designed to resist traffic analysis even by global adversaries. Without breaking Tor's cryptography (considered infeasible), calls cannot be traced.

### Q: What if my ISP blocks Tor?
**A:** Use Tor bridges (obfs4, Snowflake, Meek) to circumvent censorship. Zerion supports all Tor bridge types.

### Q: Can the recipient see my phone number?
**A:** No. Zerion doesn't use phone numbers. You only exchange Briar contact IDs (cryptographic identities), and calls show .onion addresses.

### Q: Are calls recorded?
**A:** No. Zerion has no servers and no call recording functionality. Audio exists only in memory during the call and is immediately discarded.

### Q: What if someone seizes my phone?
**A:** No call history is stored. Forensic analysis won't reveal who you called or when. Use Zerion's screen lock and panic button features for additional security.

### Q: How does Zerion compare to Signal?
**A:** Signal has better latency and audio quality but requires phone numbers and exposes IP addresses. Zerion prioritizes anonymity over performance.

### Q: Can I use Zerion for emergency calls?
**A:** No. Tor latency makes emergency services unsuitable. Use traditional cellular for emergencies.

---

## Technical Architecture Diagram

```
┌─────────────────────────────────────────────────────────────────┐
│                        CALLER DEVICE                            │
│                                                                 │
│  ┌──────────────┐      ┌─────────────┐      ┌──────────────┐  │
│  │ Microphone   │─────→│ Opus Encoder│─────→│ AES-256-GCM  │  │
│  │ (16kHz PCM)  │      │ (24kbps)    │      │ Encryption   │  │
│  └──────────────┘      └─────────────┘      └───────┬──────┘  │
│                                                      │         │
│                                              ┌───────▼──────┐  │
│                                              │ Tor Socket   │  │
│                                              │ (Outgoing)   │  │
│                                              └───────┬──────┘  │
└──────────────────────────────────────────────────────┼─────────┘
                                                       │
                         ┌─────────────────────────────▼──────────────────────────────┐
                         │              TOR NETWORK (3+ Hops)                         │
                         │                                                            │
                         │  ┌──────┐    ┌──────┐    ┌────────────┐    ┌──────┐     │
                         │  │Guard │───→│Middle│───→│Rendezvous  │←───│Middle│     │
                         │  │ Node │    │ Node │    │   Point    │    │ Node │     │
                         │  └──────┘    └──────┘    └────────────┘    └──────┘     │
                         │                                ↑                          │
                         │                                │                          │
                         └────────────────────────────────┼──────────────────────────┘
                                                          │
┌─────────────────────────────────────────────────────────┼─────────┐
│                        CALLEE DEVICE                    │         │
│                                                ┌────────▼──────┐  │
│                                                │ Hidden Service│  │
│                                                │ (.onion:80)   │  │
│                                                └────────┬──────┘  │
│                                                         │         │
│  ┌──────────────┐      ┌─────────────┐      ┌─────────▼──────┐  │
│  │ Speaker      │◄─────│ Opus Decoder│◄─────│ AES-256-GCM    │  │
│  │ (16kHz PCM)  │      │             │      │ Decryption     │  │
│  └──────────────┘      └─────────────┘      └────────────────┘  │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

---

## Conclusion

Zerion P2P voice calls represent the **gold standard for anonymous, private communication**. By combining:
- **Tor hidden services** (anonymity)
- **End-to-end encryption** (confidentiality)
- **Peer-to-peer architecture** (decentralization)
- **No metadata collection** (privacy)

Zerion provides a level of protection that no centralized service can match. While this comes with trade-offs in latency and bandwidth, the privacy benefits are unparalleled for users who need true anonymity.

For journalists, activists, whistleblowers, and privacy-conscious individuals, Zerion offers a communication tool that resists even well-funded adversaries with global surveillance capabilities.

---

**Last Updated:** 2026-06-12
**Version:** 2.0.2 — post-quantum signalling envelope (Mode 3-Full), full video pipeline, current as of v2.0.2
**License:** CC BY-SA 4.0

---

## Implementation Status (v2.0.2)

### Voice calls — shipped

- P2P voice calling over Tor v3 hidden services
- End-to-end encryption with AES-256-GCM (per-frame authenticated, counter-based nonces)
- Per-call symmetric key (256-bit, fresh per call), delivered through the dedicated `VOICE_SIGNAL` message type
- Signalling key delivery rides Mode 3-Full, the per-message ML-KEM-768 + X25519 hybrid ratchet that is the default since v1.7 — call keys travel inside a post-quantum-encrypted envelope, with a fresh ML-KEM-768 encapsulation on every frame
- Opus codec at 24 kbps (VoIP mode with FEC/PLC), CRC32 integrity check, jitter buffer
- Heartbeat for Tor circuit keepalive
- Stream sync (SYNC / READY markers)
- Audio routing to earpiece (`USAGE_VOICE_COMMUNICATION`), speakerphone toggle with optional gain
- Mute, in-call UI bubbles, network-quality readouts (latency, packet loss, codec, bitrate)
- `VOICE_SIGNAL` channel — `CALL_OFFER` / `CALL_ANSWER` / `CALL_REJECT` / `CALL_END` / `ICE_CANDIDATE` / `CALL_BUSY` — fully separated from text messaging, never rendered in conversation UI
- `FLAG_SECURE` on active call activity (screenshot/recording prevention)
- `VoiceSignalReceivedEvent` routing for incoming-call handling

### Video calls — shipped

- H.264 Main Profile Level 3.1, 640x480 @ 24 fps / 600 kbps (primary mode)
- Adaptive quality controller steps frame rate and bitrate down under load: 15 fps / 250 kbps → 10 fps / 150 kbps → 5 fps / 80 kbps → video off, recovering when conditions improve
- AES-256-GCM frame encryption with deterministic padding to defeat frame-size analysis
- Per-frame rotation metadata (correct portrait orientation across camera switches)
- Camera switch with async callback for correct transform
- Auto-speaker on video start, mute / speaker active-state indicators
- AES-GCM authentication failure detection (treated as stream integrity failure)
- Clean encoder drain on hang-up (EOS flag), decoder consecutive-failure tracking
- `FLAG_SECURE` on the video call activity

### Changes affecting voice / video

- The signalling channel that delivers call keys is Mode 3-Full, the per-message PCS ratchet that is the default since v1.7. Rather than rotating ML-KEM-768 once per epoch, it performs a fresh ML-KEM-768 encapsulation on every frame, so the envelope carrying call keys is post-quantum-protected on a per-message basis. See [PCS_DESIGN.md](PCS_DESIGN.md).
- Bluetooth audio routing logic is unaffected by the v1.6.2 removal of the Bluetooth *transport* plugin. The transport plugin governed BLE / Bluetooth-LE pairing between devices and was unrelated to A2DP / HFP audio output, which routes through the standard Android `AudioManager`.

### Planned

- Call history persistence
- Adaptive bitrate based on network conditions
- Group voice calls
- Connection padding for traffic-analysis resistance
- Enhanced network diagnostics with graphs

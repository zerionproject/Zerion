# Revolutionary P2P Voice Calling: True Privacy, No Servers

## The Future of Secure Communication is Here

Zerion introduces groundbreaking peer-to-peer voice calling that operates without any servers, ensuring your conversations remain completely private and untraceable.

## What Makes Zerion Different?

### 🔐 **Zero Servers, Zero Surveillance**
While other "secure" messengers route your calls through their servers, Zerion connects you directly to your contact. No company servers, no government backdoors, no metadata collection.

### 🌐 **Powered by Tor Network**
Every call is routed through the Tor network using v3 onion services, making it impossible to trace who's calling whom or intercept the conversation.

### 🎭 **Complete Anonymity**
- No phone numbers required
- No IP addresses exposed
- No call logs stored
- No location tracking
- No metadata whatsoever

## How It Works

### Simple 3-Step Process

1. **Tap the Call Button**
   Your device creates a secure, temporary address on the Tor network

2. **Direct Connection**
   The app establishes a direct, encrypted tunnel to your contact

3. **Private Conversation**
   Your voice travels directly to your contact with no servers in between

## Comparison with Other Apps

| Feature | **Zerion** | Signal | WhatsApp | Telegram |
|---------|----------|--------|----------|----------|
| **Servers Required** | ❌ None | ✅ Yes | ✅ Yes | ✅ Yes |
| **Phone Number** | ❌ Not needed | ✅ Required | ✅ Required | ✅ Required |
| **IP Address Hidden** | ✅ Always | ❌ Exposed | ❌ Exposed | ❌ Exposed |
| **Call Metadata** | ❌ None | ✅ Stored | ✅ Stored | ✅ Stored |
| **Government Requests** | ❌ Impossible | ✅ Possible | ✅ Complies | ✅ Complies |
| **Can Be Blocked** | ❌ Tor Resistant | ✅ Easy | ✅ Easy | ✅ Easy |

## Real-World Benefits

### For Journalists
Communicate with sources without leaving any digital trail. No call records, no metadata, no evidence of communication.

### For Activists
Organize without fear of surveillance. Governments can't monitor what doesn't go through servers.

### For Business
Discuss sensitive matters knowing that no competitor or adversary can intercept or trace your calls.

### For Everyone
Your personal conversations remain personal. No corporation profits from your data, no algorithm analyzes your calls.

## Technical Excellence

### Audio Quality
- **Clear Voice**: 16kHz sample rate optimized for telephone-quality voice
- **Opus Codec**: ~16 kbps with 16x compression (via Concentus pure Java library)
- **Forward Error Correction**: Built-in FEC handles packet loss gracefully
- **Intelligent Buffering**: 200-350ms jitter buffer ensures smooth playback
- **Packet Loss Concealment**: Synthesizes audio for lost frames

### Security Features
- **End-to-End Encryption**: AES-256-GCM for audio frames
- **Triple-Layer Protection**: Tor encryption + connection encryption + audio encryption
- **Perfect Forward Secrecy**: Each call uses unique, randomly-generated keys
- **Authentication**: Only verified contacts can call
- **Integrity Checking**: CRC32 + GCM authentication prevents tampering
- **Stream Synchronization**: SYNC/READY markers prevent stream confusion attacks
- **Screenshot Protection**: FLAG_SECURE prevents screen capture during calls
- **Dedicated Signaling**: Voice signals use separate protocol from text messages
- **Post-Quantum Ready**: 128-bit post-quantum security on all symmetric encryption

### Performance
- **Fast Connection**: Typically 2-5 seconds with Tor rendezvous
- **Latency**: 2-5 seconds (Tor overhead, trade-off for complete anonymity)
- **Reliable**:
  - Exponential backoff retry logic
  - 30-second heartbeat keeps circuits alive
  - Tor automatically rebuilds failed circuits
- **Battery Efficient**: Optimized for mobile devices with hardware audio processing
- **Seamless UX**:
  - Automatic earpiece routing
  - One-tap mute function
  - Speakerphone toggle with volume boost
  - Proper permission handling
  - Network quality indicators (latency, packet loss, signal strength)

## Why This Matters

### The Problem with Current Solutions
Every popular messaging app today relies on centralized servers:
- **Signal** routes calls through their servers
- **WhatsApp** processes calls via Facebook infrastructure
- **Telegram** uses their cloud servers
- **Even "secure" apps** create metadata trails

These servers can be:
- 📊 Monitored by governments
- 🚫 Blocked by censors
- 💾 Forced to store logs
- 🎯 Targeted by hackers
- 📍 Used to track your location

### The Zerion Solution
By eliminating servers entirely, Zerion makes mass surveillance impossible. There's simply nothing to monitor, block, or hack.

## Frequently Asked Questions

### Is it really anonymous?
**Yes.** Tor hides your IP address, Zerion doesn't require phone numbers, and no servers means no logs.

### Can governments block it?
**Very difficult.** Blocking Zerion would require blocking the entire Tor network, which is nearly impossible.

### What about call quality?
**Excellent for voice.** While video calling isn't available yet, voice quality is crystal clear with minimal delay.

### Does it work internationally?
**Perfectly.** Distance doesn't matter when using Tor. Call anyone, anywhere, with the same quality.

### Is it legal?
**Yes.** Zerion is legal privacy software. However, users should comply with local laws regarding encryption.

### Can calls be recorded?
**Only by you.** The system prevents third-party recording, but users can record on their own devices if desired.

## Technical Specifications

### Requirements
- **Platform**: Android 5.0 (API 21) or higher
- **Network**: ~100 kbps minimum bandwidth (Opus codec compression)
- **Storage**: Less than 100 MB
- **Permissions**: Microphone (RECORD_AUDIO) and Internet only
- **Features**: Works through earpiece or speakerphone with mute support

### Open Source
Zerion is fully open source. Security researchers and developers can audit every line of code.

## Get Started Today

Experience true communication privacy. No phone numbers, no servers, no surveillance.

### Download Zerion
Available for Android devices. iOS version coming soon.

### Join the Revolution
Be part of the movement for genuine digital privacy. Your conversations belong to you, not to corporations or governments.

---

## The Technology Behind Privacy

### Tor Network Integration
Zerion leverages the battle-tested Tor network, the same technology that protects journalists, activists, and privacy-conscious individuals worldwide.

### Peer-to-Peer Architecture
Direct connections mean:
- No central point of failure
- No company can shut it down
- No server costs or maintenance
- Infinite scalability

### Current Features (v1.4)
- ✅ **P2P Voice Calling**: Direct peer-to-peer over Tor
- ✅ **End-to-End Encryption**: AES-256-GCM audio encryption
- ✅ **Opus Codec**: 16 kbps compression via Concentus (16x bandwidth reduction)
- ✅ **Dedicated Signaling**: VOICE_SIGNAL protocol separate from text messages
- ✅ **Stream Synchronization**: Reliable audio streaming with jitter buffering
- ✅ **Mute & Speakerphone**: Full call control features with volume boost
- ✅ **Call Events**: Signal/Molly-style call history in conversations
- ✅ **Screenshot Protection**: FLAG_SECURE prevents screen capture during calls
- ✅ **Network Quality**: Real-time latency, packet loss, and signal strength indicators
- ✅ **Forward Error Correction**: Built-in FEC and PLC for packet loss resilience
- ✅ **Post-Quantum Hardened**: Argon2id KDF with 128-bit post-quantum security

### Future Developments
- 📹 **Video Calling**: P2P video over Tor with same privacy guarantees
- 👥 **Group Calls**: Multi-party encrypted voice conferencing
- 🔋 **Adaptive Bitrate**: Dynamic quality adjustment based on network conditions
- 🔐 **ML-KEM/ML-DSA**: Full post-quantum key exchange and signatures

## Why We Built This

In an age of mass surveillance and data harvesting, we believe private communication is a human right. Zerion's P2P voice calling is our contribution to a more private, more secure future.

Every call you make with Zerion is a vote for privacy, a stand against surveillance, and a step toward a truly free internet.

---

*Join thousands who've already chosen real privacy. Download Zerion today.*

**No servers. No surveillance. No compromise.**
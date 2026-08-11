# Embedded I2P Carrier

I2P is an optional carrier. It lets Zerion reach the I2P network as a second
overlay alongside Tor. It is off by default and is enabled by the user behind a
consent screen, because I2P has a different privacy property from Tor that the
user should understand before turning it on.

Below the transport seam, I2P is identical to Tor: an I2P stream carries the same
ZWF and ZPP stack as a Tor stream (see ZTP-ZPP.md and ZWF-MODE3FULL.md). Only the
carrier differs.

## Transport seam

The I2P transport extends the same overlay transport interface as Tor. It has two
implementations: a SAM bridge to a router run outside the app, and an embedded
router reached over I2CP. The SAM variant mirrors the Tor transport: it opens a
session, recreates the destination from a persisted key, binds a local server
socket for inbound streams, and dials outbound with a stream connect. The address
property is the I2P destination. Inbound connection limit is 64.

## Embedded router configuration

When the embedded router is used, its assets are extracted into app-private
storage and the router runs in a low-footprint, hidden configuration. The relevant
router properties are:

| Property | Value | Effect |
| --- | --- | --- |
| `i2cp.disableInterface` | true | No external I2CP interface |
| `router.maxParticipatingTunnels` | 0 | Do not relay other peers' traffic |
| `router.floodfillParticipant` | false | Never act as a floodfill node |
| `i2p.hiddenMode` | true | Do not publish a reachable address; do not accept inbound |
| `i2np.udp.enable` | false | Disable the SSU (UDP) transport |
| `i2np.ntcp2.enable` | true | Use the NTCP2 (TCP) transport only |
| `i2np.inboundKBytesPerSecond` | 128 | Inbound bandwidth cap |
| `i2np.outboundKBytesPerSecond` | 64 | Outbound bandwidth cap |
| `i2np.upnp.enable`, `router.enableUPnP` | false | No UPnP port mapping |

Router logging is forced to the critical level. Startup polls for up to 240
seconds for the router to become ready, and stop performs a hard shutdown.

## Reseed over Tor

An I2P router must reseed once to learn its first peers. Zerion forces that reseed
through Tor. When a Tor SOCKS port is available, the router is configured with:

| Property | Value |
| --- | --- |
| `router.reseedSSLProxyEnable` | true |
| `router.reseedSSLProxyType` | SOCKS5 |
| `router.reseedSSLProxyHost` | 127.0.0.1 |
| `router.reseedSSLProxyPort` | the Tor SOCKS port |
| `router.reseedSSLRequired` | true |

Reseed is required to use SSL through the proxy, so it fails closed rather than
falling back to a direct fetch.

## Privacy property and the consent screen

Reseed over Tor hides the bootstrap. It does not hide ongoing participation. Once
the router is running it opens direct NTCP2 connections to I2P peers by their
addresses, from the device's own address. A network observer can therefore see
that the device is using I2P, though not what it sends. This is a property of I2P
itself: I2P is a peer-to-peer overlay whose transport cannot be tunnelled through
Tor, since Tor carries only TCP and cannot receive inbound.

The hidden-mode and NTCP2-only settings above reduce the footprint. The device
does not advertise itself as a reachable node, does not relay for others, and does
not use the UDP transport, so it makes only outbound TCP connections. It cannot be
made invisible while remaining functional.

Because of this, I2P is presented to the user with a consent screen that states
plainly that the content stays encrypted and unreadable, but that the user's
network can see that I2P is in use, and that the user's address is not hidden for
I2P traffic the way it is for Tor. The user enables I2P only after accepting that.

## Status

I2P is a secondary carrier for reaching the I2P network. Tor remains mandatory and
always on. I2P does not replace it.

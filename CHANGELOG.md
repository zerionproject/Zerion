# Changelog

Release notes for every published version. The current architecture and its security properties are described in [README.md](README.md), [SECURITY.md](SECURITY.md) and [docs/](docs/); an entry below describes its own release only and is not updated afterwards. Artifacts and hashes are on [GitHub Releases](https://github.com/zerionproject/Zerion/releases); the values for the current release are in [docs/release-manifest.json](docs/release-manifest.json).

## 3.0.19 (unreleased)

- Pairing and root evolution: when both phones run 3.0.19, the key a new contact is stored with, and each new key from root evolution, is no longer used during the exchange that creates it; the proofs and the contact details are protected by separate keys. Pairings and evolutions with older versions work as before, and existing contacts keep their keys. Details in [docs/protocol/PAIRING-HANDSHAKE.md](docs/protocol/PAIRING-HANDSHAKE.md) and [docs/protocol/ZWF-ROOT-EVOLUTION.md](docs/protocol/ZWF-ROOT-EVOLUTION.md).
- Contacts: an add that turns out to be with someone who is already your contact no longer replaces that contact's keys unless both of you meant it: you started it from Re-add contact (a request sent from there says so, and the receiver sees a warning before accepting), you accepted such a request, or you scanned each other's code in person. The Requests screen then shows "Already a contact" instead of "Could not connect". Before, accepting a contact request from someone holding a copy of a contact's identity replaced that contact's keys and kept its verified mark and history. Re-add contact works from a distance again: send them a request, or paste each other's links.
- Vault: on phones that can afford it, the vault password now uses more memory, up to 1 GiB, and an existing vault is upgraded on its next unlock, so the first unlock after the update takes longer.
- Vault: the app lock now also locks a vault whose unlock was still running, and the vault shows as locked as soon as it is asked to lock.
- Received images: a photo, sticker, profile picture or link preview image from a contact, and a picture in a group or channel post, is now checked before it is decoded. It must be a JPEG, a PNG that is not interlaced, a GIF of at most 1000 frames or a WebP file that is not animated, the formats Zerion sends, of at most 16384 pixels a side and 40 million pixels in all (8 million for a progressive or multi-scan JPEG), and at most 10 MiB as a chat attachment or 16 MiB in a post. Anything else, including HEIF, AVIF, BMP and camera raw files, is not decoded: a chat photo shows as an image that cannot be displayed, a profile picture falls back to the generated one, and a link preview or post appears without its picture. Save and Open in another app apply the same format and dimension check. The video player now plays only MP4 and WebM or Matroska video, shows no cover art or subtitles, and no longer decodes a still image sent as a video. Before, chat photos, stickers, profile pictures and link preview images were handed to the decoder as they arrived.
- Connection keys: the key two contacts share is now renewed before messages are sent, not beside them. Between two phones on 3.0.19 or later, when a renewal is due (ten minutes or more after the last one) a new connection sends nothing but the renewal until the new key is in place; if the greeting or the renewal takes longer than 30 seconds each, the connection is closed and tried again after the usual waiting time. A connection that stays open renews the key every hour and is closed if it cannot. For every contact that has greeted before, a phone that is not paused sends no message on a connection before the other phone's greeting has arrived on it. Before, the renewal was attempted at the start of a connection and messages flowed whether or not it completed, so someone who had copied the key and could hold back traffic could keep the copy valid while the two phones kept talking. Now, when a renewal is due, a phone sends only after its own key has been renewed: holding back the renewal holds back the messages, and letting it through makes the copy useless. The first message on a new connection can take a few seconds longer when a renewal is due. With a contact on 3.0.18 or earlier, messages flow as soon as its greeting has arrived, whether or not the renewal completes; the phone that offers renewals (which of the two that is follows from their identities) now also offers one every hour on a connection that stays open.
- Groups: the disappearing timer of a group now reaches members also when the owner's own notice of it does not arrive. Members pass the owner's signed setting on to each other, with their next message and to a member whose messages show it has not got it; a setting received this way counts from then on. When the owner's own notice arrives, also late, it covers the messages a phone already holds from the moment the owner chose the timer; on a phone updated from 3.0.18 this happens once for the timer already set, so messages that arrived before that timer reached the phone disappear after the update. Before, a member who missed that one notice kept its own messages, and those of other members who missed it, without a timer. Members on 3.0.18 or older take the setting from the owner only. Details in [docs/wire/GROUPTR_WIRE_PROTOCOL.md](docs/wire/GROUPTR_WIRE_PROTOCOL.md).
- Backups: after a backup or a transfer to another phone no new renewal of the connection keys starts for 24 hours, no longer seven days (a renewal that was already under way may still complete), so restore the backup within a day. A backup restored later may not reach some contacts until you add each other again; chats and history are kept.
- Files from other people: a received file that Android would install or import is no longer handed to another app. This covers app packages (APK and its variants), program files, certificates and key stores (X.509, PKCS #7, PKCS #12, PEM), Wi-Fi configuration files and every type under `vnd.android`, and also any file whose content is an Android app package, an Android program file or a native program, whatever type it claims. Such a file shows the message that it cannot be opened. A web bundle is shown as plain text. Before, a channel attachment declared as an app package was passed to the system installer after the usual warning.
- Sending text files: a file sent as plain text in a chat, group or channel, or added to the vault as text, must now read as text: no control characters other than tab, line breaks and form feed, and not a PDF, PostScript, RTF, XML, SVG or HTML document. Before, a one-to-one chat sent any file without a zero byte unchanged when it was labelled as text, so a PDF without binary data kept its author and other properties.
- Links: the question that is asked before a link opens now spells a host name with letters from another alphabet in its plain form (starting with `xn--`), as the link preview already did, so a lookalike address is visible before it is opened.
- Received images are decoded outside the app: after that check, a JPEG, PNG or WebP from someone else, and a GIF shown as a still, is handed to a small service that runs in an isolated process of its own, without permissions, network access or access to the app's files, keys or messages. It decodes the picture with Android's decoder and gives back only the pixels, at most 4096 a side, and the app accepts them only in the size it asked for. This covers chat photos, stickers, profile pictures, link preview images, group and channel pictures, the full-screen view, Save, Open in another app and the page images of a cleaned PDF. A flaw in one of Android's image decoders can therefore no longer be used to read or change what the app holds. If the service stops while decoding a picture, the picture is tried once more, alone in a new service process; if that stops too, the picture shows as an image that cannot be displayed and is not tried again until the app is restarted. A picture that is saved or opened in another app is decoded alone in a service process of its own, and the service is released after 10 seconds without work. A JPEG with more than 100 scans is refused, so a small file cannot keep the decoder busy for long. An animated GIF in a chat is still drawn inside the app, by a decoder written in Java. The size of a picture is now read from its own header by Zerion, not by a decoder.
- Vault: a photo, video or document added to the vault, from any of its screens, is cleaned of hidden information before it is stored: photos and videos as when they are sent in a chat, a PDF redrawn page by page as in a chat (it keeps its look but loses its text layer, links and forms), a Word, Excel or PowerPoint file without its document properties, plain text as it is. A file that cannot be cleaned, or of another type, is not added and the vault says why. Before, a file whose cleaning failed or whose type had no cleaner was stored as it was, and documents added from the Documents tab were never cleaned.
- Documentation: the security claims now say when a side's incoming messages are protected again after its message key pair is copied: once the other phone uses the new key pair the side makes after every 16 frames it sends or receives, or from the next connection, which always starts with new key pairs.
- Message keys: a phone now also makes a new message key pair after it has received 16 frames since its last one, not only after it has sent 16, so a chat where one person does most of the talking renews keys as often in both directions. If the other phone keeps using one of this phone's key pairs for more than 128 frames in a row, because this phone's newer key does not reach it or it has fallen far behind, the connection is closed and the next one starts with new keys; unacknowledged messages are sent again. The iOS app does not do either yet. Details in [docs/protocol/ZWF-MODE3FULL.md](docs/protocol/ZWF-MODE3FULL.md).
- Update check: the daily check for a newer version is now off by default, so an installation from GitHub Releases contacts GitHub only after the user turns on "Check automatically" in Settings or taps "Check for updates". An installation where the user had turned the check on or off keeps that choice; one where it was never changed now has it off.
- Stickers: tapping a sticker imported into My Stickers sends it again; it showed "Could not import sticker" and sent nothing unless a file had been attached since the chat was opened ([#44](https://github.com/zerionproject/Zerion/issues/44)). A sticker that cannot be sent now says so.
- Secret notes: opening a second note while the first one is still counting down no longer deletes it; each note stays readable for its own countdown. If the app crashes or the phone turns off while an opened note is counting down, the note is deleted when the account is next opened, once its countdown has passed, and it is never shown again.
- Chat input: the message field is wider because the spare margins around it were removed and the secret note and attachment icons moved inside the box; every icon keeps its 48 dp touch area.
- Pinned messages: long-press a message in a one-to-one chat and choose Pin to show it in a banner under the chat's title; tapping the banner scrolls to the message, and either side can pin another message or unpin. The pin is sent to the contact as a small control record, offered only to contacts on 3.0.19 or later (messaging minor version 15); with an older contact the pin stays on this phone and the app says so. View once media and secret notes cannot be pinned, and the banner names a photo, video, voice message or file without showing it. In a group the Group owner pins and unpins a post for every member on 3.0.19 or later; members see the banner and can hide it. Channels already had pinned posts. Deleting a pinned message or post removes the pin. Details in [docs/protocol/MESSAGE-EDIT-DELETE.md](docs/protocol/MESSAGE-EDIT-DELETE.md) and [docs/wire/GROUPTR_WIRE_PROTOCOL.md](docs/wire/GROUPTR_WIRE_PROTOCOL.md).
- View once photos and videos in one-to-one chats: on the attachment screen, View once sends one photo or one video, without a caption, that the contact can open once. It opens from memory with screenshot protection, cannot be saved, forwarded or opened in another app, and is erased from the contact's phone when it is opened and from the sender's phone once it has been delivered; the sender sees when it was viewed. Offered only to contacts on 3.0.19 or later (messaging minor version 14), so not to the iOS app; it cannot stop a camera pointed at the screen or a modified app. Details in [docs/protocol/MESSAGE-EDIT-DELETE.md](docs/protocol/MESSAGE-EDIT-DELETE.md).
- Adding contacts: the Add contact screen has two choices, "Add contact" and "Scan QR". Add contact opens a screen with the same title, which takes the other person's ZerionID or invite link; once a link is pasted or scanned it asks for their name (only you see it), your name (they see it) and an optional note of up to 200 characters, and sends a contact request they can accept later; it also shows your own ZerionID with Share, Copy and Show QR. A link from an older version shows "Older link. Both of you paste each other's link, or use Scan QR." and adds the contact the old way once both phones have pasted each other's link. Scan QR shows your link code while Zerion is connected and, without a connection, the nearby pairing code for pairing over Bluetooth, with a line saying which mode is active; a scanned nearby pairing code always opens nearby pairing, so the separate Pair nearby entry is gone.
- Compatibility: Android phones on 3.0.18 or older and iOS phones can still be added by pasting each other's links on both phones or with Scan QR. They cannot send or receive invites; invites on iOS are not implemented.
- Contact requests: a new Requests screen, reached from the "Contact requests" row at the top of the Chats home and from the notification, replaces the pending contacts list. Received requests show the name the sender chose (or "No name") and the link they came through; the note is shown only when the request is opened, with Accept, Decline (with Undo for 5 seconds) and Block; an accepted request that its sender cancels shows "Cancelled by sender". Sent requests show Not sent yet, Delivered, Accepted, Not delivered, No answer, Cancelled or Cancelled after delivery, with Cancel request and Remove, and "Their phone is busy. Zerion will try again." when the other phone asks for more work than this phone will do. Notifications use their own "Contact requests" channel and say only "New contact request" or how many arrived. When both people add each other at the same time, the new contact appears about a minute after Accept: in the first 15 minutes after a request is delivered, the sender's phone checks about every 20 seconds whether it was accepted (for the 3 newest requests), then every 2 minutes up to 2 hours.
- Contact requests after Accept: an accepted request shows "Connecting…", or "Adding contact…" while the contact is being added, with a progress bar for the first 2 minutes after Accept or Try again, then "Accepted, connects when you are both online". A sent request whose contact is connecting or being added shows the same progress bar. When the contact is added, the row stays and turns into "Contact added" with "Open chat" until you leave the screen, and a short bar says "Contact added" with "Open chat"; the screen does not close by itself. The sender's phone shows "Your contact request was accepted" on the Contact requests channel (no name, note or link); tapping it opens the new chat behind the app lock, and nothing is shown while the Requests screen is open. A sent request that was delivered more than 3 days ago and has no answer shows "No answer yet" until it ends as "No answer" after 32 days; this is worked out on the phone and nothing is sent.
- Invite links: up to 5 labelled links that people can use to send you requests, each valid until you remove it, with Share, Copy, Show QR, its request and block counts (a count of 0 is not shown) and Remove. Your ZerionID receives requests for 30 days after you last tap Share, Copy or Show QR for it in Zerion; the Invite links screen shows until when and has "Keep receiving" to renew it. "Keep receiving" is the main button only when the ZerionID is not receiving or has less than 3 days left. "Reset ZerionID" (a red text button at the end of the ZerionID section) and the list of blocked senders (shown only when someone is blocked) are on the same screen, reachable from Settings and from Add contact. Longer explanations on this screen sit behind "More info".
- ZerionID rotation: your ZerionID changes by itself after every contact you accept through it, so the next person gets a fresh one; the Add contact and Invite links screens say so. The new ZerionID starts receiving when you first share it, the old inbox address is removed at once and its key is kept only while requests through it are still waiting, so those can still be accepted. Blocks stay with the ZerionID they were made on, as after "Reset ZerionID". Invite links do not rotate; the Invite links screen says they stay the same until you remove them, which suits support. Its "More info" for the ZerionID also says that, while it receives requests, anyone who has it can see when your phone is online. The Block confirmation names what the block covers: your ZerionID or the invite link the request came through.
- Flooded links: when an inbox sees more than 300 connections without work in 10 minutes, or its puzzle reaches a third ten-minute period in a row at the highest level (10 to 20 minutes after it first got there), Zerion pauses that link: an invite link goes offline for an hour (twice as long for every further flood within a day, at most 24 hours) and comes back by itself, the link stays valid, and the Invite links screen shows "Too many requests. Paused until <time>" with "Resume now" and "Remove"; the ZerionID is reset. A notification on the Contact requests channel names no link ("Invite link paused: too many requests. It resumes by itself." or "Too many requests, so your ZerionID was reset. Share the new one."). Requests already received stay, and a sender who was in the middle of a request when a link went offline gets up to 10 seconds to finish it. Nothing in Tor changes.
- Restore: after an account is restored from a backup, requests and invite links stay paused until you confirm that this is the only device using the account.
- Calls: an incoming call now rings and can be answered while Zerion is in the background or the screen is off, and a call that is not answered leaves a missed call notification.
- Video in calls (beta): turning on video during a voice call now also works when the person who answered asks for it. A video link that arrives early waits up to 30 seconds for the acceptance, a late or unexpected acceptance is answered with a decline so the other phone stops waiting, and video links are closed on every failure path. An unanswered video request now closes after 60 seconds on both phones, and also closes when the other person turns video off first.
- Links and names: link previews are shown only for web links, a link shows its host in plain letters before it opens, and direction and invisible characters are removed from names other people chose.
- Safety: every copy goes through the secure clipboard, a panic trigger locks or signs out only when it comes from the panic app you connected, and a security setting that cannot be saved stays as it was and says so. Video previews are made without a cache file, and a quick reply that could not be sent is reported as not sent.
- Groups created before 3.0.18 (groups with account keys): a member's post-quantum key is taken from your contact record first and from your own key, and only then from that group's own member list; a key in a member list that differs from the contact record is no longer used, and a member who is not your contact has a post-quantum key in a group only if that group's member list carries one, no longer taken from another group's member list. Joining a group and member invitations use the contact record alone.
- Channels: the owner chooses who can post: only the owner, the owner and editors (as before, the default), or the subscribers the owner trusts (subscribers marked as trusted and approved applicants). Such a subscriber can write a text post; the owner's phone checks it and publishes it signed with the channel's key, under the name the owner's phone holds for that subscriber with the label Subscriber, at most 10 posts an hour and 50 a day per subscriber and 60 an hour and 300 a day for the whole channel. The setting can be changed at any time; turning it off stops new posts and keeps the published ones. The owner can delete such a post and block its author from the member sheet. A post written while the owner is away waits on the subscriber's phone for up to 7 days, a post over the limits waits for the next hour, and a refused post stays as not sent with Retry and Delete and is reported as not accepted. Subscribers on 3.0.18 and earlier do not see subscriber posts and keep receiving the other posts; a full storage window gives up subscriber posts first.
- Bluetooth privacy: Pair Nearby now hides the phone's Bluetooth name the same way the offline mesh does, advertises at low power, and only lets in a phone that proves it scanned the code; other devices cannot take part, and after five wrong proofs the phone stops advertising for 10 seconds before it tries again. Phones on 3.0.18 or earlier still pair.
- New settings under Connections, next to the offline mesh switch: "Bluetooth name while the mesh runs" (Android unless you choose another name, also used by Pair Nearby), "Bluetooth range" (Normal, the default, or Short for fewer phones in reach; it lowers the advertising power) and "Pause Bluetooth mesh while the screen is off" (off by default; starts about 10 minutes after the screen turns off). The options are shown on Android 12 and newer, where the mesh runs.
- The offline mesh and Pair Nearby advertise with a random interval, and the mesh waits a random moment before it starts a new advertising set, so the timing links fewer of them. The advertised data is unchanged and 3.0.14 and later phones still find each other.
- Signal strength and the app's fixed Bluetooth markers remain visible to anyone nearby with a scanner; see the Bluetooth privacy notes in the mesh documentation.
- Offline mesh: the send and receive queues are bounded and the relay limits count bytes as well as frames, a stranger can no longer suppress a contact's message by replaying it, and a link keeps its slot only while it delivers messages that open on this device. Cover traffic padding and timing come from a secure random source.
- Bitcoin wallet: fee estimates are checked against a second server and the lower of the two estimates is always used; when they differ more than threefold, or the rate jumps, sending shows a warning. Server replies are parsed strictly and a malformed reply is refused.
- Monero wallet: a public node that is not an onion address is used over Tor only after you confirm it, the vetted Tor nodes are used until then, and a node on your own network works only in Direct mode.
- Monero wallet: the Monero library is updated from v0.18.5.1 to v0.18.5.4, which fixes lifetime and data race bugs in the wallet's refresh path and hardens it against misbehaving nodes, and the library's HTTP client now refuses a node reply over 128 MiB instead of reading it into memory without limit; a correct node's largest reply, a block chunk, is about 100 MiB of blocks plus their output indices, under that cap. Details in [packaging/monero-android/PROVENANCE.md](packaging/monero-android/PROVENANCE.md).
- Build: the container that builds the Monero library runs as an unprivileged user instead of root. The library it produces is unchanged.
- Build: the F-Droid recipe that the CI reference build takes from fdroiddata must now match, field for field after YAML decoding, the recipe pinned in [scripts/fdroid-recipe.yml](scripts/fdroid-recipe.yml), and only that one entry is written into the metadata the build reads, so a recipe change needs a reviewed change here before CI builds with it; before, the check looked for the word "verification" in the recipe text, which a line fold or an escape could hide.
- Backups: a backup passphrase needs at least 12 characters with at least 8 different ones, from at least two of these groups: lowercase letters, capitals, digits and symbols.
- Release verification: the release signing certificate and how to check an APK and the signed release announcement are published in [docs/RELEASE_VERIFICATION.md](docs/RELEASE_VERIFICATION.md). The protobuf runtime is updated, and the security claims for secure deletion, USB panic and client authorization now match the code.
- Tor: the built-in Tor (still 0.4.9.14) is rebuilt with Tor's onion service proof-of-work support, its defence against floods of connection requests to an onion address; the app turns it on for the inboxes of the ZerionID and invite links only, never for contact or channel addresses, so during a flood of contact requests the phone's Tor asks senders for work and serves those who did it first, before the app sees a connection; it raises the cost of a flood but does not stop a determined attacker. Built this way, the Tor program is covered by the GNU GPL, version 3, instead of Tor's usual BSD licence, because the proof-of-work code it includes (Equi-X and HashX) is under the LGPL, version 3; see [NOTICE.md](NOTICE.md). Every other Tor build option is unchanged, and the build is reproduced byte for byte in two build environments ([packaging/tor-android/PROVENANCE.md](packaging/tor-android/PROVENANCE.md)).
- Tor: Tor's guard and bridge state is kept between sessions as one value for the whole phone, encrypted with a key in the phone's keystore, and the plain copy is shredded when Tor stops; every profile uses the same guards. Tor writes to disk less while it runs. Only Tor's cache of the public directory stays on disk, so connecting stays as fast as before.
- Keystore hardening: the key behind the profile name shown before sign-in is created in the phone's secure element where it has one and only works while the phone is unlocked; the keys behind the encrypted settings and the lockout counters stay in the phone's hardware keystore. Stickers are now sealed with a key that belongs to the profile (existing stickers move over one by one when they are shown), lockout counters are stored encrypted (a plain file is used only while the keystore fails, and is moved into encrypted storage once it works again), a new decoy code uses the same key derivation strength as the account password, a damaged first-setup database is shredded once the new database works, and the security settings explain that the app lock keeps the account open until you sign out.

Known limits in this version: video in calls is beta, and asking for video while a call is reconnecting can fail. A missed call shows a notification but no entry in the chat. The call timer starts at the first connection and keeps running across reconnects. An unanswered call stops ringing after 60 seconds.

## 3.0.18 (October 2026)

- Reconnecting: contacts that stopped seeing each other some days after one of them changed address now find each other again. The phone that dials a contact no longer gives up on an announced address after three failed dials, both sides of a pair dial, a new address is announced once Tor has published it, and the old address is retired only when every contact has moved to the new one. A contact silent for 90 days no longer holds back a renewal on the side that waits for it, and the network settings list the contacts a renewal still waits for, with when they were last seen.
- Client authorization of contact addresses: a side that committed to the protected path keeps its credentials instead of clearing them after seven days, repairs the exchange with bounded retries from either side, re-keys a contact whose phone lost its keys, and rotates the authorized address after a random period.
- Private groups: every group created from now on gives each member a key of its own for that group, so members who are not your contacts can no longer recognise you across groups; contacts still can. Groups created before keep working as they are and say so, joining incognito is offered for new groups only, and contacts whose app does not support these keys yet, which includes the iOS app, cannot join new groups.
- Groups: a post can be deleted for everyone by its author and answered with a reply that quotes it; members may invite their own contacts once the group owner allows it (off by default), the owner's phone staying the only signer of the membership; own posts show waiting, sent and delivered; a deleted message, a cleared chat, a removed group and a removed contact are overwritten in the database file instead of only being marked deleted.
- Channels: posts are full-width cards and comments read like a chat; members see when a channel was last updated; comments, reactions and deletes written while the owner is away are sent when the channel can be reached; a renewed invite link revokes every approval; the channel settings screen offers the owner member management, delete for everyone and a safety code that covers the whole identity. Comment times are rounded to the hour on purpose and shown as "around 10:00".
- Messages: every message shows sending, waiting to send, sent, delivered or not sent, with Retry, Edit and Delete with Undo; a selected message, post or comment is shown by tinting its own bubble; one selection bar offers Reply, React, Copy, Delete and More in chats, groups and channels; photos and videos from groups and channels open in the app's own viewer, held in memory only and wiped on close, lock or delete; saved files get sanitized names.
- Appearance and accessibility: new theme foundation (surface roles, no wallpaper colour leaks, readable dialog buttons), home lists with a connection chip, texts and layouts that survive the largest system font, section headings for TalkBack, status changes announced once, every message readable as one item with Reply, React, Copy and Delete as TalkBack actions, 48 dp touch targets.
- Tor: updated to 0.4.9.14, built from the signed upstream source with OpenSSL 3.5.9. The obfs4, snowflake and meek transports (lyrebird 0.9.0) are now built from the signed upstream tag in this repository instead of taken from a third-party jar. The built-in bridge lists were refreshed from the Tor Project's published lines and dead bridges removed; snowflake and meek are used only after the user agrees, remembered per country; an installation that uses the built-in bridges can fetch a newer list over Tor from this repository, accepted only with a valid signature by a pinned key, a higher sequence number and before its expiry; bridge health is stored encrypted. The "Unstable, switch to bridges" warning appears only while onion lookups go unanswered, and reconnect attempts are calmer.
- Wallet: the Monero library is rebuilt on OpenSSL 3.5.9, the same release as Tor.
- Links: the zerion link scheme is matched in any letter case.
- Exit: opening Zerion again after Exit no longer crashes when the system kept the old process alive in the background, as some power managers do ([#43](https://github.com/zerionproject/Zerion/issues/43)).
- Languages: every new and changed text is translated in all 46 supported languages.

## 3.0.17 (October 2026)

- Network status: the status no longer goes back to "Publishing" every time the screen is opened or after Tor is restarted. The publishing state is now tracked for the whole app instead of per screen.
- Update check: an installation from GitHub Releases can check once a day over Tor whether a newer version exists, and shows a notice with a link to the release page. The notice is only shown if the announcement is signed with the same key as the installed app; anything else is ignored. The check can be turned off or run by hand in Settings. Installations from app stores are not checked, and the notice reminds users never to uninstall Zerion to update, because uninstalling deletes the account.
- Disappearing messages: besides the fixed choices, any time from one minute to one year can be set, for chats, private groups and the default timer.
- Languages: every text is now translated in all supported languages, including the Tor and network screens, zVault, the wallet, calls and the Hardened Mode messages, which were partly English before. Choosing "System default" after another language now switches back without restarting the app. The word typed to confirm account deletion stays "DELETE" in every language.
- Replies: the quoted message shows its author and up to four lines in its own tinted box, is no longer squeezed by a short reply, and tapping it scrolls to the original message.
- Notifications: a new, clearer status bar icon. When the Calculator, Notes or Weather icon is chosen, the app now explains that notifications appear under that name with a bell icon.
- zVault: a wrong vault password now shows an error message.

## 3.0.16 (October 2026)

- Decoy launcher: with the Calculator, Notes or Weather app icon chosen, the correct code no longer crashes the app, and the calculator's bottom row (0, decimal point and equals) is no longer covered by the navigation bar ([#39](https://github.com/zerionproject/Zerion/issues/39)).
- zVault settings open again; the app closed every time they were chosen from the menu ([#40](https://github.com/zerionproject/Zerion/issues/40)).
- Password dialogs: a very long password no longer pushes the OK and Cancel buttons out of the dialog, and Enter confirms the account password instead of starting a new line. The wipe password, decoy code and vault document password dialogs keep their buttons above the keyboard on small screens ([#41](https://github.com/zerionproject/Zerion/issues/41)).
- Otherwise identical to 3.0.15.

## 3.0.15 (October 2026)

- Security and reliability fixes from the October 2026 internal assessment of the Android app, covering calls, attachments, channels, groups, the offline mesh, the network layer, account protection and the build. The issues rated High and the main Medium ones are listed under the limitations of 3.0.14 in [SECURITY.md](SECURITY.md).
- Contact keys renew: between two devices that both run 3.0.15, the long-term key a pair of contacts shares is refreshed at the start of a connection (at most once every ten minutes), so a copy taken from a phone or a backup stops working after the next renewal. With 3.0.14 and with iOS the key does not renew until the contacts pair again.
- Calls: a call whose connection is lost now ends on both phones about 90 seconds after the last audio arrived, while a shorter network stall still recovers. A call placed or answered while the previous call is still ending is no longer lost. The call screen honours the screen lock and the app lock, and the ongoing-call notification opens the call.
- Attachments: photos, videos and audio shared in groups and channels are stripped of location and other hidden metadata, as in one-to-one chats. A text sent while a large video is still transferring is no longer held behind it, and parts of an attachment that are still queued are no longer sent twice.
- Network status: a wrong device clock reported by Tor is now shown instead of a status that stays on "Connecting" or "Publishing", and on phones whose connection keeps dropping (for example while a VPN reconnects) the address no longer stays on "Publishing".
- Reconnecting: when a contact's phone loses its connection for a while, the two phones now find each other again within a minute or two. Before, the phone that stayed online kept using the contact's stale onion address information and Tor waited about fifteen minutes before asking for the current one, so the contact looked offline for that long unless Tor was restarted.
- Channels: approval of new subscribers works, editors can post text, a deleted channel tells its subscribers for 14 days, and rotating the invite link no longer makes the owner's own earlier posts unreadable.
- Contacts introduced to you are stored as post-quantum, safety numbers of version 2 cover both identity keys, and contacts added by link are no longer marked as verified without a comparison.
- Build: Tor uses OpenSSL 3.5.8. The Monero library is built in a fixed, minimal environment, so the same recipe produces the same bytes whatever environment starts it, and the F-Droid recipe keeps Gradle dependency verification on. Opening the project in Android Studio no longer fails with a missing lock state for the configuration copies the IDE resolves.
- App texts: unused Mailbox and crash-report texts removed and the security wording of several screens corrected; translations of corrected texts other than Dutch fall back to English until they are translated again.

## 3.0.14 (September 2026)

- Account creation on a fresh installation no longer fails with "Setup Failed" on devices whose Android key store throws when the app's key is looked up before it exists. Since 3.0.12 the app refused to generate a new key store key whenever the lookup failed, to protect the stored keys of existing profiles; before the first account there is nothing to protect, so the first account now generates a key regardless, and the guard applies from then on. Google Play's review device is one such device; 3.0.12 and 3.0.13 could not be set up there. The Setup Failed dialog now names the cause.
- Reproducible builds: the Argon2 library compiled by the app build no longer carries its build directory in debug sections, so the same source builds the same bytes in F-Droid's build layout as anywhere else. 3.0.13 reproduced only from the directory it was built in (see [SECURITY.md](SECURITY.md)).
- Functionally identical to 3.0.13 otherwise; documentation wording updated.

## 3.0.13 (September 2026)

- Tor updated to 0.4.9.13, the security release of 23 September 2026. The executable is now built from the signed upstream release source in this repository (`packaging/tor-android`), with its libraries at pinned commits, and is verified against a recorded hash by the build before packaging and by the app before every start; the same build reproduces the previously published 0.4.9.12 executables byte for byte.
- The Tor process is run by an in-tree wrapper: the generated configuration isolates the SOCKS listener and enables connection padding from the start, a Tor process that does not stop is killed within a bound, a start that fails or is interrupted leaves no process behind, and the address-published state shown on the network screen comes from Tor itself.
- Client authorization: credentials are re-installed after every network change. Tor discards them whenever its configuration changes, which after the first connectivity change following a Tor start left every locked-in contact unreachable until Tor was restarted; the protected path never fell back to the open address, it was unavailable. Fixed.
- Documentation and provenance: NOTICE.md records the wrapper's derivation and the Tor build; the known limitations of 3.0.12 are recorded in [SECURITY.md](SECURITY.md).
## 3.0.12 (September 2026)

- Security fixes from the September 2026 internal assessments (see the known limitations of 3.0.11 in [SECURITY.md](SECURITY.md)): call key derivation and video nonce handling, post-quantum authentication at pairing, post-quantum nearby pairing, onion rotation republishing, dependency verification during the release build, and the remaining findings recorded there.
- Client authorization of your contact address: each pair of contacts whose apps support it moves to a second onion address whose descriptor can only be read by the holder of a key generated for that one contact. Someone who learns the address without the key cannot read it and cannot connect. Once both sides have proven the new path, that address becomes the only way they reach each other, and removing a contact withdraws their key and changes the address for everyone else.
- Calls: fixed a call that could keep ringing on one phone after the other had given up, a self view that appeared rotated, a video call that reported a camera error when the video link was simply lost, and a first video attempt that could fail with a broken connection. Video setup now has a full minute before it gives up.
- Network status now shows what Tor is actually doing, building circuits or publishing your address, offers a Restart Tor button, and reports how many contacts have client authorization locked in.
- Documentation cleanup: current architecture only in the README, release history moved here, provenance recorded in NOTICE.md, release metadata in a single manifest, documentation consistency checks. The call audio format, the pairing authentication and the new address protection are described as the code implements them.

## 3.0.11 (September 2026)

- Fixes a bug where the app could stay offline to contacts after a spell without signal, such as in an elevator or a garage, until it was force closed. The app now notices when a link that stayed attached stops or resumes passing traffic, and it restarts Tor's network when Tor is stuck reconnecting, so contacts can reach you again without a restart.
- Upgrades Tor to 0.4.9.12.
- Builds the Monero wallet library from a clean tree on every release and records the resulting hashes, so the F-Droid build can be verified against the published APK.

## 3.0.10 (September 2026)

- Fixes a bug where updating to 3.0.9 could show a database error after signing in. No data was affected: the failed database upgrade rolled back and left the account, contacts, messages and wallets intact, and installing this version opens the account normally. The database upgrade step is now idempotent and self-healing, and is covered by a regression test that runs it against a real database. Functionally identical to 3.0.9 otherwise.

## 3.0.9 (September 2026)

- Rotating pairing links: the handshake key behind your pairing link rotates after every successful contact addition, so a previously shared link stops identifying you once its pairings resolve; pairings in flight are bound to the key they started with and are unaffected (database schema v67).
- Fixes from an independent focused security review of the Bitcoin wallet and the dormant PayJoin component (researcher: ZeroTrace): strict custom-node classification so a hostile hostname can never bypass the selected Tor routing policy (enforced again at the socket boundary), pending payments keep their coins reserved through every reconciliation state with node responses verified against the requested transaction, and the reviewed fee now always equals the exact final fee.
- Devices with unreliable secure-element firmware no longer crash-loop until a phone restart: key store failures are handled safely everywhere, transient failures no longer discard keys, and a clear explanation screen appears when the device key store is unresponsive; there is never a fallback to unencrypted storage.
- Separate text size settings for chats and for the rest of the interface.
- Fixes: password dots invisible on the light-theme sign-in screen, a stale "Invalid password" message after successfully unlocking the vault.

## 3.0.8 (September 2026)

- Idle data usage cut by five to ten times: the cover traffic now has an active and an idle rate (slower still on mobile data, with a setting to control it); within each rate real and cover frames remain indistinguishable and sending never bursts.
- Storage cleanup: cancelled or failed media uploads no longer leave data behind, orphaned attachment chunks are reclaimed automatically including space leaked by older versions, channel attachment caches are garbage collected, and the encrypted database compacts itself when deletions free significant space.
- Vault: auto-lock timeout and hide-content settings now work as configured, photos taken into the vault save at full resolution, and unsaved note changes warn before closing.
- A full-app hygiene pass: fixed a crash in chats containing voice-call history, password dialogs keep your input when validation fails, notification switches reflect the real system state, dates on older group messages, tappable links in groups and channels, smoother media scrolling in group chats, and more texts moved to translations.

## 3.0.7 (September 2026)

- Security hardening from an external vulnerability report and an independent focused review of the 3.0.6 transport: stream replay protection across restarts, stricter post-quantum ratchet state handling, connection session caps with real socket teardown, onion address rotation wired end to end, and retained ratchet keys stripped from persisted state.
- Vault: asks for the password every time you enter it, locks when you leave, and gains a chat button to return to the messaging environment.
- Fixed a crash after account creation on devices whose key store accepts generating a hardware-backed key but fails when using it; candidate keys are now probed before selection.
- Fixed from an independent focused security review of the Monero wallet native integration (researcher: ZeroTrace): a native wallet lifetime race between refresh interruption and wallet destruction, plus documentation claims rescoped to what the code enforces.
- Fixes: sign-in visibility in light theme, language changes apply immediately, password change no longer succeeds with a mismatched confirmation.

## 3.0.6 (September 2026)

- F-Droid buildability: all native libraries now build from pinned upstream source with published per-ABI hashes; no prebuilt binaries remain in the repository.
- 16 KB memory-page compatibility: updated the one bundled library that was not aligned for Android devices with 16 KB pages.
- No protocol change and no database upgrade.

## 3.0.4 (September 2026)

- Optional non-custodial Bitcoin and Monero wallets inside the vault: self-custodial (the seed is generated on-device and never leaves it), each with its own Argon2id-derived password. The Monero wallet runs view-only at rest so the spend key is in memory only while a payment is signed. All wallet traffic (Electrum, Monero nodes, broadcast, price) goes over Tor by default with per-wallet and per-purpose stream isolation.
- The native wallet libraries (Monero `wallet2`, Argon2) are built from pinned upstream source with published per-ABI hashes and build-time verification gates.
- The wallet foundation (vault, Bitcoin and Monero wallets, and the native boundary) went through extensive internal security and code review in several separate adversarial passes, with the findings fixed.
- Play Store review fixes: a complete Light theme option, user control over background connections, and localisation updates.
- Both people still need matching versions to message; no messaging protocol or database change.

## 3.0.3 (August 2026)

- Require a typed confirmation before wiping an account, and clearer contact-trust labelling; the disappearing-messages timer refreshes correctly on returning to a conversation. No protocol change, no database upgrade.

## 3.0.1 (August 2026)

- Fixes a startup bug where the app could fail to launch and show a black screen on some installs, including from Google Play, because a new integrity self-check did not recognise Google Play's app-signing key.
- Optional hardened mode is now off by default; it is still available under Security settings.
- No protocol change and no database upgrade from 3.0.0.

## 3.0.0 (August 2026)

- A network protocol written in-house: fixed-size 4096-byte frames, paced cover traffic so active use is indistinguishable from idle within a live connection, and per-message hybrid post-quantum encryption, all over Tor with no Zerion servers.
- Keeps the post-quantum ratchet and the delivery database from the 2.x line.
- Two new transports: a Bluetooth offline mesh for messaging with no internet at all (one-to-one and group, with replies and photos) and an opt-in embedded I2P transport; Tor stays mandatory and always on for online messaging.
- Both people need this version to message each other.

## 2.0.7 (July 2026)

- Fixes a display bug where the decoy calculator keypad could render blank in portrait on some narrower screens (reported on HyperOS and GrapheneOS). No protocol change, no database upgrade, signing key unchanged.

## 2.0.6 (July 2026)

- Adding a contact is reliable again; connections recover immediately after a drop so contacts stay online more consistently; voice memos deliver reliably.
- Contact pairing and the per-message post-quantum encryption were reviewed and hardened, and the release went through a full internal code and security review.
- Both people need this version to add each other. No database upgrade.

## 2.0.5 (June 2026)

- Account backup reliability fix: backups work reliably across devices and report a clear error if anything goes wrong. No change to backup encryption, no protocol change, no database upgrade.

## 2.0.4 (June 2026)

- Back up your whole account to an encrypted file, or move it straight to a new phone over Tor.
- Faster, more reliable connections through bridges in censored regions; quicker message delivery; smoother group chats with Enter-to-send and a tappable key fingerprint; app lock can hold for up to 24 hours in the background.
- Full internal code and security review. No protocol change, no database upgrade.

## 2.0.3 (June 2026)

- Voice calls work cleanly in both directions and are on by default; opt-in video-calling beta.
- Mute a channel, search and sort the vault, save channel post drafts, set a default disappearing timer, see pending group invites.
- Lower battery and memory use, faster start, and a set of crash fixes. No protocol change, no database upgrade.

## 2.0.2 (June 2026)

- Channels now raise system notifications for new posts (subscribers) and new comments (owners), with a global Channels toggle and per-channel mute.
- Group chats are resilient under concurrent admin actions: adding a member while another is removed, or messaging during a membership change, no longer splits the member list; invitees see the current roster immediately on accept.
- At-rest encrypted preferences moved to an in-tree implementation, replacing the deprecated AndroidX `security-crypto` library (one-time settings reset on upgrade; conversations, channels, groups, contacts, and vault are unaffected).
- Exit from the foreground notification now reliably reopens cleanly on next launch.

## 2.0.1 (June 2026)

- Build hygiene for F-Droid main-repo distribution: the PhotoView library moved from a vendored binary to source, keeping a single signing key across Play Store, GitHub, and F-Droid so users can switch channels without reinstalling.

## 2.0.0 (June 2026)

- Channels: a publisher-to-subscriber broadcast layer with optional discussion threads; public or private, owner-approved subscribers, reactions, pinned posts, attachments, and editor delegations (post without sharing your identity key); subscribers never see one another.
- Hardened mode (opt-in): refuse to start on tampered devices, under a debugger/root/hooking framework, or when USB debugging/file transfer is enabled.
- Cache wipe on sign-out, 60-second clipboard auto-clear, plain-language copy throughout.

## 1.7.0 (May 2026)

- Mode 3-Full per-message hybrid ratchet is now the default. Every frame in both directions carries a fresh ML-KEM-768 encapsulation; the decapsulated secret is mixed into the body AEAD key on every message.
- Group chat unread counter; multi-profile end-to-end polish; internationalisation of vault confirmation keywords; accessibility labels on call controls; streamlined per-chat actions menu.
- Build-time zero-logging guarantee: a Gradle gate fails the build if any production source file references a logger.
- Wire-compatible with 1.6.x peers (Mode 2 fallback when the peer is older); no vault or DB schema changes; signing key unchanged.

## 1.6.2 (May 2026)

- Native group-invite protocol replaces the legacy carrier on the 1:1 channel.
- Kick reliability fix: invitee epoch desync that silently dropped `MEMBER_REMOVED` is closed; removed users are purged from the local device atomically.
- Non-Tor transports removed: Bluetooth, Wi-Fi LAN, removable-drive sync, and dev-reporting subsystems.
- All `SharedPreferences` routed through Android Keystore-backed encrypted preferences.
- Hybrid Ed25519 + ML-DSA-65 signatures extended to private-group and invitation contexts.
- Carry-forward downgrade-lock token reconstruction fix; vault, biometric and lock-screen findings of an internal audit patched.
- Supply-chain: `junit-bom-5.11.4` pinned by SHA-256 in dependency-verification metadata.

## 1.6.0 (May 2026)

- PCS Mode 3 post-quantum ratchet completes end-to-end; ML-KEM-768 mixed into the root key every 25 messages or 24 hours, both directions.
- Hybrid Ed25519 + ML-DSA-65 signatures on every group record (3,373 bytes).
- Vault password KDF migrated from a PBKDF2 placeholder to Argon2id.
- DB schema v62 to v63 (nullable ML-DSA columns, lazy backfill on first login).
- Critical, high and medium findings of an internal code audit patched before tag.

## 1.5.0 (May 2026)

- B.3 hybrid pairing: ML-KEM-768 + X25519 contact handshake with downgrade defence.
- B.4 onion rotation: Tor v3 onion address rotates every 5 to 14 days to defeat long-term linkability.
- Hybrid identity proofs at first pair (Ed25519 + ML-DSA-65); per-direction PQ epoch infrastructure.

## 1.2.0

- Security hardening: video call camera deadlock fixed, password handling uses `char[]` throughout.
- Registration Lock: protect your account with PIN or password (PBKDF2-SHA256).
- App icon changer: disguise as Calculator, Notes, or Weather; chat text size chooser and bubble colour picker; navigation bar size setting; invite friends sharing.
- Edge-to-edge rendering for Android 15 (SDK 35); link previews default off (fetched via Tor when enabled).
- Removed QR/zxing dependency, Bluetooth and Wi-Fi hotspot dead code; cleaned 2,100+ dead localised strings across 47 languages.

## 1.0.10

- Now available on Google Play Store; fixed local self-view rotation during video calls; fixed camera switch race condition; vault UI refinements.

## 1.0.9

- UI/UX improvements: rich empty states with icons across all list screens; conversation empty state with contextual action prompt; zVault branding.

## 1.0.8

- Auto-wipe on max login attempts is now immediate; forensic tool detection (Cellebrite, GrayKey, ADB, USB data transfer) triggers immediate app lock; message clipboard auto-clears after 60 seconds; emergency file corruption overwrites entire file contents with secure flush.

## 1.0.7

- Fixed self-view rotation during video calls; fixed spurious "Camera error" toast after hanging up; fixed call timer overlapping the local video preview.

## 1.0.6

- Video call security: AES-GCM authentication failure detection; video encoder drain thread clean shutdown; video decoder consecutive failure tracking; `FLAG_SECURE` on the auth screen; `char[]` password handling in the strength estimator.

## 1.0.5

- Video call quality: 640x480 at 24 fps / 600 kbps, H.264 Main Profile Level 3.1; remote video rotation metadata; camera switch async callback; call UX indicators; VoiceCallService key zeroing and threading fixes.

## 1.0.4

- P2P encrypted video calls over Tor; crypto-protocol hardening (8 vulnerabilities fixed); voice signal ephemeral cleanup; zero-log CI enforcement.

# Editing and deleting sent messages

From 3.0.18 the sender of a private (one-to-one) message can edit its text
and can delete it for everyone. Both are ordinary messages of the private
messaging client (`org.zerionproject.app.messaging`, major version 0) in the
conversation's contact group, so they travel over the same encrypted
connection as the text they change. This document is the reference for
every implementation.

## Records

| Type | Body | Meaning |
| --- | --- | --- |
| 12 | `[12, target, text]` or `[12, target, text, salt]` | Replace the text of `target` with `text` |
| 13 | `[13, target]` | Remove `target` |

`target` is the 32-byte id under which the sender stored the message. A
message that reached the receiver over the mesh is stored there under a
different id, and neither record applies to it (see the mesh rule below).
`text` is 1 to `MAX_PRIVATE_MESSAGE_TEXT_LENGTH` bytes and may not start
with a form the app gives a special meaning (`SECRET:`, `VOICE_CALL:`,
`[VOICE:`, `[VMP:` or the legacy call signal prefix). `salt` is 32 random
bytes, sent only to a contact that announces minor version 12 or later (see
"Salted bodies").

## Receiving

- Only the author can change a message: the target must be a message the
  contact sent. A record that names a message this device sent is ignored.
- An edit applies to a text message (type 0 with text, or the one-element
  legacy text) whose text is not a secret note, voice memo or call event. Its
  timestamp must be later than the message's and at most 24 hours after it.
  The newest edit wins; the text shown, quoted and copied is the edited text,
  the message and every quote of it are marked as edited, and the app keeps
  no earlier version (see "What an edit leaves on this device").
- A delete applies to a text, attachment, voice memo part or link preview
  message. The message is removed with its attachments, its reactions and
  any edit still waiting for it, without a placeholder and without a
  notification. If it was unread, it is also taken off the notification
  count, and a quote of it shows that the original is unavailable. Images
  of a withdrawn message that already arrived are removed with it.
- An edit or delete that arrives before its message is kept and applied when
  the message arrives, also when the message is still waiting for its
  attachments. A waiting edit is kept for seven days, or for the
  conversation's disappearing-message time if that is shorter; a delete
  record is kept for seven days. At most 500 waiting records of each kind
  are kept per contact. A message that arrives after its delete is rejected
  and never shown, and a message still waiting for its attachments when its
  delete arrives is withdrawn. A delete also drops the edits waiting for its
  message, and an edit that arrives after the delete of its message is
  rejected.

## Sending

- Both are offered only when the contact announces minor version 9 or later
  of the messaging client. Earlier versions, 3.0.17 and before, and the iOS
  client ignore types 12 and 13 without a trace. Edit is then not offered,
  and delete for everyone removes the message on this device only and says so.
- An edit is possible for 24 hours after sending; delete for everyone has no
  time limit. A message that never left the device is simply withdrawn and
  deleting it sends nothing, unless an edit of it already left, in which case
  the delete is sent too.
- An edit the contact has not received yet is removed with its message,
  whether the message is deleted on this device or disappears when its timer
  runs out. Clearing the chat keeps the deletes for everyone the contact has
  not received yet.
- Each edit of a message carries a later time than the one before, and a
  newer edit replaces an older one that has not been delivered yet.
- The edited message itself is still sent until the contact acknowledges it,
  with its original body, since the contact applies an edit only to a message
  it holds. Messages are offered in the order of their time, so the message
  goes before its edit; an edit that reaches the contact first waits there for
  the message.
- Messages sent over the mesh can be neither edited nor deleted for everyone:
  copies flooded through other phones cannot be recalled. Deleting one
  removes it on this device and stops it from being sent again. The edit and
  delete records themselves travel over Tor.
- To a contact that announces minor version 12 or later, text messages and
  edits carry a random salt (next section).

## Salted bodies

From 3.0.18, minor version 12 of the messaging client
(`MessagingManager.SALTED_TEXT_MIN_VERSION`) adds a random salt to the body
of every type 0 message (text, attachments or both, secret notes included)
and every edit that this device sends to a contact that announces 12 or
later:

| Type | Body |
| --- | --- |
| 0 | `[0, text, attachments, timer, replyTo, salt]` |
| 12 | `[12, target, text, salt]` |

`text` may be null when the message has attachments, and `timer` and
`replyTo` keep their meaning and may be null, as before. `salt` is 32 bytes
that the sending device draws for each message from the app's
`SecureRandom` (`CryptoComponent.getSecureRandom`). A contact that announces
11 or less, which includes every release up to 3.0.17, the development
builds of 3.0.18 made before version 12 and the iOS client, gets the bodies
without the salt, byte for byte as before, since its validator refuses the
longer lists. A receiver from version 12 accepts both forms from any
contact; a salt of any other length, a salt that is not raw bytes, or
anything after the salt makes the record invalid. The salt makes a text
message 35 bytes longer (34 when it quotes a message, since the place of the
quote is then filled anyway) and an edit 34 bytes longer.

The salt is part of the body only. The receiver's validator does not copy
it into the metadata, and neither side stores it anywhere else, so it goes
with the body: when an edit erases the original (next sections), when the
message is deleted, and when a record is rejected and marked invalid.

The message id is a hash over the conversation, the message time and a hash
of the encoded body (`MessageFactoryImpl`), so with the salt in the body the
id is a randomised hash commitment to the text. It still binds the message
to its exact content, and acknowledgements, the recognition of a copy
offered again and the references of reactions, edits, deletes and replies
use it exactly as before. Once the body and its salt are gone, a guessed
text can no longer be checked against the id, since the guess would have to
include the 256-bit salt. Nothing new is computed: the salt is hashed with
the rest of the body by the hash that has always made the id, and the
encoding of the body is unchanged apart from the extra element.

The salt is not added to: messages and edits exchanged with a contact that
announces 11 or less; messages stored before this version, sent or received;
the one-element legacy text, which carries voice memos and the texts sent to
a contact that announces no image support; link preview records, which
cannot be edited; mesh copies, which the receiver stores as a legacy
text under an id of its own; and photo messages sent over the mesh, which
carry no text. The id of each of these still confirms a guessed text where
it has one, as described under "What remains" below.

Whether a message or edit is salted is decided from the contact's announced
version in the transaction that creates it. A contact that later goes back
to a release below version 12 refuses salted records that were already
queued for it; this phone counts them as delivered when the contact's
device takes them, as with every record a contact's version no longer
accepts, so after such a downgrade the contact may miss them. Release builds
cannot be installed over a newer version, so this concerns development
builds.

## What a deletion leaves on this device

From 3.0.18 this holds for a message deleted on this device, deleted for
everyone by either side, withdrawn before it was delivered or removed by its
disappearing timer, and for a cleared chat.

- The message row is deleted from the database, not emptied, together with
  its attachments, its reactions and any edit of it still waiting to be sent
  or applied. A reaction removal the contact has not acknowledged yet is the
  one exception: it is kept until the contact acknowledges it, so the contact
  still learns that the reaction is gone, and is deleted after that.
- A message that is withdrawn because its delete arrived while it was still
  waiting for its attachments keeps one row with no body and no metadata,
  only its id, conversation, time and length, marked invalid. The sync layer
  needs that row to acknowledge the message and never fetch it again; without
  it a copy offered later would be downloaded and shown once the delete
  record has expired.
- Clearing a chat deletes every record of the conversation: messages,
  attachments, reactions, edits, typing indicators and call signals,
  including those not sent yet, which are then never sent. It keeps the
  group records that travel in the same pairwise connection (group posts,
  membership changes, group settings, the invitations and answers this
  device sent, and a received invitation that can still be accepted or
  declined, whose card stays in the chat; an invitation that was answered,
  revoked or ran out is deleted with the chat), so a group record this
  device sent stays until the contact has it, also one that 3.0.17 or
  earlier stored without its type. It also keeps the deletes for everyone
  the contact has not received yet, the delete records received from the
  contact, which hold only the id of the deleted message and stop it from
  coming back, reaction removals the contact has not acknowledged, and
  messages that arrived but have not been checked yet.
- The database is SQLCipher with `secure_delete` on, so the space a deletion
  frees is overwritten with zeros. The database runs in WAL mode, where the
  previous version of every changed page stays in the database file until a
  checkpoint and in the log until the log is reset. Once the deleting
  transaction has committed, the app therefore runs a truncating WAL
  checkpoint on the database executor while no transaction is open: the
  zeroed pages replace the old ones in the database file and the log is
  emptied. The checkpoint runs one second after the deletion, or up to ten
  seconds after it when a checkpoint ran in the ten seconds before (a clock
  set back does not stretch this), once for a burst of deletions. It is
  retried every 30 seconds if it cannot complete, and runs again when the
  database is opened and closed, so a deletion made just before the app
  stopped is purged at the next start. While the phone is in deep sleep the
  checkpoint waits until the app's scheduler next wakes the phone, which
  Android can postpone. Replacing or removing key material (transport keys,
  ratchet and root key state, handshake and signing key pairs) requests the
  same checkpoint, so earlier keys do not stay in the log either.
- On the desktop database (HyperSQL) the same step is a `CHECKPOINT`. It
  writes the tables anew without the deleted rows and empties the log; the
  database files are encrypted with the database key. Message bodies are
  stored as large objects in a separate file, and HyperSQL has no
  `secure_delete`: nothing guarantees that the space a deleted body used in
  that file is overwritten, so the encrypted body can stay there until
  HyperSQL reuses the space. Files that HyperSQL replaces are left to the
  file system in the same way.
- The app also drops the deleted messages from its memory caches, also when
  their chat is not open: the text, quotes, edits, link previews and
  reactions an open chat holds, the voice memo assemblies of that chat,
  video thumbnails and decoded images.

What remains:

- Deleting for me does not touch the contact's copy.
- A message withdrawn while it waited for its attachments keeps its id,
  conversation, time and length (above), and the delete records kept for
  seven days name the id of the message they deleted. For a message without
  a salt (see "Salted bodies") that id confirms a guessed text when the
  message's time is known or can be guessed, in the way described for edits
  below; for a salted message it does not.
- A backup exported before the deletion still holds the message.
- Copies already flooded over the mesh stay on the phones that carry them;
  this device cannot recall them.
- The flash controller and the file system may keep earlier copies of the
  database file below what the app can overwrite (wear levelling, and
  copy-on-write file systems such as F2FS that write changed blocks to new
  places). They are encrypted with the database key.
- Memory the app used for the message is reused by the runtime in its own
  time; the app does not control when.

## What an edit leaves on this device

From 3.0.18 an edited one-to-one message has no readable earlier version on
this device. The app has no edit history and keeps none. The earlier text
cannot be read back. For a salted message (see "Salted bodies") nothing that
stays can confirm a guess of it either; for a message without a salt,
someone who can read the database can confirm a guess of it through the
message id (see "What remains" below).

- The message is shown, quoted, copied, forwarded and searched with its
  newest text, which is kept in the message's metadata together with the time
  of that edit.
- The original body is removed from the database once nothing needs it. The
  message row stays with its id, conversation, time, metadata (the current
  text and the edit time) and, for a disappearing message, its cleanup
  deadline; the body and the length of the original are cleared, and the
  sync layer marks the message as never to be sent again. The row is needed
  so that a copy of the message offered again is not fetched and shown.
  - A message the contact sent loses its original body in the transaction
    that applies the edit once the message itself has been delivered on this
    device. A message that is still being delivered keeps its body until its
    delivery is stored, since a message whose body is gone can no longer be
    delivered: when an edit waited for the message, or the delivery of the
    message is put off and tried again later, the body is removed in a
    transaction of its own right after the delivery has been stored. A
    delivery that is put off is not queued again while the app runs; it is
    tried again at the next app start, so until then the message keeps its
    original body.
  - A message this device sent loses it in the transaction of the edit when
    the contact has already acknowledged the message. Until then the body may
    still have to be sent, so it is removed as soon as the contact's
    acknowledgement arrives.
  - Removing a body in a transaction of its own, after a delivery or an
    acknowledgement, is tried up to three times in a row. If it still fails,
    the message keeps its original and its mark (below) until the next time
    the database is opened, which finishes it.
  - A message whose original has to wait is marked in its metadata, and the
    mark is removed with the body. Each time the database is opened, the
    marked messages are looked up with an equality query on that mark and
    checked again under the same rules, so the app does not read and parse
    the metadata of every message. The database has no index on metadata
    values, so the query itself still goes through the metadata rows of each
    conversation. This covers an acknowledgement or a delivery stored just
    before the app stopped. Originals edited before the mark existed (in
    development builds of 3.0.18) are found once by reading the metadata of
    every message. In both lookups a message whose metadata cannot be read is
    skipped on its own, and the other messages of its conversation are still
    checked.
  - A message that waits to be checked or delivered and whose stored body is
    gone can never be delivered. Development builds of 3.0.18 could leave
    such a message behind. Checking and delivering go on with the other
    messages, and the message is marked invalid in the usual way: its
    metadata, which can hold the current text, is deleted, and every message
    that depends on it is marked invalid as well. Like a rejected record, it
    keeps a row with neither body nor metadata. Each start also looks up
    such messages by their missing body and resolves them the same way, so
    none is left waiting. This touches only a stored message that is still
    waiting and whose body was removed: a message whose body has not arrived
    yet is not stored at all, and a delivered message whose original was
    removed after an edit is not affected.
- A newer edit removes the records of the earlier ones in its own
  transaction: this device's earlier edit record, delivered or not (an
  earlier edit that has not left yet is then never sent), the edits received
  from the contact for the same message, and an edit still waiting for its
  message when a newer one for the same message arrives. When a message
  arrives with edits waiting for it, the newest one that applies is applied
  and the others are deleted. An edit that arrives after a newer one, or
  after its message was deleted for everyone, is rejected; like a withdrawn
  message it keeps a row with no body and no metadata, only its id,
  conversation, time and length. The record that carries the current text is
  deleted about a second after it is applied on the receiving side, and a
  minute after the contact acknowledged it on the sending side.
- Every edit asks for the truncating WAL checkpoint described above for
  deletions, so the pages that held the earlier text are overwritten in the
  database file and leave the log. Backups and account transfers are written
  with `VACUUM INTO`, which copies only what the database holds, so one made
  after the original was removed does not contain it.
- An open chat replaces the old text in the texts it loaded, in quotes, in
  the reply preview and in what its search looks through, also when the edit
  arrives while the chat is loading its messages, and the app-wide
  conversation cache drops the conversation. Notifications show only the
  number of new messages, never their text.
- Message bodies are not encrypted with keys of their own, so there is no
  key to destroy: the original is removed by deletion and overwritten as
  described above.

What remains:

- The contact's device keeps what its own app keeps. Edits are sent only to
  apps that announce minor version 9 or later; a modified app can keep the
  original.
- A message this device sent keeps its original body until the contact
  acknowledges it, however long the contact stays offline; it goes earlier
  only when the message is deleted, disappears or the chat is cleared.
- The message keeps its id, a hash over the conversation, the message time
  and a hash of the encoded body. The id has to stay: both devices store,
  acknowledge and recognise a copy offered again by it, and the contact's
  reactions, edits, deletes and replies refer to it. An edit that is
  rejected because it arrived after a newer one, or after its message was
  deleted for everyone, likewise keeps its id, time and length.
  - A salted message or edit (see "Salted bodies") lost its salt with its
    body, so its id cannot be computed from a guess: the guess would have to
    include the 256-bit salt. What stays is the time and, for a rejected
    edit, the length, which shows about how long its text was.
  - For a message or edit without a salt every other part of the body (its
    type, the attachment list, the disappearing timer and the quoted
    message) can be read from the metadata, and a legacy text message's body
    is the text alone. Someone who can read the database can therefore try
    candidate texts, for example the current text with a typo put back or a
    number changed, and confirm a right guess by computing the id again. The
    id gives nothing away beyond confirming a guess, but a short or
    predictable original is easy to guess. This holds for every message and
    edit exchanged with a contact that announces minor version 11 or less
    and for every message stored before 3.0.18 began to add the salt, and
    nothing can change it afterwards: the id is fixed when the message is
    made, both devices hold the message under it, and the salt that would
    hide the text never existed. Keeping a keyed hash of the id in its place
    would not help: the key would have to be on the device that holds the
    database, and the sync layer finds a message by its id.
- A received message whose delivery is put off keeps its original body until
  the next app start delivers it, and a body whose removal failed three times
  stays until the next time the database is opened.
- A backup exported before the original was removed still holds it.
- The flash controller and the file system may keep earlier copies of the
  database file, as for deletions above. They are encrypted with the
  database key; nothing here is a forensic guarantee.
- On the desktop database (HyperSQL) the body is a large object in a separate
  file without `secure_delete`, so its encrypted bytes can stay there until
  HyperSQL reuses the space.
- Text already shown can have been copied, forwarded or captured in a
  screenshot, and memory the app used is reused by the runtime in its own
  time.

## Groups

From 3.0.18 a group post can be deleted for everyone and replied to. Group
posts cannot be edited. The records are specified byte for byte in
[GROUPTR_WIRE_PROTOCOL.md](../wire/GROUPTR_WIRE_PROTOCOL.md) (msgType 47 and
post body type 5); this section describes the behaviour.

- A post is named by its post identity, a hash of what its signature covers,
  the same on every device, not by a message id: a member holds one copy of
  its own post per member it sent it to, and every copy has the same identity.
- Only the author can delete a post for everyone. The delete is a signed group
  record (msgType 47, a hybrid signature under its own label) that names the
  post and the author's key; a receiver applies it only when it comes from a
  member, both signature halves verify and the post's signer is the record's
  signer. It removes every copy of the post and any copy waiting for a later
  epoch, corrects the unread count and the group notification, and an open
  group screen drops the post at once, without a placeholder.
- A delete that arrives before its post waits for it, at most 500 per group
  for 7 days; a post that arrives while its delete waits, or up to 7 days after
  its delete, is dropped before it is shown.
- The sender removes every local copy at once, including copies still queued
  for members, and sends the record to each member contact that announces
  messaging minor version 10 or later. Members on an earlier version, including
  3.0.17 and before, and members whose version is not known keep the post; the
  delete dialog and a notice after deleting say how many. Members that are not
  the author's contacts hold the post only when the group owner's phone passed
  it on (from 3.0.19); the author hands the delete to the owner's phone the
  same way (msgType 60), and the owner passes it on to each such member that
  runs 3.0.19. A member that announces version 10 or later within 7 days of
  the delete is sent it then, re-signed over the same epoch and time, and
  drops the post. There is no time limit on
  deleting itself.
- Delete for me removes every local copy of the selected posts, own or not,
  and sends nothing.
- A reply quotes one post by its identity inside the signed post body (body
  type 5), so the reference cannot be changed without breaking the signature.
  Because one signed body goes to every member, a reply is sent as a reply only
  when every member contact that receives it announces minor version 10 or
  later; otherwise the same text is sent as an ordinary post, as a one-to-one
  reply loses its quote for a contact on an old version. The quoted post is
  looked up on the receiving device; when it is missing or deleted the quote
  says that the original is unavailable.

## Pinned message

From 3.0.19, minor version 15 of the messaging client
(`MessagingManager.PIN_MIN_VERSION`) lets either side of a one-to-one
conversation pin one message. The pin is a control record of the private
messaging client, like an edit or a delete:

| Type | Body | Meaning |
| --- | --- | --- |
| 15 | `[15, target]` | Pin the message `target` in this conversation |
| 15 | `[15, null]` | Unpin |

`target` is the 32-byte id under which the sender stored the message; a
message that reached the receiver over the mesh is resolved to the
receiver's copy the way edits are. Anything other than a 32-byte raw or null
in slot 1, or anything after it, makes the record invalid.

Storage: one pinned message per conversation, kept in the metadata of the
conversation's contact group under `pinnedMessageId` (the message id),
`pinnedLocal` (whether this device pinned it), `pinnedAt` (the time of the
last pin or unpin) and `pinnedRecordId` (the id of the record that set it).
No message row and no table is added. Pinning another message replaces the
entry; unpinning removes the message id and keeps the time and record id, so
the order of later records can still be decided. A pin or unpin made on this
device takes the current time, or one millisecond after the stored time if
that is later, so it always sorts after what this device has seen.

Receiving:

- A pin applies to a stored message of the conversation that is a text,
  attachment, voice memo or link preview message (types 0 and 9 or the
  one-element legacy text), not a view once message and not a secret note.
  A record that names a message that has not arrived yet (for example a
  message with attachments that is still being sent, or one that travels
  over another connection) is kept waiting like a pending edit, for 7 days
  or the conversation's disappearing timer, whichever is shorter, and is
  applied when the message arrives; the newest waiting record wins, a pin
  or unpin from the contact drops the waiting records that are older than
  it, and a pin or unpin made on this device drops them all. A record that
  names a message deleted for everyone, a message of another kind, or a
  message this device cannot pin is ignored without a trace. Either side
  may pin a message sent by either side.
- An unpin clears the pinned message, whoever pinned it. An unpin when
  nothing is pinned changes nothing.
- A received pin or unpin is applied only if it is newer than the stored
  state: its message timestamp is later than `pinnedAt`, or equal with a
  record id that is larger byte by byte (unsigned) than `pinnedRecordId`.
  An older record is dropped. Two pins made at the same moment on both
  phones therefore end with the same pin on both.
- The record itself is removed right after it is applied on the receiving
  side; the sender keeps its own record until a minute after the contact
  acknowledged it, and a newer pin or unpin from this device drops the
  earlier record that has not left yet.

Sending:

- Pin and Unpin are offered for any single message that is not a view once
  message or a secret note; choosing them for one of those shows "This
  message cannot be pinned." and sends nothing.
- The record is sent only to a contact that announces minor version 15 or
  later. To a contact on 14 or earlier (3.0.18 and before, and the iOS
  client, which announces 3) the pin is stored on this device only and the
  app says "Pinned on this device only: your contact's app is too old to
  show it on theirs." An unpin to such a contact likewise says so. A 3.0.18
  receiver that is nevertheless sent a type 15 record (a development build)
  marks it invalid and shows nothing, as for every unknown type.
- A message sent over the mesh can be pinned on this device only, since its
  id differs on the contact's device.
- Deleting the pinned message, on this device, for everyone, by its
  disappearing timer or by clearing the chat, removes the pin and the pin
  record still waiting for the contact. The contact's pin goes with the
  contact's copy when the delete reaches it.

The banner under the chat's title shows the first line of the text, or
"Photo", "Video", "Voice message" or "File" for an attachment, never the
content of a view once message or a secret note. Tapping it scrolls to the
message; the close icon unpins.

Groups: the Group owner (the creator) pins and unpins one post per group
with the signed record of msgType 59 (`GROUP_PIN`), specified byte for byte
in [GROUPTR_WIRE_PROTOCOL.md](../wire/GROUPTR_WIRE_PROTOCOL.md). Members on
3.0.18 or earlier are not sent it and keep working; the owner is told how
many members do not see the pin. Admins have no pin right: the group's
permission model gives the admin role no rights, and the pin follows that.
Members see the banner and can hide it until the next pin; the owner's
close icon unpins. Deleting the pinned post, for everyone or on this device,
removes the pin on that device. Channels keep their own pinned post in the
channel manifest, unchanged.

## View once photos and videos

From 3.0.19, minor version 14 of the messaging client
(`MessagingManager.VIEW_ONCE_MIN_VERSION`) lets the sender of a private
message mark one photo or one video as view once: the contact can open it
once, and then neither device keeps it.

| Type | Body | Meaning |
| --- | --- | --- |
| 0 | `[0, null, [[id, contentType]], timer, replyTo, salt, 1]` | A photo or video the contact can open once |
| 14 | `[14, target]` | The contact opened the view once message `target` |

Slot 6 of a type 0 body is the view once flag, the integer 1. It needs the
salt in slot 5 (32 bytes, as in "Salted bodies"), no text and exactly one
attachment whose content type starts with `image/` or `video/`; `timer` and
`replyTo` keep their meaning and may be null. Any other value in slot 6,
text, a missing or malformed salt, no attachment or more than one, another
content type, or anything after slot 6 makes the record invalid. The
attachment travels as before (a type 1 record for a photo, a type 3 manifest
with type 4 chunks for a video). `target` in type 14 is the 32-byte id of
the view once message; anything after it makes the record invalid.

Sending:

- The attachment screen offers the View once switch only when the contact
  announces minor version 14 or later, only for one photo or one video, and
  only without a caption; while it is on, the caption field is locked: it
  cannot take focus, the keyboard is closed, and text that still arrives
  from an open keyboard is dropped without turning the switch off. The
  messaging manager refuses a view once message for a contact below 14 and
  for the mesh (`ViewOnceNotSupportedException`). A contact below 14 is
  never sent the longer body: Android up to 3.0.18 refuses it (its
  validator accepts at most six elements) and the iOS client, which
  announces minor version 3, would show it as an ordinary picture. A client
  that does not implement view once must announce 13 or lower; in this code
  base a build does so by returning false from
  `FeatureFlags.shouldEnableViewOnce` (the desktop client until it
  implements this section).
- In offline mode a photo picked from the gallery or taken with the camera
  is sent over the mesh at once, without the attachment screen, as before
  3.0.19, so view once is never offered for it. Where the attachment screen
  is still used in offline mode (a recorded video), the switch is shown
  switched off with the words "View once is not sent over the mesh".
- After a view once photo is sent, the app's in-memory image cache is
  emptied, and the attachment screen never keeps its preview there.
- The sender's own copy shows a placeholder ("Photo, view once" or "Video,
  view once"), never the picture or a preview frame. Its media is kept only
  until the contact has acknowledged the message and every part of the
  attachment (the photo, or the manifest and every chunk); then the bodies
  of those parts are erased (`DatabaseComponent.eraseMessageBody`, which
  also clears the stored length), in the transaction that follows the
  acknowledgement, at the next start if that was missed, or when the
  receipt arrives, whichever comes first. The placeholder says "Viewed" once
  the receipt has arrived. The sender can delete the message for everyone,
  before or after it was opened.

Receiving:

- The message is shown as a tile without a thumbnail or preview frame
  ("Tap to view once"); it is not listed under All media, cannot be copied,
  forwarded, quoted, reacted to or saved, and a notification only counts it.
  The message depends on its photo, or on the manifest and through it on
  every chunk of its video, so it is delivered and shown only once all of
  them have arrived; there is no tile whose media is still on its way.
- The first time the user opens any view once message, a notice says that it
  can be opened once, that it is deleted as soon as it is opened, and that
  the sender trusts the viewer not to photograph or record the screen.
- Opening reads the media into memory and, in the same transaction, marks
  the message opened, erases the body of the photo or of every chunk of the
  video and queues a type 14 record for the contact. A process that is killed
  afterwards cannot show it again, and a second tap finds nothing; taps
  while it is being opened are ignored. A view once message is opened only
  when the message and every part of its attachment were received from the
  contact: one that names an attachment this device sent is refused, and
  erasure only ever touches parts on the same side as the message.
- If the chat is no longer on screen when the media has been read (the user
  left in that moment), the copy is wiped instead of shown, the tile says
  "Viewed" and a short message says that it was not shown. Media the viewer
  cannot decode is used up in the same way: the tile says "Viewed" and a
  short message says that it could not be shown.
- The media is shown in the app's own viewer or player from memory, with
  screenshot protection on whatever the setting, and without Save, Open in
  another app or Share. The copy is wiped and the viewer closes when the
  user closes it, when the app leaves the screen (another app, the home
  screen, the screen turning off), when a video has played to its end, at
  lock and sign-out. The tile then says "Viewed".
- A type 14 record applies only to a view once message this device sent and
  that is not marked opened yet; any other record is ignored. It is removed
  right after it is applied. The opener keeps its own type 14 record until a
  minute after the contact acknowledged it, also when the chat is cleared.
- Deleting on this device, deleting for everyone and the disappearing timer
  work for view once messages as for any other type 0 message, opened or
  not.

What remains on both devices: the message row with its time, the attachment
id and content type in its metadata and the opened mark; the rows of the
attachment parts with their ids, delivery state and metadata (for a video
the manifest, which holds the chunk ids, the total size and the hash of the
chunks, and the size of every chunk), so the parts are recognised and not
offered or requested again. The pixels and samples are gone from the
database. The id of each part is a hash over the conversation, its time and
its body, so someone who holds the exact photo or video file and can read
the database can confirm that this file was the one sent.

A backup or a transfer to a new phone made while a view once message is
unopened on the receiving side, or not yet acknowledged on the sending side,
carries the message without its media: the export erases those bodies in its
own copy of the database before it is encrypted, so restoring it gives
nothing that can be opened. A copy of the whole device storage made outside
the app is out of scope.

View once protects against the media staying on either device after it was
opened, against it being saved, forwarded or opened in another app from
Zerion, and against ordinary screenshots and screen recordings on an
unmodified system. It cannot stop a second camera pointed at the screen, a
modified client or operating system that keeps the media or captures the
screen, or a contact's device that never opens the message (the media stays
there, encrypted at rest, until it is opened, deleted or its timer runs
out). The sender learns that the message was opened only if the receipt
arrives.

iOS port: the iOS client announces messaging minor version 3, so Android
never sends it view once; before announcing 14 it must refuse slot 6
values other than 1, show the tile without a preview, open the media once
from memory, erase it and send type 14, and erase its own sent copy as
described above.

## Limits

Deleting for everyone removes the message from the contact's app. It cannot
remove copies the contact already made, such as screenshots, forwarded text
or a notification already read, and a modified app can ignore the records.
The 24-hour limit and the order of edits rest on the sender's own clock, so
a modified app can also edit outside that window.

In a group the same holds for every member that received the post, and in
addition members on an app earlier than minor version 10 (or whose version is
not known) keep the post, as do members that were offline and never come back
online to take the record; copies of the post that travelled over the mesh
were sealed to their recipient and are deleted with that recipient's copy, but
nothing is sent to members that are not the author's contacts, since they
never received the post.

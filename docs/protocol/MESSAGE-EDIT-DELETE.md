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
| 12 | `[12, target, text]` | Replace the text of `target` with `text` |
| 13 | `[13, target]` | Remove `target` |

`target` is the 32-byte id under which the sender stored the message. A
message that reached the receiver over the mesh is stored there under a
different id; the receiver finds it by the sender's id recorded with the mesh
copy. `text` is 1 to `MAX_PRIVATE_MESSAGE_TEXT_LENGTH` bytes and may not start
with a form the app gives a special meaning (`SECRET:`, `VOICE_CALL:`,
`[VOICE:`, `[VMP:` or the legacy call signal prefix).

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
cannot be read back, but someone who can read the database can confirm a
guess of it through the message id (see "What remains" below).

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
- The message keeps its id, and the id confirms a guessed original. A message
  id is a hash over the conversation, the message time and a hash of the
  encoded body, and every other part of a one-to-one message's body (its
  type, the attachment list, the disappearing timer and the quoted message)
  can be read from the metadata; a legacy text message's body is the text
  alone.
  Someone who can read the database can therefore try candidate texts, for
  example the current text with a typo put back or a number changed, and
  confirm a right guess by computing the id again. The id gives nothing away
  beyond confirming a guess, but a short or predictable original is easy to
  guess. The id has to stay: the contact's acknowledgements and the
  recognition of a copy offered again refer to it. An edit that is rejected
  because it arrived after a newer one, or after its message was deleted for
  everyone, keeps its id, time and length, so a guess of its text can be
  confirmed the same way. Closing this would take a change of the message
  format.
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
  the author's contacts never received the post. A member that announces
  version 10 or later within 7 days of the delete is sent it then, re-signed
  over the same epoch and time, and drops the post. There is no time limit on
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

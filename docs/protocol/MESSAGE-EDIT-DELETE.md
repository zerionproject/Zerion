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
  the message and every quote of it are marked as edited, and the app shows
  no earlier version. The original body stays in the encrypted database
  until the message is deleted.
- A delete applies to a text, attachment, voice memo part or link preview
  message. The message is removed with its attachments, its reactions and
  any edit still waiting for it, without a placeholder and without a
  notification. If it was unread, it is also taken off the notification
  count, and a quote of it shows that the original is unavailable.
- An edit or delete that arrives before its message is kept and applied when
  the message arrives, also when the message is still waiting for its
  attachments. A waiting edit is kept for seven days, or for the
  conversation's disappearing-message time if that is shorter; a delete
  record is kept for seven days. At most 500 waiting records of each kind
  are kept per contact. A message that arrives after its delete is rejected
  and never shown, and a message still waiting for its attachments when its
  delete arrives is withdrawn.

## Sending

- Both are offered only when the contact announces minor version 9 or later
  of the messaging client. Earlier versions, 3.0.17 and before, and the iOS
  client ignore types 12 and 13 without a trace. Edit is then not offered,
  and delete for everyone removes the message on this device only and says so.
- An edit is possible for 24 hours after sending; delete for everyone has no
  time limit. A message that never left the device is simply withdrawn and
  deleting it sends nothing.
- Each edit of a message carries a later time than the one before, and a
  newer edit replaces an older one that has not been delivered yet.
- Messages sent over the mesh can be neither edited nor deleted for everyone:
  copies flooded through other phones cannot be recalled. Deleting one
  removes it on this device and stops it from being sent again. The edit and
  delete records themselves travel over Tor.

## Limits

Deleting for everyone removes the message from the contact's app. It cannot
remove copies the contact already made, such as screenshots, forwarded text
or a notification already read, and a modified app can ignore the records.
The 24-hour limit and the order of edits rest on the sender's own clock, so
a modified app can also edit outside that window.

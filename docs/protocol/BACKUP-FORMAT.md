# Account backup file format

This document describes the account backup file written and read by
`AccountBackupManager` (export and import in Settings, and import on the
welcome screen), and the account bundle it carries. Device-to-device
transfer reuses the bundle and is described at the end.

From 3.0.18 the save dialog suggests the name `backup.bin`, which does not
name the app (earlier versions suggested `zerion-backup.zbk`). Import does
not depend on the name or the extension: any file can be picked, and it is
recognised by its content, which still starts with the magic below.

From 3.0.18 the app writes version 3, a chunked and authenticated stream that
is exported and imported with a bounded amount of memory whatever the size
of the account. It still reads versions 1 and 2, within the memory bound
that applied to them.

## Common header

Every version starts with the magic `ZBK1` (4 bytes) and a version byte.
A file that does not start with the magic is refused as not a backup; an
unknown version byte is refused as made by a newer version, before any key
derivation.

## The account bundle

The plaintext of every version is the same bundle (bundle version 1), all
integers big endian:

| Field | Size | Rule on import |
|---|---|---|
| bundle version | 4 | must be 1 |
| display name | 2 + n | modified UTF-8 with its 2 byte length, n at most 65535 |
| key length | 4 | must be 32 |
| database key | 32 | |
| database length | 4 | 0 or more |
| database | as given | the SQLCipher database, still encrypted under the database key |
| vault length | 4 | 0 in version 3 (no file ever carried a vault) |
| vault | as given | ignored |

Nothing may follow the last field. Every length is checked against the bytes
that remain before anything is allocated (`BackupBundle`), and a streamed
bundle is parsed field by field as it arrives (`BundleStreamParser`), so the
only buffers sized by the input are the name (at most 64 KiB) and the
32 byte key.

## Version 3 (written from 3.0.18)

`BackupStream`. Header, 66 bytes:

| Field | Size |
|---|---|
| magic `ZBK1` | 4 |
| version 3 | 1 |
| flags | 1 |
| salt | 32 |
| Argon2id parameters (memory KiB, iterations, parallelism, output length) | 16 |
| chunk size | 4 |
| plaintext length | 8 |

followed by a 32 byte passphrase check and the chunks.

Keys: Argon2id over the passphrase and the salt (the export uses 128 MiB,
eight passes, one lane, 32 bytes), then HKDF-SHA256 with the salt and the
labels `Zerion-Account-Backup-ZBK3-enc` and
`Zerion-Account-Backup-ZBK3-verify`. The salt is fresh for every file, so
the encryption key is never used for two files. The passphrase check is
HMAC-SHA256 under the verification key over a fixed label; it tells a wrong
passphrase apart from a damaged file and is compared in constant time.

Chunks: the plaintext is cut into chunks of the header's chunk size (1 MiB
when exporting); only the last one may be shorter, so the number of chunks
and the length of each follow from the header. Chunk `i` is AES-256-GCM with
a 16 byte tag, a 12 byte nonce made of `i` (8 bytes), three zero bytes and a
final flag byte (1 for the last chunk, else 0), and as associated data the
whole 66 byte header followed by `i` (8 bytes) and the final flag. This is
the STREAM construction with AES-GCM: the header is bound to every chunk, so
a changed length, chunk size, flag, salt or parameter fails the first chunk;
a chunk moved, repeated or taken from another file fails its tag; a stream
cut at a chunk boundary lacks the chunk marked final; bytes after the final
chunk are refused.

Import rules: the parameters must lie in the range a version 2 file accepts
(128 to 256 MiB, one to ten passes, one to four lanes, 32 bytes), the chunk
size between 4 KiB and 4 MiB and the length between 1 byte and 4 GiB; the
passphrase check runs before any chunk is read. Each chunk is read into one
reused buffer of chunk size plus 16 bytes and opened into another; the
plaintext goes through the bundle parser, which writes the database to a
temporary file in the app cache (`zbk-*.restore`). Only when the final chunk
authenticated, the stream ended and the bundle was complete is a profile
created and the temporary file moved into it; on any failure the temporary
file is overwritten and deleted and no profile exists. A temporary file left
by a killed process is removed at the next start. While a restore, an export
or a transfer is running, its temporary file is left alone by the cache
sweeps that run when the app locks or starts its services.

Export streams the bundle from a snapshot of the database file
(`VACUUM INTO`, written to the cache and deleted afterwards) through the same
chunking straight into the chosen document. Memory use is a few chunk
buffers and the key derivation, independent of the account size. A failed or
cancelled export deletes the document once it was opened for writing, or if
it is still empty; a document chosen to be replaced is kept when the export
fails before writing to it. As for every copy, a failed export writes no root
evolution pause (see
[ZWF-ROOT-EVOLUTION.md](ZWF-ROOT-EVOLUTION.md)).

## Versions 1 and 2 (read only)

`BackupCrypto`. Header: magic, version, flags, salt (32), Argon2id
parameters (16); then a 32 byte passphrase check, a 4 byte length and one
AES-256-GCM message (4 byte nonce length, 12 byte nonce, ciphertext and tag)
over the whole bundle. Version 2 derives an encryption and a verification
key with HKDF-SHA256 (labels `Zerion-Account-Backup-ZBK2-enc` and
`-verify`) and authenticates the header; version 1 uses the Argon2id output
for both and authenticates a fixed label with the version and flags.

The 4 byte length must cover exactly the rest of the file: bytes after the
message are refused before any key derivation, as in version 3.

A whole-message tag can only be checked after the whole message was read,
so these files are read into memory: the file is read into one array of its
exact size, the message is decrypted from it into a second array and the
bundle parser copies the database out of that one, so up to three copies of
the file's size are held at once (the cipher may buffer the message too). A
file larger than a quarter of the app's heap (at most 512 MiB) is therefore
refused as too large before it is read.

## Device transfer

The transfer sends the same bundle inside one AES-256-GCM message under the
session key, preceded by its encrypted length. The wire format is unchanged
in 3.0.18 so that phones on different versions can still move an account;
the sender builds the message within the same heap bound as above, and the
receiver accepts an authenticated length only if four copies of it fit in
three quarters of its heap, so neither side can be driven out of memory. A
chunked transfer needs a protocol version that both phones announce, which
the current exchange has no field for.

package org.zerionproject.app.messaging;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.FileTooBigException;
import org.zerionproject.app.api.messaging.MessagingManager.ProgressCallback;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;
import static org.zerionproject.app.api.attachment.MediaConstants.CHUNK_SIZE;
import static org.zerionproject.app.api.attachment.MediaConstants.MAX_ATTACHMENT_SIZE;
import static org.zerionproject.app.api.attachment.MediaConstants.MAX_CHUNK_COUNT;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT_CHUNK;
import static org.zerionproject.app.messaging.MessageTypes.ATTACHMENT_MANIFEST;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_CHUNK_COUNT;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_CHUNK_INDEX;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_LOCAL;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MANIFEST_ID;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_MSG_TYPE;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_ROOT_HASH;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_TIMESTAMP;
import static org.zerionproject.app.messaging.MessagingConstants.MSG_KEY_TOTAL_SIZE;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;

@Immutable
@NotNullByDefault
public class StreamingAttachmentWriter {
	private static final int STREAMING_THRESHOLD = CHUNK_SIZE;

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;

	@Inject
	public StreamingAttachmentWriter(DatabaseComponent db,
			ClientHelper clientHelper) {
		this.db = db;
		this.clientHelper = clientHelper;
	}

	public AttachmentHeader storeAttachment(GroupId groupId, long timestamp,
			String contentType, InputStream inputStream, long totalSize,
			@Nullable ProgressCallback progressCallback)
			throws DbException, IOException {

		if (totalSize > MAX_ATTACHMENT_SIZE) {
			throw new FileTooBigException();
		}
		if (totalSize <= 0) {
			throw new IOException("Invalid attachment size: " + totalSize);
		}

		int chunkCount = (int) Math.ceil((double) totalSize / CHUNK_SIZE);
		if (chunkCount > MAX_CHUNK_COUNT) {
			throw new FileTooBigException();
		}

		if (totalSize <= STREAMING_THRESHOLD) {
			return storeLegacyAttachment(groupId, timestamp, contentType,
					inputStream, (int) totalSize, progressCallback);
		}

		return storeChunkedAttachment(groupId, timestamp, contentType,
				inputStream, totalSize, chunkCount, progressCallback);
	}

	private AttachmentHeader storeLegacyAttachment(GroupId groupId,
			long timestamp, String contentType, InputStream is, int totalSize,
			@Nullable ProgressCallback progressCallback)
			throws DbException, IOException {

		ByteArrayOutputStream bodyOut = new ByteArrayOutputStream();
		try {
			byte[] descriptor =
					clientHelper.toByteArray(BdfList.of(ATTACHMENT, contentType));
			bodyOut.write(descriptor);

			byte[] buffer = new byte[8192];
			int bytesRead;
			int totalRead = 0;

			while ((bytesRead = is.read(buffer)) != -1) {
				bodyOut.write(buffer, 0, bytesRead);
				totalRead += bytesRead;

				if (progressCallback != null && totalSize > 0) {
					progressCallback.onProgress((float) totalRead / totalSize);
				}
			}

			byte[] body = bodyOut.toByteArray();

			org.zerionproject.core.api.data.BdfDictionary meta =
					new org.zerionproject.core.api.data.BdfDictionary();
			meta.put(MSG_KEY_TIMESTAMP, timestamp);
			meta.put(MSG_KEY_LOCAL, true);
			meta.put(MSG_KEY_MSG_TYPE, ATTACHMENT);
			meta.put(MSG_KEY_CONTENT_TYPE, contentType);
			meta.put(MSG_KEY_DESCRIPTOR_LENGTH, descriptor.length);

			Message m = clientHelper.createMessage(groupId, timestamp, body);
			db.transaction(false, txn ->
					clientHelper.addLocalMessage(txn, m, meta, false, true));

			if (progressCallback != null) {
				progressCallback.onProgress(1.0f);
			}

			return new AttachmentHeader(groupId, m.getId(), contentType);
		} catch (FormatException e) {
			throw new IOException("Failed to encode attachment", e);
		} finally {
			try {
				is.close();
			} catch (IOException e) {
			}
		}
	}

	private AttachmentHeader storeChunkedAttachment(GroupId groupId,
			long timestamp, String contentType, InputStream inputStream,
			long totalSize, int chunkCount,
			@Nullable ProgressCallback progressCallback)
			throws DbException, IOException {

		List<byte[]> chunkHashes = new ArrayList<>(chunkCount);
		List<MessageId> chunkMessageIds = new ArrayList<>(chunkCount);

		try {
			long bytesProcessed = 0;
			int chunkIndex = 0;

			while (bytesProcessed < totalSize) {
				int chunkSize = (int) Math.min(CHUNK_SIZE, totalSize - bytesProcessed);
				byte[] chunkData = new byte[chunkSize];
				int offset = 0;

				while (offset < chunkSize) {
					int read = inputStream.read(chunkData, offset, chunkSize - offset);
					if (read == -1) {
						throw new IOException("Unexpected end of stream at byte " +
								(bytesProcessed + offset) + " of " + totalSize);
					}
					offset += read;
				}

				chunkHashes.add(sha256(chunkData));

				MessageId chunkMsgId = storeChunk(groupId, timestamp, chunkIndex,
						chunkCount, chunkData);
				chunkMessageIds.add(chunkMsgId);

				bytesProcessed += chunkSize;
				chunkIndex++;

				if (progressCallback != null) {
					progressCallback.onProgress((float) bytesProcessed / totalSize);
				}
			}

			byte[] rootHash = computeMerkleRoot(chunkHashes);

			MessageId manifestId = storeManifest(groupId, timestamp, contentType,
					totalSize, chunkCount, rootHash, chunkMessageIds);

			linkChunksToManifest(chunkMessageIds, manifestId);

			if (progressCallback != null) {
				progressCallback.onProgress(1.0f);
			}

			return new AttachmentHeader(groupId, manifestId, contentType);

		} catch (DbException | IOException e) {
			cleanupOrphanedChunks(chunkMessageIds);
			throw e;
		} finally {
			try {
				inputStream.close();
			} catch (IOException e) {
			}
		}
	}

	private MessageId storeChunk(GroupId groupId, long timestamp,
			int chunkIndex, int chunkCount, byte[] chunkData)
			throws DbException, IOException {

		try {
			byte[] header = clientHelper.toByteArray(
					BdfList.of(ATTACHMENT_CHUNK, chunkIndex, chunkData.length));

			byte[] body = new byte[header.length + chunkData.length];
			System.arraycopy(header, 0, body, 0, header.length);
			System.arraycopy(chunkData, 0, body, header.length, chunkData.length);

			org.zerionproject.core.api.data.BdfDictionary meta =
					new org.zerionproject.core.api.data.BdfDictionary();
			meta.put(MSG_KEY_TIMESTAMP, timestamp);
			meta.put(MSG_KEY_LOCAL, true);
			meta.put(MSG_KEY_MSG_TYPE, ATTACHMENT_CHUNK);
			meta.put(MSG_KEY_CHUNK_INDEX, chunkIndex);
			meta.put(MSG_KEY_CHUNK_COUNT, chunkCount);
			meta.put(MSG_KEY_DESCRIPTOR_LENGTH, header.length);

			Message m = clientHelper.createMessage(groupId, timestamp + chunkIndex, body);
			db.transaction(false, txn ->
					clientHelper.addLocalMessage(txn, m, meta, false, true));

			return m.getId();
		} catch (FormatException e) {
			throw new IOException("Failed to encode chunk", e);
		}
	}

	private MessageId storeManifest(GroupId groupId, long timestamp,
			String contentType, long totalSize, int chunkCount,
			byte[] rootHash, List<MessageId> chunkIds)
			throws DbException, IOException {

		try {
			BdfList chunkIdList = new BdfList();
			for (MessageId id : chunkIds) {
				chunkIdList.add(id.getBytes());
			}

			byte[] body = clientHelper.toByteArray(BdfList.of(
					ATTACHMENT_MANIFEST, contentType, totalSize, chunkCount,
					rootHash, chunkIdList));

			org.zerionproject.core.api.data.BdfDictionary meta =
					new org.zerionproject.core.api.data.BdfDictionary();
			meta.put(MSG_KEY_TIMESTAMP, timestamp);
			meta.put(MSG_KEY_LOCAL, true);
			meta.put(MSG_KEY_MSG_TYPE, ATTACHMENT_MANIFEST);
			meta.put(MSG_KEY_CONTENT_TYPE, contentType);
			meta.put(MSG_KEY_TOTAL_SIZE, totalSize);
			meta.put(MSG_KEY_CHUNK_COUNT, chunkCount);
			meta.put(MSG_KEY_ROOT_HASH, rootHash);

			Message m = clientHelper.createMessage(groupId, timestamp, body);
			db.transaction(false, txn ->
					clientHelper.addLocalMessage(txn, m, meta, false, true));

			return m.getId();
		} catch (FormatException e) {
			throw new IOException("Failed to encode manifest", e);
		}
	}

	private void linkChunksToManifest(List<MessageId> chunkIds,
			MessageId manifestId) throws DbException {
		db.transaction(false, txn -> {
			for (MessageId chunkId : chunkIds) {
				try {
					org.zerionproject.core.api.data.BdfDictionary meta =
							clientHelper.getMessageMetadataAsDictionary(txn, chunkId);
					meta.put(MSG_KEY_MANIFEST_ID, manifestId.getBytes());
					clientHelper.mergeMessageMetadata(txn, chunkId, meta);
				} catch (FormatException e) {
					throw new DbException(e);
				}
			}
		});
	}

	private void cleanupOrphanedChunks(List<MessageId> chunkIds) {
		if (chunkIds.isEmpty()) return;
		try {
			db.transaction(false, txn -> {
				for (MessageId chunkId : chunkIds) {
					try {
						db.deleteMessage(txn, chunkId);
					} catch (DbException e) {
					}
				}
			});
		} catch (DbException e) {
		}
	}

	private byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}

	private byte[] computeMerkleRoot(List<byte[]> chunkHashes) {
		if (chunkHashes.isEmpty()) return sha256(new byte[0]);
		if (chunkHashes.size() == 1) return chunkHashes.get(0);

		List<byte[]> current = new ArrayList<>(chunkHashes);
		while (current.size() > 1) {
			List<byte[]> next = new ArrayList<>();
			for (int i = 0; i < current.size(); i += 2) {
				if (i + 1 < current.size()) {
					byte[] combined = new byte[current.get(i).length +
							current.get(i + 1).length];
					System.arraycopy(current.get(i), 0, combined, 0,
							current.get(i).length);
					System.arraycopy(current.get(i + 1), 0, combined,
							current.get(i).length, current.get(i + 1).length);
					next.add(sha256(combined));
				} else {
					next.add(current.get(i));
				}
			}
			current = next;
		}
		return current.get(0);
	}
}

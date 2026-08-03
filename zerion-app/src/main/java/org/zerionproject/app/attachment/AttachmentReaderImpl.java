package org.zerionproject.app.attachment;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentNotYetAvailableException;
import org.zerionproject.app.api.attachment.AttachmentReader;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import javax.inject.Inject;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;

public class AttachmentReaderImpl implements AttachmentReader {
	private static final int ATTACHMENT = 1;
	private static final int ATTACHMENT_MANIFEST = 3;
	private static final int ATTACHMENT_CHUNK = 4;

	private static final String MSG_KEY_MSG_TYPE = "messageType";
	private static final String MSG_KEY_CHUNK_COUNT = "chunkCount";
	private static final String MSG_KEY_ROOT_HASH = "rootHash";

	private final TransactionManager db;
	private final ClientHelper clientHelper;

	@Inject
	public AttachmentReaderImpl(TransactionManager db,
			ClientHelper clientHelper) {
		this.db = db;
		this.clientHelper = clientHelper;
	}

	@Override
	public Attachment getAttachment(AttachmentHeader h) throws DbException {
		return db.transactionWithResult(true, txn -> getAttachment(txn, h));
	}

	@Override
	public Attachment getAttachment(Transaction txn, AttachmentHeader h)
			throws DbException {
		MessageId m = h.getMessageId();
		Message message = clientHelper.getMessage(txn, m);

		if (!message.getGroupId().equals(h.getGroupId())) {
			throw new NoSuchMessageException();
		}

		try {
			BdfDictionary meta =
					clientHelper.getMessageMetadataAsDictionary(txn, m);

			if (meta.isEmpty()) {
				throw new AttachmentNotYetAvailableException();
			}

			String contentType = meta.getString(MSG_KEY_CONTENT_TYPE);
			if (!contentType.equals(h.getContentType())) {
				throw new NoSuchMessageException();
			}

			int msgType = meta.getInt(MSG_KEY_MSG_TYPE, ATTACHMENT);

			if (msgType == ATTACHMENT_MANIFEST) {
				return readChunkedAttachment(txn, h, message, meta);
			} else {
				return readLegacyAttachment(h, message, meta);
			}
		} catch (FormatException e) {
			throw new NoSuchMessageException();
		}
	}

	private Attachment readLegacyAttachment(AttachmentHeader h, Message message,
			BdfDictionary meta) throws FormatException {
		byte[] body = message.getBody();
		int offset = meta.getInt(MSG_KEY_DESCRIPTOR_LENGTH);
		InputStream stream = new ByteArrayInputStream(body, offset,
				body.length - offset);
		return new Attachment(h, stream);
	}

	private Attachment readChunkedAttachment(Transaction txn, AttachmentHeader h,
			Message manifestMessage, BdfDictionary manifestMeta)
			throws DbException, FormatException {

		int chunkCount = manifestMeta.getInt(MSG_KEY_CHUNK_COUNT);
		byte[] expectedRootHash = manifestMeta.getRaw(MSG_KEY_ROOT_HASH);

		BdfList manifestBody = clientHelper.toList(manifestMessage.getBody());
		BdfList chunkIdList = manifestBody.getList(5);

		if (chunkIdList.size() != chunkCount) {
			throw new NoSuchMessageException();
		}

		long totalSize = manifestMeta.getLong("totalSize", -1L);
		if (totalSize <= 0L || totalSize > 10L * 1024 * 1024) {
			throw new NoSuchMessageException();
		}
		List<byte[]> chunkHashes = new ArrayList<>(chunkCount);
		ByteArrayOutputStream assembledData =
				new ByteArrayOutputStream((int) totalSize);
		long assembledLength = 0L;

		try {
			for (int i = 0; i < chunkCount; i++) {
				byte[] chunkIdBytes = chunkIdList.getRaw(i);
				MessageId chunkId = new MessageId(chunkIdBytes);

				Message chunkMessage;
				try {
					chunkMessage = clientHelper.getMessage(txn, chunkId);
				} catch (NoSuchMessageException e) {
					throw new AttachmentNotYetAvailableException();
				}

				if (!chunkMessage.getGroupId().equals(h.getGroupId())) {
					throw new NoSuchMessageException();
				}

				BdfDictionary chunkMeta =
						clientHelper.getMessageMetadataAsDictionary(txn, chunkId);

				if (chunkMeta.isEmpty()) {
					throw new AttachmentNotYetAvailableException();
				}

				byte[] body = chunkMessage.getBody();

				int headerLength = chunkMeta.getInt(MSG_KEY_DESCRIPTOR_LENGTH);
				BdfList chunkHeader = clientHelper.toList(body, 0, headerLength);
				int chunkIndex = chunkHeader.getInt(1);
				int dataLength = chunkHeader.getInt(2);

				if (body.length != headerLength + dataLength) {
					throw new NoSuchMessageException();
				}

				byte[] chunkData = new byte[dataLength];
				System.arraycopy(body, headerLength, chunkData, 0, dataLength);

				if (chunkIndex != i) {
					throw new NoSuchMessageException();
				}

				assembledLength += dataLength;
				if (assembledLength > totalSize) {
					throw new NoSuchMessageException();
				}
				chunkHashes.add(sha256(chunkData));
				assembledData.write(chunkData);
			}

			if (assembledLength != totalSize) {
				throw new NoSuchMessageException();
			}

			byte[] computedRootHash = computeMerkleRoot(chunkHashes);
			if (!Arrays.equals(expectedRootHash, computedRootHash)) {
				throw new NoSuchMessageException();
			}

			byte[] data = assembledData.toByteArray();
			return new Attachment(h, new ByteArrayInputStream(data));

		} catch (IOException e) {
			throw new DbException(e);
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

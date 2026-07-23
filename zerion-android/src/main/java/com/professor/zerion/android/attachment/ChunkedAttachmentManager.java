package com.professor.zerion.android.attachment;

import android.content.Context;

import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.app.api.attachment.AttachmentChunk;
import org.zerionproject.app.api.attachment.AttachmentManifest;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Singleton;

import static org.zerionproject.app.api.attachment.MediaConstants.CHUNK_SIZE;
import static org.zerionproject.app.api.attachment.MediaConstants.MAX_ATTACHMENT_SIZE;
import static org.zerionproject.app.api.attachment.MediaConstants.MAX_CHUNK_COUNT;
import static org.zerionproject.app.api.attachment.MediaConstants.MAX_PARALLEL_CHUNKS;

@Singleton
@NotNullByDefault
public class ChunkedAttachmentManager {

	private static final int NONCE_SIZE = 12;
	private static final int TAG_SIZE = 128;
	private static final int KEY_SIZE = 32;
	private static final String CIPHER_ALGORITHM = "AES/GCM/NoPadding";
	private static final String MAC_ALGORITHM = "HmacSHA256";

	private static final byte[] HKDF_CHUNK_ENC_INFO = "zerion-chunk-encryption-v1".getBytes();
	private static final byte[] HKDF_MANIFEST_MAC_INFO = "zerion-manifest-mac-v1".getBytes();
	private static final byte[] HKDF_NONCE_INFO = "zerion-chunk-nonce-v1".getBytes();
	private static final byte[] HKDF_STATE_MAC_INFO = "zerion-state-mac-v1".getBytes();

	public static final int MANIFEST_VERSION = 1;
	public static final int LEGACY_MAX_SIZE = 256 * 1024;

	private static final int CHUNK_REQUEST_WINDOW_MS = 1000;
	private static final int MAX_CHUNK_REQUESTS_PER_WINDOW = 10;

	private static final int MAX_CONCURRENT_TRANSFERS_PER_PEER = 3;
	private static final long MAX_TOTAL_INCOMPLETE_DISK_BYTES = 500 * 1024 * 1024L;
	private static final long STALE_TRANSFER_TTL_MS = 24 * 60 * 60 * 1000L;

	private final Context context;
	private final SecureRandom secureRandom;
	private final ConcurrentHashMap<MessageId, TransferState> activeTransfers;
	private final ConcurrentHashMap<String, PeerRateLimit> peerRateLimits;
	private final ConcurrentHashMap<String, AtomicInteger> peerTransferCounts;
	private final ConcurrentHashMap<MessageId, Long> completedTransfers;
	private final File chunksDir;
	private final File stateDir;
	private final AtomicLong totalIncompleteBytes = new AtomicLong(0);

	@Inject
	public ChunkedAttachmentManager(Context context) {
		this.context = context.getApplicationContext();
		this.secureRandom = new SecureRandom();
		this.activeTransfers = new ConcurrentHashMap<>();
		this.peerRateLimits = new ConcurrentHashMap<>();
		this.peerTransferCounts = new ConcurrentHashMap<>();
		this.completedTransfers = new ConcurrentHashMap<>();
		this.chunksDir = new File(context.getFilesDir(), "attachment_chunks");
		this.stateDir = new File(context.getFilesDir(), "attachment_state");
		if (!chunksDir.exists()) chunksDir.mkdirs();
		if (!stateDir.exists()) stateDir.mkdirs();
		restoreTransferStates();
		purgeStaleTransfers();
	}

	public AttachmentManifest createManifest(InputStream inputStream,
			String contentType, byte[] sessionKey) throws IOException {

		MessageId attachmentId = generateAttachmentId();
		byte[] chunkEncKey = deriveKey(sessionKey, HKDF_CHUNK_ENC_INFO, attachmentId);
		byte[] nonceKey = deriveKey(sessionKey, HKDF_NONCE_INFO, attachmentId);
		byte[] manifestMacKey = deriveKey(sessionKey, HKDF_MANIFEST_MAC_INFO, attachmentId);

		File tempFile = new File(chunksDir, toHex(attachmentId.getBytes()) + ".tmp");
		File finalFile = new File(chunksDir, toHex(attachmentId.getBytes()) + ".src");

		long totalSize = 0;
		byte[][] chunkCiphertextHashes;

		try (FileOutputStream fos = new FileOutputStream(tempFile)) {
			byte[] buffer = new byte[8192];
			int read;
			while ((read = inputStream.read(buffer)) != -1) {
				if (totalSize + read > MAX_ATTACHMENT_SIZE) {
					tempFile.delete();
					throw new IOException("Attachment exceeds " + (MAX_ATTACHMENT_SIZE / 1024 / 1024) + "MB limit");
				}
				fos.write(buffer, 0, read);
				totalSize += read;
			}
			fos.getFD().sync();
		}

		if (!tempFile.renameTo(finalFile)) {
			tempFile.delete();
			throw new IOException("Failed to finalize source file");
		}

		int chunkCount = (int) Math.ceil((double) totalSize / CHUNK_SIZE);
		if (chunkCount == 0) chunkCount = 1;
		if (chunkCount > MAX_CHUNK_COUNT) {
			finalFile.delete();
			throw new IOException("Attachment requires too many chunks");
		}

		chunkCiphertextHashes = new byte[chunkCount][];
		for (int i = 0; i < chunkCount; i++) {
			byte[] nonce = generateNonce(nonceKey, attachmentId, i);
			byte[] plainData = readChunkFromFile(finalFile, i, totalSize);
			byte[] ciphertext = encryptChunk(plainData, chunkEncKey, nonce, attachmentId, i, chunkCount, plainData.length);
			chunkCiphertextHashes[i] = sha256(ciphertext);
			Arrays.fill(plainData, (byte) 0);
		}

		byte[] rootHash = computeMerkleRoot(chunkCiphertextHashes);

		TransferState state = new TransferState(attachmentId, finalFile, chunkCount,
				chunkEncKey, nonceKey, manifestMacKey, totalSize);
		state.chunkCiphertextHashes = chunkCiphertextHashes;
		activeTransfers.put(attachmentId, state);

		byte[] manifestMac = computeManifestMac(manifestMacKey, MANIFEST_VERSION, attachmentId,
				contentType, totalSize, chunkCount, rootHash);

		return new AttachmentManifest(attachmentId, contentType, totalSize,
				chunkCount, CHUNK_SIZE, rootHash, manifestMac);
	}

	public boolean validateManifest(AttachmentManifest manifest, byte[] sessionKey) {
		if (manifest.getVersion() < 1 || manifest.getVersion() > MANIFEST_VERSION) return false;

		if (manifest.getTotalSize() > MAX_ATTACHMENT_SIZE) return false;
		if (manifest.getTotalSize() < 0) return false;
		if (manifest.getChunkCount() > MAX_CHUNK_COUNT) return false;
		if (manifest.getChunkCount() <= 0) return false;
		if (manifest.getChunkSize() != CHUNK_SIZE) return false;

		int expectedChunks = (int) Math.ceil((double) manifest.getTotalSize() / CHUNK_SIZE);
		if (expectedChunks == 0) expectedChunks = 1;
		if (manifest.getChunkCount() != expectedChunks) return false;

		byte[] manifestMacKey = deriveKey(sessionKey, HKDF_MANIFEST_MAC_INFO,
				manifest.getAttachmentId());
		byte[] expectedMac = computeManifestMac(manifestMacKey, manifest.getVersion(),
				manifest.getAttachmentId(), manifest.getContentType(), manifest.getTotalSize(),
				manifest.getChunkCount(), manifest.getRootHash());

		return constantTimeEquals(manifest.getManifestMac(), expectedMac);
	}

	public void initReceiveTransfer(AttachmentManifest manifest, byte[] sessionKey, String peerId)
			throws IOException {
		if (!validateManifest(manifest, sessionKey)) {
			throw new IOException("Invalid manifest");
		}

		MessageId attachmentId = manifest.getAttachmentId();

		if (completedTransfers.containsKey(attachmentId)) {
			throw new IOException("Attachment already completed - replay rejected");
		}
		if (activeTransfers.containsKey(attachmentId)) {
			throw new IOException("Transfer already in progress");
		}

		AtomicInteger peerCount = peerTransferCounts.computeIfAbsent(peerId,
				k -> new AtomicInteger(0));
		if (peerCount.get() >= MAX_CONCURRENT_TRANSFERS_PER_PEER) {
			throw new IOException("Too many concurrent transfers from peer");
		}

		long newTotal = totalIncompleteBytes.get() + manifest.getTotalSize();
		if (newTotal > MAX_TOTAL_INCOMPLETE_DISK_BYTES) {
			throw new IOException("Disk quota exceeded for incomplete transfers");
		}

		byte[] chunkEncKey = deriveKey(sessionKey, HKDF_CHUNK_ENC_INFO, attachmentId);
		byte[] nonceKey = deriveKey(sessionKey, HKDF_NONCE_INFO, attachmentId);
		byte[] manifestMacKey = deriveKey(sessionKey, HKDF_MANIFEST_MAC_INFO, attachmentId);

		TransferState state = new TransferState(attachmentId, null,
				manifest.getChunkCount(), chunkEncKey, nonceKey, manifestMacKey, manifest.getTotalSize());
		state.expectedRootHash = manifest.getRootHash();
		state.chunkCiphertextHashes = new byte[manifest.getChunkCount()][];
		state.peerId = peerId;
		state.createdTime = System.currentTimeMillis();

		peerCount.incrementAndGet();
		totalIncompleteBytes.addAndGet(manifest.getTotalSize());

		activeTransfers.put(attachmentId, state);
		persistTransferState(state);
	}

	public void initReceiveTransfer(AttachmentManifest manifest, byte[] sessionKey)
			throws IOException {
		initReceiveTransfer(manifest, sessionKey, "unknown");
	}

	public AttachmentChunk getChunk(MessageId attachmentId, int chunkIndex, String peerId)
			throws IOException {

		TransferState state = activeTransfers.get(attachmentId);
		if (state == null) {
			throw new IOException("No active transfer");
		}

		if (!checkPeerRateLimit(peerId)) {
			throw new IOException("Rate limit exceeded for peer");
		}

		if (chunkIndex < 0 || chunkIndex >= state.chunkCount) {
			throw new IOException("Invalid chunk index");
		}

		byte[] plainData = readChunkFromFile(state.sourceFile, chunkIndex, state.totalSize);
		byte[] nonce = generateNonce(state.nonceKey, attachmentId, chunkIndex);
		byte[] ciphertext = encryptChunk(plainData, state.chunkEncKey, nonce,
				attachmentId, chunkIndex, state.chunkCount, plainData.length);
		byte[] chunkHash = state.chunkCiphertextHashes[chunkIndex];

		Arrays.fill(plainData, (byte) 0);

		return new AttachmentChunk(attachmentId, chunkIndex, ciphertext, chunkHash);
	}

	public AttachmentChunk getChunk(MessageId attachmentId, int chunkIndex)
			throws IOException {
		return getChunk(attachmentId, chunkIndex, "local");
	}

	public void receiveChunk(AttachmentChunk chunk, byte[] sessionKey)
			throws IOException {

		MessageId attachmentId = chunk.getAttachmentId();
		TransferState state = activeTransfers.get(attachmentId);

		if (state == null) {
			throw new IOException("No active transfer");
		}

		if (chunk.getChunkIndex() < 0 || chunk.getChunkIndex() >= state.chunkCount) {
			throw new IOException("Invalid chunk index");
		}

		if (state.receivedChunks.get(chunk.getChunkIndex())) {
			return;
		}

		byte[] nonce = generateNonce(state.nonceKey, attachmentId, chunk.getChunkIndex());

		byte[] ciphertextHash = sha256(chunk.getData());
		state.chunkCiphertextHashes[chunk.getChunkIndex()] = ciphertextHash;

		byte[] decrypted = decryptChunk(chunk.getData(), state.chunkEncKey,
				nonce, attachmentId, chunk.getChunkIndex(),
				state.chunkCount, state.totalSize);

		File tempChunkFile = new File(chunksDir, toHex(attachmentId.getBytes()) + "_" + chunk.getChunkIndex() + ".tmp");
		File finalChunkFile = getChunkFile(attachmentId, chunk.getChunkIndex());

		try (FileOutputStream fos = new FileOutputStream(tempChunkFile)) {
			fos.write(decrypted);
			fos.getFD().sync();
		}
		Arrays.fill(decrypted, (byte) 0);

		if (!tempChunkFile.renameTo(finalChunkFile)) {
			tempChunkFile.delete();
			throw new IOException("Failed to finalize chunk");
		}

		state.receivedChunks.set(chunk.getChunkIndex());
		persistTransferState(state);
	}

	public boolean isTransferComplete(MessageId attachmentId) {
		TransferState state = activeTransfers.get(attachmentId);
		if (state == null) return false;
		return state.receivedChunks.cardinality() == state.chunkCount;
	}

	public int[] getMissingChunks(MessageId attachmentId, int maxCount) {
		TransferState state = activeTransfers.get(attachmentId);
		if (state == null) return new int[0];

		int count = Math.min(maxCount, MAX_PARALLEL_CHUNKS);
		int[] result = new int[count];
		int idx = 0;
		for (int i = 0; i < state.chunkCount && idx < count; i++) {
			if (!state.receivedChunks.get(i)) {
				result[idx++] = i;
			}
		}
		if (idx < count) {
			int[] trimmed = new int[idx];
			System.arraycopy(result, 0, trimmed, 0, idx);
			return trimmed;
		}
		return result;
	}

	public File assembleAttachment(MessageId attachmentId) throws IOException {
		TransferState state = activeTransfers.get(attachmentId);
		if (state == null) {
			throw new IOException("No active transfer");
		}

		if (!isTransferComplete(attachmentId)) {
			throw new IOException("Transfer incomplete");
		}

		byte[] computedRoot = computeMerkleRoot(state.chunkCiphertextHashes);
		if (!constantTimeEquals(computedRoot, state.expectedRootHash)) {
			purgeTransfer(attachmentId, state, true);
			throw new IOException("Root hash verification failed - transfer purged");
		}

		File tempOutput = new File(chunksDir, toHex(attachmentId.getBytes()) + ".assembling");
		File finalOutput = new File(chunksDir, toHex(attachmentId.getBytes()) + ".complete");

		try {
			try (FileOutputStream fos = new FileOutputStream(tempOutput)) {
				for (int i = 0; i < state.chunkCount; i++) {
					File chunkFile = getChunkFile(attachmentId, i);
					try (FileInputStream fis = new FileInputStream(chunkFile)) {
						byte[] buffer = new byte[8192];
						int read;
						while ((read = fis.read(buffer)) != -1) {
							fos.write(buffer, 0, read);
						}
					}
				}
				fos.getFD().sync();
			}

			if (!tempOutput.renameTo(finalOutput)) {
				tempOutput.delete();
				throw new IOException("Failed to finalize assembled file");
			}

			cleanupSuccessfulTransfer(attachmentId, state);

			return finalOutput;
		} catch (IOException e) {
			tempOutput.delete();
			throw e;
		}
	}

	private void cleanupSuccessfulTransfer(MessageId attachmentId, TransferState state) {
		for (int i = 0; i < state.chunkCount; i++) {
			getChunkFile(attachmentId, i).delete();
		}
		if (state.sourceFile != null) {
			state.sourceFile.delete();
		}
		getStateFile(attachmentId).delete();
		new File(chunksDir, toHex(attachmentId.getBytes()) + ".tmp").delete();
		new File(chunksDir, toHex(attachmentId.getBytes()) + ".assembling").delete();

		totalIncompleteBytes.addAndGet(-state.totalSize);
		AtomicInteger peerCount = peerTransferCounts.get(state.peerId);
		if (peerCount != null) {
			peerCount.decrementAndGet();
		}

		completedTransfers.put(attachmentId, System.currentTimeMillis());
		activeTransfers.remove(attachmentId);
	}

	private void purgeTransfer(MessageId attachmentId, TransferState state, boolean integrityFailure) {
		if (state == null) {
			state = activeTransfers.get(attachmentId);
		}
		if (state != null) {
			for (int i = 0; i < state.chunkCount; i++) {
				getChunkFile(attachmentId, i).delete();
			}
			if (state.sourceFile != null) {
				state.sourceFile.delete();
			}

			totalIncompleteBytes.addAndGet(-state.totalSize);
			AtomicInteger peerCount = peerTransferCounts.get(state.peerId);
			if (peerCount != null) {
				peerCount.decrementAndGet();
			}

			if (integrityFailure) {
				completedTransfers.put(attachmentId, System.currentTimeMillis());
			}
		}
		getStateFile(attachmentId).delete();
		new File(chunksDir, toHex(attachmentId.getBytes()) + ".tmp").delete();
		new File(chunksDir, toHex(attachmentId.getBytes()) + ".src").delete();
		new File(chunksDir, toHex(attachmentId.getBytes()) + ".assembling").delete();
		new File(chunksDir, toHex(attachmentId.getBytes()) + ".complete").delete();
		activeTransfers.remove(attachmentId);
	}

	public void cancelTransfer(MessageId attachmentId) {
		purgeTransfer(attachmentId, null, false);
	}

	public float getProgress(MessageId attachmentId) {
		TransferState state = activeTransfers.get(attachmentId);
		if (state == null || state.chunkCount == 0) return 0f;
		return (float) state.receivedChunks.cardinality() / state.chunkCount;
	}

	public boolean resumeTransfer(MessageId attachmentId, byte[] sessionKey) {
		TransferState state = activeTransfers.get(attachmentId);
		if (state == null) return false;
		if (!state.needsKeyDerivation) return true;

		state.chunkEncKey = deriveKey(sessionKey, HKDF_CHUNK_ENC_INFO, attachmentId);
		state.nonceKey = deriveKey(sessionKey, HKDF_NONCE_INFO, attachmentId);
		state.manifestMacKey = deriveKey(sessionKey, HKDF_MANIFEST_MAC_INFO, attachmentId);
		state.needsKeyDerivation = false;
		return true;
	}

	public boolean needsKeyDerivation(MessageId attachmentId) {
		TransferState state = activeTransfers.get(attachmentId);
		return state != null && state.needsKeyDerivation;
	}

	public boolean hasTransfer(MessageId attachmentId) {
		return activeTransfers.containsKey(attachmentId);
	}

	public static boolean isLegacySizeAcceptable(long size) {
		return size > 0 && size <= LEGACY_MAX_SIZE;
	}

	public static boolean requiresChunkedTransfer(long size) {
		return size > LEGACY_MAX_SIZE;
	}

	private byte[] encryptChunk(byte[] data, byte[] key, byte[] nonce,
			MessageId attachmentId, int chunkIndex, int totalChunks, int plainLen)
			throws IOException {
		try {
			Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
			SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
			GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE, nonce);
			cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);
			cipher.updateAAD(createAAD(attachmentId, chunkIndex, totalChunks, plainLen));
			return cipher.doFinal(data);
		} catch (Exception e) {
			throw new IOException("Encryption failed", e);
		}
	}

	private byte[] decryptChunk(byte[] ciphertext, byte[] key, byte[] nonce,
			MessageId attachmentId, int chunkIndex, int totalChunks, long totalSize)
			throws IOException {
		try {
			Cipher cipher = Cipher.getInstance(CIPHER_ALGORITHM);
			SecretKeySpec keySpec = new SecretKeySpec(key, "AES");
			GCMParameterSpec gcmSpec = new GCMParameterSpec(TAG_SIZE, nonce);
			cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

			long chunkOffset = (long) chunkIndex * CHUNK_SIZE;
			int expectedPlainLen = (int) Math.min(CHUNK_SIZE, totalSize - chunkOffset);
			cipher.updateAAD(createAAD(attachmentId, chunkIndex, totalChunks, expectedPlainLen));

			return cipher.doFinal(ciphertext);
		} catch (Exception e) {
			throw new IOException("Decryption failed - chunk may be corrupted or tampered", e);
		}
	}

	private byte[] createAAD(MessageId attachmentId, int chunkIndex, int totalChunks, int plainLen) {
		byte[] idBytes = attachmentId.getBytes();
		byte[] aad = new byte[idBytes.length + 12];
		System.arraycopy(idBytes, 0, aad, 0, idBytes.length);
		int offset = idBytes.length;
		aad[offset++] = (byte) (chunkIndex >> 24);
		aad[offset++] = (byte) (chunkIndex >> 16);
		aad[offset++] = (byte) (chunkIndex >> 8);
		aad[offset++] = (byte) chunkIndex;
		aad[offset++] = (byte) (totalChunks >> 24);
		aad[offset++] = (byte) (totalChunks >> 16);
		aad[offset++] = (byte) (totalChunks >> 8);
		aad[offset++] = (byte) totalChunks;
		aad[offset++] = (byte) (plainLen >> 24);
		aad[offset++] = (byte) (plainLen >> 16);
		aad[offset++] = (byte) (plainLen >> 8);
		aad[offset] = (byte) plainLen;
		return aad;
	}

	private byte[] generateNonce(byte[] nonceKey, MessageId attachmentId, int chunkIndex) {
		try {
			Mac mac = Mac.getInstance(MAC_ALGORITHM);
			mac.init(new SecretKeySpec(nonceKey, MAC_ALGORITHM));
			mac.update(attachmentId.getBytes());
			mac.update(intToBytes(chunkIndex));
			byte[] fullHash = mac.doFinal();
			byte[] nonce = new byte[NONCE_SIZE];
			System.arraycopy(fullHash, 0, nonce, 0, NONCE_SIZE);
			return nonce;
		} catch (Exception e) {
			throw new RuntimeException("Nonce generation failed", e);
		}
	}

	private byte[] deriveKey(byte[] sessionKey, byte[] info, MessageId attachmentId) {
		try {
			Mac mac = Mac.getInstance(MAC_ALGORITHM);
			mac.init(new SecretKeySpec(sessionKey, MAC_ALGORITHM));
			mac.update(info);
			mac.update(attachmentId.getBytes());
			byte[] derived = mac.doFinal();
			byte[] key = new byte[KEY_SIZE];
			System.arraycopy(derived, 0, key, 0, KEY_SIZE);
			return key;
		} catch (Exception e) {
			throw new RuntimeException("Key derivation failed", e);
		}
	}

	private byte[] computeManifestMac(byte[] macKey, int version, MessageId attachmentId,
			String contentType, long totalSize, int chunkCount, byte[] rootHash) {
		try {
			Mac mac = Mac.getInstance(MAC_ALGORITHM);
			mac.init(new SecretKeySpec(macKey, MAC_ALGORITHM));
			mac.update(intToBytes(version));
			mac.update(attachmentId.getBytes());
			mac.update(contentType.getBytes("UTF-8"));
			mac.update(longToBytes(totalSize));
			mac.update(intToBytes(chunkCount));
			mac.update(intToBytes(CHUNK_SIZE));
			mac.update(rootHash);
			return mac.doFinal();
		} catch (Exception e) {
			throw new RuntimeException("MAC computation failed", e);
		}
	}

	private byte[] computeMerkleRoot(byte[][] chunkHashes) {
		if (chunkHashes.length == 0) return sha256(new byte[0]);
		if (chunkHashes.length == 1) return chunkHashes[0];

		byte[][] current = chunkHashes;
		while (current.length > 1) {
			int newLen = (current.length + 1) / 2;
			byte[][] next = new byte[newLen][];
			for (int i = 0; i < newLen; i++) {
				int left = i * 2;
				int right = left + 1;
				if (right >= current.length) {
					next[i] = current[left];
				} else {
					byte[] combined = new byte[current[left].length + current[right].length];
					System.arraycopy(current[left], 0, combined, 0, current[left].length);
					System.arraycopy(current[right], 0, combined, current[left].length, current[right].length);
					next[i] = sha256(combined);
				}
			}
			current = next;
		}
		return current[0];
	}

	private byte[] readChunkFromFile(File file, int chunkIndex, long totalSize) throws IOException {
		long offset = (long) chunkIndex * CHUNK_SIZE;
		int length = (int) Math.min(CHUNK_SIZE, totalSize - offset);
		byte[] data = new byte[length];

		try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
			raf.seek(offset);
			int read = raf.read(data);
			if (read < length) {
				byte[] trimmed = new byte[read];
				System.arraycopy(data, 0, trimmed, 0, read);
				return trimmed;
			}
		}
		return data;
	}

	private boolean checkPeerRateLimit(String peerId) {
		PeerRateLimit rateLimit = peerRateLimits.computeIfAbsent(peerId,
				k -> new PeerRateLimit());
		return rateLimit.checkAndIncrement();
	}

	public void cleanupStaleRateLimits() {
		long now = System.currentTimeMillis();
		peerRateLimits.entrySet().removeIf(entry ->
				now - entry.getValue().lastWindowStart.get() > CHUNK_REQUEST_WINDOW_MS * 10);
	}

	public void purgeStaleTransfers() {
		long now = System.currentTimeMillis();
		for (TransferState state : activeTransfers.values()) {
			if (now - state.createdTime > STALE_TRANSFER_TTL_MS) {
				purgeTransfer(state.attachmentId, state, false);
			}
		}
		completedTransfers.entrySet().removeIf(entry ->
				now - entry.getValue() > 60 * 60 * 1000L);
	}

	public ResourceStats getResourceStats() {
		return new ResourceStats(
				activeTransfers.size(),
				totalIncompleteBytes.get(),
				MAX_TOTAL_INCOMPLETE_DISK_BYTES,
				completedTransfers.size()
		);
	}

	public static class ResourceStats {
		public final int activeTransfers;
		public final long usedDiskBytes;
		public final long maxDiskBytes;
		public final int completedTransferCount;

		ResourceStats(int activeTransfers, long usedDiskBytes, long maxDiskBytes, int completedTransferCount) {
			this.activeTransfers = activeTransfers;
			this.usedDiskBytes = usedDiskBytes;
			this.maxDiskBytes = maxDiskBytes;
			this.completedTransferCount = completedTransferCount;
		}
	}

	private void persistTransferState(TransferState state) {
		File stateFile = getStateFile(state.attachmentId);
		File tempFile = new File(stateFile.getPath() + ".tmp");

		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			DataOutputStream dos = new DataOutputStream(baos);

			dos.write(state.attachmentId.getBytes());
			dos.writeInt(state.chunkCount);
			dos.writeLong(state.totalSize);
			dos.write(state.expectedRootHash != null ? state.expectedRootHash : new byte[32]);

			byte[] bitsetBytes = state.receivedChunks.toByteArray();
			dos.writeInt(bitsetBytes.length);
			dos.write(bitsetBytes);
			dos.flush();

			byte[] stateData = baos.toByteArray();
			byte[] checksum = computeStateChecksum(stateData, state.manifestMacKey);

			try (FileOutputStream fos = new FileOutputStream(tempFile)) {
				fos.write(stateData);
				fos.write(checksum);
				fos.getFD().sync();
			}

			if (!tempFile.renameTo(stateFile)) {
				tempFile.delete();
			}
		} catch (Exception e) {
			tempFile.delete();
		}
	}

	private void restoreTransferStates() {
		File[] stateFiles = stateDir.listFiles((dir, name) -> name.endsWith(".state"));
		if (stateFiles == null) return;

		for (File stateFile : stateFiles) {
			try {
				restoreTransferState(stateFile);
			} catch (Exception e) {
				stateFile.delete();
			}
		}
	}

	private void restoreTransferState(File stateFile) throws IOException {
		byte[] fileData;
		try (FileInputStream fis = new FileInputStream(stateFile)) {
			fileData = new byte[(int) stateFile.length()];
			int read = fis.read(fileData);
			if (read != fileData.length) {
				throw new IOException("Incomplete state file");
			}
		}

		if (fileData.length < 32) {
			throw new IOException("State file too small");
		}

		int checksumOffset = fileData.length - 32;
		byte[] stateData = new byte[checksumOffset];
		byte[] storedChecksum = new byte[32];
		System.arraycopy(fileData, 0, stateData, 0, checksumOffset);
		System.arraycopy(fileData, checksumOffset, storedChecksum, 0, 32);

		try (DataInputStream dis = new DataInputStream(
				new java.io.ByteArrayInputStream(stateData))) {
			byte[] idBytes = new byte[32];
			dis.readFully(idBytes);
			MessageId attachmentId = new MessageId(idBytes);

			int chunkCount = dis.readInt();
			long totalSize = dis.readLong();

			byte[] rootHash = new byte[32];
			dis.readFully(rootHash);

			int bitsetLen = dis.readInt();
			byte[] bitsetBytes = new byte[bitsetLen];
			dis.readFully(bitsetBytes);
			BitSet receivedChunks = BitSet.valueOf(bitsetBytes);

			TransferState state = new TransferState(attachmentId, null, chunkCount,
					new byte[32], new byte[32], new byte[32], totalSize);
			state.expectedRootHash = rootHash;
			state.receivedChunks.or(receivedChunks);
			state.chunkCiphertextHashes = new byte[chunkCount][];
			state.needsKeyDerivation = true;

			activeTransfers.put(attachmentId, state);
		}
	}

	private byte[] computeStateChecksum(byte[] data, byte[] key) {
		try {
			Mac mac = Mac.getInstance(MAC_ALGORITHM);
			mac.init(new SecretKeySpec(key, MAC_ALGORITHM));
			return mac.doFinal(data);
		} catch (Exception e) {
			return new byte[32];
		}
	}

	private File getChunkFile(MessageId attachmentId, int chunkIndex) {
		return new File(chunksDir, toHex(attachmentId.getBytes()) + "_" + chunkIndex + ".enc");
	}

	private File getStateFile(MessageId attachmentId) {
		return new File(stateDir, toHex(attachmentId.getBytes()) + ".state");
	}

	private MessageId generateAttachmentId() {
		byte[] id = new byte[32];
		secureRandom.nextBytes(id);
		return new MessageId(id);
	}

	private byte[] sha256(byte[] data) {
		try {
			return MessageDigest.getInstance("SHA-256").digest(data);
		} catch (NoSuchAlgorithmException e) {
			throw new RuntimeException("SHA-256 not available", e);
		}
	}

	private static boolean constantTimeEquals(byte[] a, byte[] b) {
		if (a == null || b == null) return false;
		if (a.length != b.length) return false;
		int result = 0;
		for (int i = 0; i < a.length; i++) {
			result |= a[i] ^ b[i];
		}
		return result == 0;
	}

	private static byte[] longToBytes(long value) {
		return new byte[] {
			(byte) (value >> 56), (byte) (value >> 48),
			(byte) (value >> 40), (byte) (value >> 32),
			(byte) (value >> 24), (byte) (value >> 16),
			(byte) (value >> 8), (byte) value
		};
	}

	private static byte[] intToBytes(int value) {
		return new byte[] {
			(byte) (value >> 24), (byte) (value >> 16),
			(byte) (value >> 8), (byte) value
		};
	}

	private static String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	private static class TransferState {
		final MessageId attachmentId;
		final File sourceFile;
		final int chunkCount;
		byte[] chunkEncKey;
		byte[] nonceKey;
		byte[] manifestMacKey;
		final long totalSize;
		final BitSet receivedChunks;
		byte[] expectedRootHash;
		byte[][] chunkCiphertextHashes;
		boolean needsKeyDerivation = false;
		String peerId = "unknown";
		long createdTime = System.currentTimeMillis();

		TransferState(MessageId attachmentId, File sourceFile, int chunkCount,
				byte[] chunkEncKey, byte[] nonceKey, byte[] manifestMacKey, long totalSize) {
			this.attachmentId = attachmentId;
			this.sourceFile = sourceFile;
			this.chunkCount = chunkCount;
			this.chunkEncKey = chunkEncKey;
			this.nonceKey = nonceKey;
			this.manifestMacKey = manifestMacKey;
			this.totalSize = totalSize;
			this.receivedChunks = new BitSet(chunkCount);
		}
	}

	private static class PeerRateLimit {
		final AtomicLong lastWindowStart = new AtomicLong(0);
		final AtomicInteger requestsInWindow = new AtomicInteger(0);

		boolean checkAndIncrement() {
			long now = System.currentTimeMillis();
			if (now - lastWindowStart.get() > CHUNK_REQUEST_WINDOW_MS) {
				lastWindowStart.set(now);
				requestsInWindow.set(1);
				return true;
			}
			return requestsInWindow.incrementAndGet() <= MAX_CHUNK_REQUESTS_PER_WINDOW;
		}
	}
}

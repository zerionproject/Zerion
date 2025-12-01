package com.professor.zerion.android.conversation.voice;

import android.content.Context;

import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessage;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@NotNullByDefault
public class VoiceMessageHandler {

	private static final String VOICE_MESSAGE_MIME_TYPE = "audio/3gpp";
	private static final int MAX_VOICE_MESSAGE_SIZE = 32 * 1024;

	private final Context context;
	private final Executor cryptoExecutor;
	private final VoiceMessageRecorder recorder;
	private final VoiceMessagePlayer player;

	@Inject
	public VoiceMessageHandler(Context context, Executor cryptoExecutor) {
		this.context = context;
		this.cryptoExecutor = cryptoExecutor;
		this.recorder = new VoiceMessageRecorder(context, cryptoExecutor);
		this.player = new VoiceMessagePlayer(context, cryptoExecutor);
	}

	public AttachmentHeader createVoiceAttachment(File voiceFile,
			GroupId groupId) throws IOException {
		if (voiceFile.length() > MAX_VOICE_MESSAGE_SIZE) {
			throw new IOException("Voice message too large");
		}

		MessageId messageId = generateMessageId(voiceFile);

		return new AttachmentHeader(groupId, messageId, VOICE_MESSAGE_MIME_TYPE);
	}

	public byte[] processVoiceMessage(File voiceFile) throws IOException {
		ByteArrayOutputStream baos = new ByteArrayOutputStream();

		try (FileInputStream fis = new FileInputStream(voiceFile)) {
			byte[] buffer = new byte[4096];
			int bytesRead;

			while ((bytesRead = fis.read(buffer)) != -1) {
				baos.write(buffer, 0, bytesRead);
			}
		}

		voiceFile.delete();

		return baos.toByteArray();
	}

	public File saveVoiceMessage(byte[] voiceData, MessageId messageId)
			throws IOException {
		String filename = toHex(messageId.getBytes()) + ".3gp";
		File outputFile = new File(context.getCacheDir(), filename);

		try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outputFile)) {
			fos.write(voiceData);
		}

		return outputFile;
	}

	private String toHex(byte[] bytes) {
		StringBuilder sb = new StringBuilder();
		for (byte b : bytes) {
			sb.append(String.format("%02x", b));
		}
		return sb.toString();
	}

	public void startRecording(File outputFile, VoiceMessageRecorder.RecordingCallback callback) {
		recorder.startRecording(outputFile, callback);
	}

	public void stopRecording() {
		recorder.stopLegacyRecording();
	}

	public void cancelRecording() {
		recorder.cancelLegacyRecording();
	}

	public void playVoiceMessage(File voiceFile, boolean ephemeral,
			VoiceMessagePlayer.PlaybackCallback callback) {
		player.playVoiceMessage(voiceFile, ephemeral, callback);
	}

	public void pausePlayback() {
		player.pausePlayback();
	}

	public void resumePlayback() {
		player.resumePlayback();
	}

	public void stopPlayback() {
		player.stopPlayback();
	}

	private MessageId generateMessageId(File file) throws IOException {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");

			try (FileInputStream fis = new FileInputStream(file)) {
				byte[] buffer = new byte[8192];
				int bytesRead;
				while ((bytesRead = fis.read(buffer)) != -1) {
					digest.update(buffer, 0, bytesRead);
				}
			}

			byte[] hash = digest.digest();
			byte[] messageIdBytes = new byte[MessageId.LENGTH];
			System.arraycopy(hash, 0, messageIdBytes, 0,
					Math.min(hash.length, MessageId.LENGTH));

			return new MessageId(messageIdBytes);
		} catch (NoSuchAlgorithmException e) {
			throw new IOException("SHA-256 not available", e);
		}
	}

	public void cleanupOldVoiceMessages(long maxAgeMs) {
		File cacheDir = context.getCacheDir();
		File[] files = cacheDir.listFiles((dir, name) -> name.endsWith(".3gp"));

		if (files != null) {
			long now = System.currentTimeMillis();
			for (File file : files) {
				if (now - file.lastModified() > maxAgeMs) {
					file.delete();
				}
			}
		}
	}

	public void release() {
		recorder.release();
		player.release();
	}

	public boolean isRecording() {
		return recorder.isRecording();
	}

	public boolean isPlaying() {
		return player.isPlaying();
	}

	public void processAndSendVoiceMessage(File voiceFile,
			MessagingManager messagingManager, ContactId contactId)
			throws IOException, DbException {

		if (!voiceFile.exists() || voiceFile.length() == 0) {
			throw new IOException("Invalid voice recording file");
		}

		byte[] voiceData = processVoiceMessage(voiceFile);


		voiceFile.delete();

	}
}

package com.professor.zerion.android.attachment;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.sync.GroupId;
import com.professor.zerion.android.attachment.media.ImageCompressor;
import com.professor.zerion.android.util.MediaMagic;
import com.professor.zerion.android.vault.utils.MetadataStripper;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import androidx.annotation.Nullable;

import static java.util.Arrays.asList;
import static org.zerionproject.core.util.AndroidUtils.getSupportedImageContentTypes;
import static org.zerionproject.core.util.IoUtils.tryToClose;
import static com.professor.zerion.android.attachment.media.ImageCompressor.MIME_TYPE;
import static org.zerionproject.app.api.attachment.MediaConstants.MAX_ATTACHMENT_SIZE;

import java.util.HashSet;
import java.util.Set;

@NotNullByDefault
class AttachmentCreationTask {
	private static final Set<String> SUPPORTED_AUDIO_TYPES = new HashSet<>(asList(
			"audio/opus",
			"audio/ogg",
			"audio/aac",
			"audio/mp4",
			"audio/mpeg",
			"audio/3gpp",
			"audio/3gp"
	));

	private static final Set<String> ISO_MEDIA_AUDIO_TYPES = new HashSet<>(asList(
			"audio/mp4",
			"audio/3gpp",
			"audio/3gp"
	));

	private static final Set<String> SUPPORTED_VIDEO_TYPES = new HashSet<>(asList(
			"video/mp4",
			"video/3gpp",
			"video/webm",
			"video/x-matroska",
			"video/quicktime",
			"video/mpeg",
			"video/avi"
	));

	private final MessagingManager messagingManager;
	private final ContentResolver contentResolver;
	private final ImageCompressor imageCompressor;
	private final MetadataStripper metadataStripper;
	private final GroupId groupId;
	private final Collection<Uri> uris;
	private final boolean needsSize;
	private final PrivateMessageFormat messageFormat;
	@Nullable
	private volatile AttachmentCreator attachmentCreator;

	private volatile boolean canceled = false;

	AttachmentCreationTask(MessagingManager messagingManager,
			ContentResolver contentResolver,
			AttachmentCreator attachmentCreator,
			ImageCompressor imageCompressor,
			MetadataStripper metadataStripper,
			GroupId groupId, Collection<Uri> uris, boolean needsSize,
			PrivateMessageFormat messageFormat) {
		this.messagingManager = messagingManager;
		this.contentResolver = contentResolver;
		this.imageCompressor = imageCompressor;
		this.metadataStripper = metadataStripper;
		this.groupId = groupId;
		this.uris = uris;
		this.needsSize = needsSize;
		this.attachmentCreator = attachmentCreator;
		this.messageFormat = messageFormat;
	}

	void cancel() {
		canceled = true;
		attachmentCreator = null;
	}

	@IoExecutor
	void storeAttachments() {
		for (Uri uri : uris) processUri(uri);
		AttachmentCreator attachmentCreator = this.attachmentCreator;
		if (!canceled && attachmentCreator != null)
			attachmentCreator.onAttachmentCreationFinished();
		this.attachmentCreator = null;
	}

	@IoExecutor
	private void processUri(Uri uri) {
		if (canceled) return;
		try {
			AttachmentHeader h = storeAttachment(uri);
			AttachmentCreator attachmentCreator = this.attachmentCreator;
			if (attachmentCreator != null) {
				attachmentCreator.onAttachmentHeaderReceived(uri, h, needsSize);
			}
		} catch (DbException | IOException | ChunkedAttachmentsNotSupportedException e) {
			AttachmentCreator attachmentCreator = this.attachmentCreator;
			if (attachmentCreator != null) {
				attachmentCreator.onAttachmentError(uri, e);
			}
			canceled = true;
		}
	}

	@IoExecutor
	private AttachmentHeader storeAttachment(Uri uri)
			throws IOException, DbException, ChunkedAttachmentsNotSupportedException {
		if (!"content".equals(uri.getScheme())) {
			throw new IOException("not a content address");
		}
		String contentType = contentResolver.getType(uri);
		if (contentType == null) {
			contentType = getMimeTypeFromExtension(uri);
		}
		if (contentType == null) throw new IOException("null content type");

		boolean isAudio = SUPPORTED_AUDIO_TYPES.contains(contentType);
		boolean isVideo = SUPPORTED_VIDEO_TYPES.contains(contentType);
		boolean isImage = asList(getSupportedImageContentTypes()).contains(contentType);
		String documentType = AttachmentDocuments.sendType(contentType);

		if (!isAudio && !isVideo && !isImage && documentType != null) {
			if (!messageFormat.supportsChunkedAttachments()) {
				throw new ChunkedAttachmentsNotSupportedException(documentType);
			}
			return storeDocument(uri, documentType);
		}

		if (!isAudio && !isVideo && !isImage) {
			throw new UnsupportedMimeTypeException(contentType, uri);
		}

		if (isAudio || isVideo) {
			if (!messageFormat.supportsChunkedAttachments()) {
				throw new ChunkedAttachmentsNotSupportedException(contentType);
			}
			return storeMediaAttachmentStreaming(uri, contentType);
		}

		return storeImageAttachment(uri, contentType);
	}

	@IoExecutor
	private AttachmentHeader storeMediaAttachmentStreaming(Uri uri, String contentType)
			throws IOException, DbException {
		long fileSize = getFileSize(uri);
		if (fileSize <= 0) {
			throw new IOException("Could not determine file size");
		}
		if (fileSize > MAX_ATTACHMENT_SIZE) {
			throw new org.zerionproject.app.api.attachment.FileTooBigException();
		}

		File strippedFile = null;
		InputStream is;
		try {
			if (remuxes(contentType) || isIsoMediaContent(uri)) {
				boolean video = contentType.startsWith("video/");
				MetadataStripper.Remuxed remuxed;
				try {
					remuxed = metadataStripper.remuxForSending(uri,
							contentResolver, video);
				} catch (IOException e) {
					throw MediaRefusedException.cannotClean(e);
				}
				strippedFile = remuxed.getFile();
				contentType = sentType(remuxed);
				fileSize = strippedFile.length();
				is = new FileInputStream(strippedFile);
			} else {
				byte[] audio = cleanAudio(readAtMost(uri, MAX_ATTACHMENT_SIZE));
				fileSize = audio.length;
				is = new ByteArrayInputStream(audio);
			}
		} catch (SecurityException e) {
			throw new IOException(e);
		} catch (IOException e) {
			if (strippedFile != null) {
				com.professor.zerion.android.vault.utils.SecureMemory
						.secureDeleteFile(strippedFile);
			}
			throw e;
		}

		long timestamp = System.currentTimeMillis();

		MessagingManager.ProgressCallback progressCallback = progress -> {
			AttachmentCreator creator = this.attachmentCreator;
			if (creator != null) {
				creator.onAttachmentProgress(uri, progress);
			}
		};

		try {
			return messagingManager.addLocalAttachmentStreaming(
					groupId, timestamp, contentType, is, fileSize, progressCallback);
		} finally {
			tryToClose(is);
			if (strippedFile != null) {
				com.professor.zerion.android.vault.utils.SecureMemory
						.secureDeleteFile(strippedFile);
			}
		}
	}

	@IoExecutor
	private AttachmentHeader storeDocument(Uri uri, String type)
			throws IOException, DbException {
		byte[] data;
		try {
			data = readAtMost(uri, MAX_ATTACHMENT_SIZE);
		} catch (SecurityException e) {
			throw new IOException(e);
		}
		if (!AttachmentDocuments.contentMatches(type, data)) {
			throw new IOException("document content does not match its type");
		}
		MessagingManager.ProgressCallback progressCallback = progress -> {
			AttachmentCreator creator = this.attachmentCreator;
			if (creator != null) {
				creator.onAttachmentProgress(uri, progress);
			}
		};
		InputStream is = new ByteArrayInputStream(data);
		try {
			return messagingManager.addLocalAttachmentStreaming(groupId,
					System.currentTimeMillis(), type, is, data.length,
					progressCallback);
		} finally {
			tryToClose(is);
		}
	}

	@IoExecutor
	private AttachmentHeader storeImageAttachment(Uri uri, String contentType)
			throws IOException, DbException {
		InputStream is;
		try {
			is = contentResolver.openInputStream(uri);
			if (is == null) throw new IOException("Could not open input stream");
		} catch (SecurityException e) {
			throw new IOException(e);
		}

		is = imageCompressor.compressImage(is, contentType);

		long timestamp = System.currentTimeMillis();
		AttachmentHeader h = messagingManager.addLocalAttachment(groupId,
				timestamp, MIME_TYPE, is);
		tryToClose(is);
		return h;
	}

	static byte[] cleanAudio(byte[] audio) throws IOException {
		byte[] clean;
		try {
			clean = SharedMediaSanitizer.cleanStream(
					AudioTagStripper.withoutTags(audio));
		} catch (IOException e) {
			throw MediaRefusedException.cannotClean(e);
		}
		boolean stream = MediaMagic.isMpegAudioFrame(clean, 0)
				|| startsWith(clean, "OggS") || startsWith(clean, "ADIF")
				|| startsWith(clean, "fLaC");
		if (!stream) {
			throw MediaRefusedException.cannotClean(
					"audio content not recognised");
		}
		return clean;
	}

	static boolean remuxes(String contentType) {
		return contentType.startsWith("video/")
				|| ISO_MEDIA_AUDIO_TYPES.contains(contentType);
	}

	static String sentType(MetadataStripper.Remuxed remuxed)
			throws MediaRefusedException {
		if (remuxed.hasVideo()) return remuxed.getMimeType();
		if (remuxed.isWebm()) {
			com.professor.zerion.android.vault.utils.SecureMemory
					.secureDeleteFile(remuxed.getFile());
			throw MediaRefusedException.cannotClean("audio only in webm");
		}
		return "audio/mp4";
	}

	private static boolean startsWith(byte[] d, String magic) {
		if (d.length < magic.length()) return false;
		for (int i = 0; i < magic.length(); i++) {
			if (d[i] != (byte) magic.charAt(i)) return false;
		}
		return true;
	}

	private boolean isIsoMediaContent(Uri uri) throws IOException {
		try (InputStream in = contentResolver.openInputStream(uri)) {
			if (in == null) throw new IOException("Could not open input stream");
			byte[] head = new byte[12];
			int read = 0;
			while (read < head.length) {
				int r = in.read(head, read, head.length - read);
				if (r < 0) break;
				read += r;
			}
			return read == head.length && MediaMagic.isIsoMedia(head);
		}
	}

	private byte[] readAtMost(Uri uri, long max) throws IOException {
		try (InputStream in = contentResolver.openInputStream(uri)) {
			if (in == null) throw new IOException("Could not open input stream");
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) != -1) {
				out.write(buf, 0, n);
				if (out.size() > max) {
					throw new org.zerionproject.app.api.attachment
							.FileTooBigException();
				}
			}
			return out.toByteArray();
		}
	}

	private long getFileSize(Uri uri) {
		long size = -1;
		Cursor cursor = null;
		try {
			cursor = contentResolver.query(uri, null, null, null, null);
			if (cursor != null && cursor.moveToFirst()) {
				int sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE);
				if (sizeIndex != -1 && !cursor.isNull(sizeIndex)) {
					size = cursor.getLong(sizeIndex);
				}
			}
		} catch (Exception e) {
		} finally {
			if (cursor != null) {
				cursor.close();
			}
		}
		return size;
	}

	@Nullable
	private String getMimeTypeFromExtension(Uri uri) {
		String path = uri.getPath();
		if (path == null) return null;
		int dotIndex = path.lastIndexOf('.');
		if (dotIndex == -1) return null;
		String extension = path.substring(dotIndex + 1).toLowerCase();
		return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
	}

}

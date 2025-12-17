package com.professor.zerion.android.attachment;

import android.content.ContentResolver;
import android.net.Uri;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.sync.GroupId;
import com.professor.zerion.android.attachment.media.ImageCompressor;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import java.util.logging.Logger;

import androidx.annotation.Nullable;

import static java.util.Arrays.asList;
import static java.util.logging.Level.WARNING;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.AndroidUtils.getSupportedImageContentTypes;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static com.professor.zerion.android.attachment.media.ImageCompressor.MIME_TYPE;

import java.util.HashSet;
import java.util.Set;

@NotNullByDefault
class AttachmentCreationTask {

	private static final Logger LOG =
			getLogger(AttachmentCreationTask.class.getName());

	// Supported audio MIME types for voice attachments
	private static final Set<String> SUPPORTED_AUDIO_TYPES = new HashSet<>(asList(
			"audio/opus",
			"audio/ogg",
			"audio/aac",
			"audio/mp4",
			"audio/mpeg",
			"audio/3gpp",
			"audio/3gp"
	));


	private final MessagingManager messagingManager;
	private final ContentResolver contentResolver;
	private final ImageCompressor imageCompressor;
	private final GroupId groupId;
	private final Collection<Uri> uris;
	private final boolean needsSize;
	@Nullable
	private volatile AttachmentCreator attachmentCreator;

	private volatile boolean canceled = false;

	AttachmentCreationTask(MessagingManager messagingManager,
			ContentResolver contentResolver,
			AttachmentCreator attachmentCreator,
			ImageCompressor imageCompressor,
			GroupId groupId, Collection<Uri> uris, boolean needsSize) {
		this.messagingManager = messagingManager;
		this.contentResolver = contentResolver;
		this.imageCompressor = imageCompressor;
		this.groupId = groupId;
		this.uris = uris;
		this.needsSize = needsSize;
		this.attachmentCreator = attachmentCreator;
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
		} catch (DbException | IOException e) {
			AttachmentCreator attachmentCreator = this.attachmentCreator;
			if (attachmentCreator != null) {
				attachmentCreator.onAttachmentError(uri, e);
			}
			canceled = true;
		}
	}

	@IoExecutor
	private AttachmentHeader storeAttachment(Uri uri)
			throws IOException, DbException {
		String contentType = contentResolver.getType(uri);
		if (contentType == null) throw new IOException("null content type");

		// Check if it's a supported audio type
		boolean isAudio = SUPPORTED_AUDIO_TYPES.contains(contentType);
		boolean isImage = asList(getSupportedImageContentTypes()).contains(contentType);

		if (!isAudio && !isImage) {
			throw new UnsupportedMimeTypeException(contentType, uri);
		}

		InputStream is;
		try {
			is = contentResolver.openInputStream(uri);
			if (is == null) throw new IOException();
		} catch (SecurityException e) {
			throw new IOException(e);
		}

		String finalMimeType;
		if (isAudio) {
			// Audio files are stored directly without compression
			finalMimeType = contentType;
		} else {
			// Images are compressed
			is = imageCompressor.compressImage(is, contentType);
			finalMimeType = MIME_TYPE;
		}

		long timestamp = System.currentTimeMillis();
		AttachmentHeader h = messagingManager.addLocalAttachment(groupId,
				timestamp, finalMimeType, is);
		tryToClose(is, LOG, WARNING);
		return h;
	}

}

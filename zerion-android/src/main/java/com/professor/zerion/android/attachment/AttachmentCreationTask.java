package com.professor.zerion.android.attachment;

import android.content.ContentResolver;
import android.database.Cursor;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.sync.GroupId;
import com.professor.zerion.android.attachment.media.ImageCompressor;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessageFormat;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collection;
import androidx.annotation.Nullable;

import static java.util.Arrays.asList;
import static org.briarproject.bramble.util.AndroidUtils.getSupportedImageContentTypes;
import static org.briarproject.bramble.util.IoUtils.tryToClose;
import static com.professor.zerion.android.attachment.media.ImageCompressor.MIME_TYPE;
import static org.briarproject.briar.api.attachment.MediaConstants.MAX_ATTACHMENT_SIZE;

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
			GroupId groupId, Collection<Uri> uris, boolean needsSize,
			PrivateMessageFormat messageFormat) {
		this.messagingManager = messagingManager;
		this.contentResolver = contentResolver;
		this.imageCompressor = imageCompressor;
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
		String contentType = contentResolver.getType(uri);
		if (contentType == null) {
			contentType = getMimeTypeFromExtension(uri);
		}
		if (contentType == null) throw new IOException("null content type");

		boolean isAudio = SUPPORTED_AUDIO_TYPES.contains(contentType);
		boolean isVideo = SUPPORTED_VIDEO_TYPES.contains(contentType);
		boolean isImage = asList(getSupportedImageContentTypes()).contains(contentType);

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
			throw new org.briarproject.briar.api.attachment.FileTooBigException();
		}
		if (contentType != null && contentType.startsWith("video/")) {
			logSenderVideoMetadata(uri, contentType, fileSize);
		}

		InputStream is;
		try {
			is = contentResolver.openInputStream(uri);
			if (is == null) throw new IOException("Could not open input stream");
		} catch (SecurityException e) {
			throw new IOException(e);
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

	private void logSenderVideoMetadata(Uri uri, String contentType, long fileSize) {
		ParcelFileDescriptor pfd = null;
		try {
			StringBuilder info = new StringBuilder();
			String uriScheme = uri.getScheme();
			boolean isGallery = "content".equals(uriScheme);
			String tag = isGallery ? "SENDER_GALLERY" : "SENDER_FILE";

			info.append("[").append(tag).append("] ");
			info.append("uri=").append(uri.toString()).append(", ");
			info.append("contentType=").append(contentType).append(", ");
			info.append("fileSize=").append(fileSize).append("bytes, ");
			MediaMetadataRetriever retriever = new MediaMetadataRetriever();
			try {
				pfd = contentResolver.openFileDescriptor(uri, "r");
				if (pfd != null) {
					retriever.setDataSource(pfd.getFileDescriptor());
					String duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
					String bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE);
					String width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
					String height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
					String mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);

					if (width != null && height != null) {
						info.append("resolution=").append(width).append("x").append(height).append(", ");
					}
					if (duration != null) {
						info.append("duration=").append(duration).append("ms, ");
					}
					if (bitrate != null) {
						info.append("bitrate=").append(bitrate).append(", ");
					}
					if (mimeType != null) {
						info.append("extractedMime=").append(mimeType).append(", ");
					}
				}
			} finally {
				retriever.release();
			}
			MediaExtractor extractor = new MediaExtractor();
			try {
				pfd = contentResolver.openFileDescriptor(uri, "r");
				if (pfd != null) {
					extractor.setDataSource(pfd.getFileDescriptor());
					for (int i = 0; i < extractor.getTrackCount(); i++) {
						MediaFormat format = extractor.getTrackFormat(i);
						String mime = format.getString(MediaFormat.KEY_MIME);
						info.append("track").append(i).append("=").append(mime);
						if (mime != null && mime.startsWith("video/")) {
							if (format.containsKey(MediaFormat.KEY_PROFILE)) {
								info.append(" profile=").append(format.getInteger(MediaFormat.KEY_PROFILE));
							}
							if (format.containsKey(MediaFormat.KEY_LEVEL)) {
								info.append(" level=").append(format.getInteger(MediaFormat.KEY_LEVEL));
							}
							if (mime.contains("hevc") || mime.contains("hev1") || mime.contains("hvc1")) {
								info.append(" [HEVC/H.265]");
							} else if (mime.contains("avc") || mime.contains("h264")) {
								info.append(" [AVC/H.264]");
							}
						}
						info.append(", ");
					}
				}
			} finally {
				extractor.release();
			}


		} catch (Exception e) {
		} finally {
			if (pfd != null) {
				try {
					pfd.close();
				} catch (Exception ignored) {
				}
			}
		}
	}
}

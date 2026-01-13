package com.professor.zerion.android.attachment.media;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.inject.Inject;

import androidx.annotation.Nullable;

@NotNullByDefault
public class VideoThumbnailExtractor {

	private static final int THUMBNAIL_QUALITY = 80;
	private static final int MAX_THUMBNAIL_DIMENSION = 512;

	private final Context context;

	@Inject
	public VideoThumbnailExtractor(Context context) {
		this.context = context.getApplicationContext();
	}

	@Nullable
	public Bitmap extractThumbnail(Uri videoUri) {
		MediaMetadataRetriever retriever = new MediaMetadataRetriever();
		try {
			retriever.setDataSource(context, videoUri);
			Bitmap frame = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
			if (frame == null) {
				frame = retriever.getFrameAtTime();
			}
			if (frame != null) {
				return scaleBitmap(frame);
			}
		} catch (Exception e) {
			return null;
		} finally {
			try {
				retriever.release();
			} catch (Exception ignored) {
			}
		}
		return null;
	}

	@Nullable
	public InputStream extractThumbnailAsStream(Uri videoUri) throws IOException {
		Bitmap thumbnail = extractThumbnail(videoUri);
		if (thumbnail == null) {
			return null;
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		if (!thumbnail.compress(Bitmap.CompressFormat.JPEG, THUMBNAIL_QUALITY, out)) {
			thumbnail.recycle();
			throw new IOException();
		}
		thumbnail.recycle();
		return new ByteArrayInputStream(out.toByteArray());
	}

	public long extractDuration(Uri videoUri) {
		MediaMetadataRetriever retriever = new MediaMetadataRetriever();
		try {
			retriever.setDataSource(context, videoUri);
			String durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
			if (durationStr != null) {
				return Long.parseLong(durationStr);
			}
		} catch (Exception e) {
			return 0;
		} finally {
			try {
				retriever.release();
			} catch (Exception ignored) {
			}
		}
		return 0;
	}

	public Size extractDimensions(Uri videoUri) {
		MediaMetadataRetriever retriever = new MediaMetadataRetriever();
		try {
			retriever.setDataSource(context, videoUri);
			String widthStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
			String heightStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
			int width = widthStr != null ? Integer.parseInt(widthStr) : 0;
			int height = heightStr != null ? Integer.parseInt(heightStr) : 0;

			String rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
			if (rotation != null) {
				int rotationDegrees = Integer.parseInt(rotation);
				if (rotationDegrees == 90 || rotationDegrees == 270) {
					int temp = width;
					width = height;
					height = temp;
				}
			}

			return new Size(width, height, "video/mp4");
		} catch (Exception e) {
			return new Size(0, 0, "video/mp4");
		} finally {
			try {
				retriever.release();
			} catch (Exception ignored) {
			}
		}
	}

	private Bitmap scaleBitmap(Bitmap original) {
		int width = original.getWidth();
		int height = original.getHeight();

		if (width <= MAX_THUMBNAIL_DIMENSION && height <= MAX_THUMBNAIL_DIMENSION) {
			return original;
		}

		float scale = Math.min(
				(float) MAX_THUMBNAIL_DIMENSION / width,
				(float) MAX_THUMBNAIL_DIMENSION / height
		);

		int newWidth = Math.round(width * scale);
		int newHeight = Math.round(height * scale);

		Bitmap scaled = Bitmap.createScaledBitmap(original, newWidth, newHeight, true);
		if (scaled != original) {
			original.recycle();
		}
		return scaled;
	}
}

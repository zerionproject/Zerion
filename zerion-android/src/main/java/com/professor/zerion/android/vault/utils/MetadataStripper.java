package com.professor.zerion.android.vault.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

@NotNullByDefault
public class MetadataStripper {

	private static final int JPEG_QUALITY = 95;

	private final Context context;

	public MetadataStripper(Context context) {
		this.context = context.getApplicationContext();
	}

	public byte[] stripMetadata(byte[] fileData, String mimeType) {
		try {
			if (mimeType.startsWith("image/")) {
				return stripImageMetadata(fileData, mimeType);
			} else if (mimeType.startsWith("video/")) {
				return stripVideoMetadata(fileData);
			} else if (isDocument(mimeType)) {
				return stripDocumentMetadata(fileData, mimeType);
			}
		} catch (Exception e) {
		}
		return fileData;
	}

	private byte[] stripImageMetadata(byte[] imageData, String mimeType) throws IOException {
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(imageData, 0, imageData.length, options);

		int maxDimension = 4096;
		int sampleSize = calculateSampleSize(options.outWidth, options.outHeight, maxDimension);

		options.inJustDecodeBounds = false;
		options.inSampleSize = sampleSize;
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;

		Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length, options);
		if (bitmap == null) {
			return imageData;
		}

		Bitmap.CompressFormat format;
		int quality;

		if (mimeType.equals("image/png")) {
			format = Bitmap.CompressFormat.PNG;
			quality = 100;
		} else if (mimeType.equals("image/webp")) {
			format = Bitmap.CompressFormat.WEBP;
			quality = 95;
		} else {
			format = Bitmap.CompressFormat.JPEG;
			quality = JPEG_QUALITY;
		}

		byte[] strippedData;
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			bitmap.compress(format, quality, output);
			bitmap.recycle();
			strippedData = output.toByteArray();
		}

		if (mimeType.equals("image/jpeg") || mimeType.equals("image/jpg")) {
			strippedData = ensureNoExif(strippedData);
		}

		return strippedData;
	}

	private int calculateSampleSize(int width, int height, int maxDimension) {
		int sampleSize = 1;

		if (width > maxDimension || height > maxDimension) {
			int halfWidth = width / 2;
			int halfHeight = height / 2;

			while ((halfWidth / sampleSize) >= maxDimension &&
			       (halfHeight / sampleSize) >= maxDimension) {
				sampleSize *= 2;
			}
		}

		return sampleSize;
	}

	private byte[] ensureNoExif(byte[] jpegData) {
		File tempFile = null;
		try {
			tempFile = File.createTempFile("jpg_meta", ".jpg", context.getCacheDir());

			try (FileOutputStream fos = new FileOutputStream(tempFile)) {
				fos.write(jpegData);
			}

			ExifInterface exif = new ExifInterface(tempFile.getAbsolutePath());

			for (String tag : getAllExifTags()) {
				exif.setAttribute(tag, null);
			}
			exif.setAttribute(ExifInterface.TAG_GPS_PROCESSING_METHOD, null);

			exif.saveAttributes();

			try (FileInputStream fis = new FileInputStream(tempFile);
			     ByteArrayOutputStream output = new ByteArrayOutputStream()) {
				byte[] buffer = new byte[8192];
				int read;
				while ((read = fis.read(buffer)) != -1) {
					output.write(buffer, 0, read);
				}
				return output.toByteArray();
			}

		} catch (Exception e) {
			return jpegData;
		} finally {
			if (tempFile != null) {
				tempFile.delete();
			}
		}
	}

	private byte[] stripVideoMetadata(byte[] videoData) {
		// For byte[] path (vault), re-mux via temp files
		File tempIn = null;
		File tempOut = null;
		try {
			tempIn = File.createTempFile("vid_in_", ".mp4", context.getCacheDir());
			try (FileOutputStream fos = new FileOutputStream(tempIn)) {
				fos.write(videoData);
			}
			tempOut = remuxVideoFile(tempIn);
			try (FileInputStream fis = new FileInputStream(tempOut);
				 ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
				byte[] buf = new byte[8192];
				int read;
				while ((read = fis.read(buf)) != -1) {
					bos.write(buf, 0, read);
				}
				return bos.toByteArray();
			}
		} catch (IOException e) {
			return videoData;
		} finally {
			if (tempIn != null) tempIn.delete();
			if (tempOut != null) tempOut.delete();
		}
	}

	/**
	 * Strip metadata from a video at the given URI.
	 * Returns a temp file containing the clean video (caller must delete it).
	 */
	public File stripVideoMetadataFromUri(Uri uri, ContentResolver resolver)
			throws IOException {
		ParcelFileDescriptor pfd = null;
		MediaExtractor extractor = null;
		MediaMuxer muxer = null;
		File tempOutput = null;

		try {
			tempOutput = File.createTempFile("vid_clean_", ".mp4",
					context.getCacheDir());

			pfd = resolver.openFileDescriptor(uri, "r");
			if (pfd == null) throw new IOException("Cannot open video URI");

			extractor = new MediaExtractor();
			extractor.setDataSource(pfd.getFileDescriptor());

			muxer = new MediaMuxer(tempOutput.getAbsolutePath(),
					MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

			int trackCount = extractor.getTrackCount();
			int[] trackMap = new int[trackCount];
			boolean[] includeTrack = new boolean[trackCount];

			// Only include video and audio tracks — skip metadata tracks
			for (int i = 0; i < trackCount; i++) {
				MediaFormat format = extractor.getTrackFormat(i);
				String mime = format.getString(MediaFormat.KEY_MIME);
				if (mime != null && (mime.startsWith("video/")
						|| mime.startsWith("audio/"))) {
					trackMap[i] = muxer.addTrack(format);
					includeTrack[i] = true;
				}
			}

			muxer.start();

			ByteBuffer buffer = ByteBuffer.allocate(512 * 1024);
			MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

			for (int i = 0; i < trackCount; i++) {
				if (!includeTrack[i]) continue;

				extractor.selectTrack(i);
				while (true) {
					int sampleSize = extractor.readSampleData(buffer, 0);
					if (sampleSize < 0) break;

					bufferInfo.offset = 0;
					bufferInfo.size = sampleSize;
					bufferInfo.presentationTimeUs =
							extractor.getSampleTime();
					bufferInfo.flags = extractor.getSampleFlags();

					muxer.writeSampleData(trackMap[i], buffer,
							bufferInfo);
					extractor.advance();
				}
				extractor.unselectTrack(i);
			}

			muxer.stop();
			return tempOutput;

		} catch (Exception e) {
			if (tempOutput != null) tempOutput.delete();
			throw new IOException("Failed to strip video metadata", e);
		} finally {
			if (extractor != null) extractor.release();
			if (muxer != null) {
				try { muxer.release(); } catch (Exception ignored) {}
			}
			if (pfd != null) {
				try { pfd.close(); } catch (Exception ignored) {}
			}
		}
	}

	private File remuxVideoFile(File inputFile) throws IOException {
		MediaExtractor extractor = null;
		MediaMuxer muxer = null;
		File tempOutput = null;

		try {
			tempOutput = File.createTempFile("vid_remux_", ".mp4",
					context.getCacheDir());

			extractor = new MediaExtractor();
			extractor.setDataSource(inputFile.getAbsolutePath());

			muxer = new MediaMuxer(tempOutput.getAbsolutePath(),
					MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

			int trackCount = extractor.getTrackCount();
			int[] trackMap = new int[trackCount];
			boolean[] includeTrack = new boolean[trackCount];

			for (int i = 0; i < trackCount; i++) {
				MediaFormat format = extractor.getTrackFormat(i);
				String mime = format.getString(MediaFormat.KEY_MIME);
				if (mime != null && (mime.startsWith("video/")
						|| mime.startsWith("audio/"))) {
					trackMap[i] = muxer.addTrack(format);
					includeTrack[i] = true;
				}
			}

			muxer.start();

			ByteBuffer buffer = ByteBuffer.allocate(512 * 1024);
			MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

			for (int i = 0; i < trackCount; i++) {
				if (!includeTrack[i]) continue;

				extractor.selectTrack(i);
				while (true) {
					int sampleSize = extractor.readSampleData(buffer, 0);
					if (sampleSize < 0) break;

					bufferInfo.offset = 0;
					bufferInfo.size = sampleSize;
					bufferInfo.presentationTimeUs =
							extractor.getSampleTime();
					bufferInfo.flags = extractor.getSampleFlags();

					muxer.writeSampleData(trackMap[i], buffer,
							bufferInfo);
					extractor.advance();
				}
				extractor.unselectTrack(i);
			}

			muxer.stop();
			return tempOutput;

		} catch (Exception e) {
			if (tempOutput != null) tempOutput.delete();
			throw new IOException("Failed to remux video", e);
		} finally {
			if (extractor != null) extractor.release();
			if (muxer != null) {
				try { muxer.release(); } catch (Exception ignored) {}
			}
		}
	}

	private byte[] stripDocumentMetadata(byte[] documentData, String mimeType) {

		if (mimeType.equals("application/pdf")) {
			return stripPdfMetadata(documentData);
		} else if (mimeType.contains("officedocument") || mimeType.contains("msword")) {
			return stripOfficeMetadata(documentData);
		}

		return documentData;
	}

	private byte[] stripPdfMetadata(byte[] pdfData) {

		try {
			String pdfString = new String(pdfData, "ISO-8859-1");

			pdfString = pdfString.replaceAll("/Producer\\s*\\([^)]*\\)", "/Producer ()");
			pdfString = pdfString.replaceAll("/Creator\\s*\\([^)]*\\)", "/Creator ()");
			pdfString = pdfString.replaceAll("/Author\\s*\\([^)]*\\)", "/Author ()");
			pdfString = pdfString.replaceAll("/Title\\s*\\([^)]*\\)", "/Title ()");
			pdfString = pdfString.replaceAll("/Subject\\s*\\([^)]*\\)", "/Subject ()");
			pdfString = pdfString.replaceAll("/Keywords\\s*\\([^)]*\\)", "/Keywords ()");
			pdfString = pdfString.replaceAll("/CreationDate\\s*\\([^)]*\\)", "/CreationDate ()");
			pdfString = pdfString.replaceAll("/ModDate\\s*\\([^)]*\\)", "/ModDate ()");
			pdfString = pdfString.replaceAll("/Trapped\\s*/\\w+", "/Trapped /False");

			pdfString = pdfString.replaceAll("<x:xmpmeta[^>]*>.*?</x:xmpmeta>", "");
			pdfString = pdfString.replaceAll("<\\?xpacket.*?\\?>", "");

			pdfString = pdfString.replaceAll("/Info\\s+\\d+\\s+\\d+\\s+R", "");

			pdfString = pdfString.replaceAll("/Type\\s*/Metadata[^>]*>>stream.*?endstream", "");

			byte[] cleaned = pdfString.getBytes("ISO-8859-1");
			return cleaned;

		} catch (Exception e) {
			return pdfData;
		}
	}

	private byte[] stripOfficeMetadata(byte[] officeData) {
		throw new UnsupportedOperationException(
				"Office document metadata stripping is not supported. " +
				"Office documents are stored as-is in the vault.");
	}

	private boolean isDocument(String mimeType) {
		return mimeType.startsWith("application/pdf") ||
				mimeType.contains("document") ||
				mimeType.contains("msword") ||
				mimeType.contains("ms-excel") ||
				mimeType.contains("ms-powerpoint") ||
				mimeType.startsWith("text/");
	}

	public static void stripExifFromFile(File imageFile) throws IOException {
		if (!imageFile.exists()) return;

		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);

		int maxDimension = 4096;
		int sampleSize = 1;

		if (options.outWidth > maxDimension || options.outHeight > maxDimension) {
			int halfWidth = options.outWidth / 2;
			int halfHeight = options.outHeight / 2;

			while ((halfWidth / sampleSize) >= maxDimension &&
			       (halfHeight / sampleSize) >= maxDimension) {
				sampleSize *= 2;
			}
		}

		options.inJustDecodeBounds = false;
		options.inSampleSize = sampleSize;

		Bitmap bitmap = BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);
		if (bitmap == null) return;

		try {
			try (FileOutputStream fos = new FileOutputStream(imageFile)) {
				bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, fos);
			}
		} finally {
			bitmap.recycle();
		}

		try {
			ExifInterface exif = new ExifInterface(imageFile.getAbsolutePath());
			for (String tag : getAllExifTags()) {
				exif.setAttribute(tag, null);
			}
			exif.saveAttributes();
		} catch (IOException e) {
		}
	}

	private static String[] getAllExifTags() {
		return new String[] {
			ExifInterface.TAG_DATETIME,
			ExifInterface.TAG_DATETIME_DIGITIZED,
			ExifInterface.TAG_DATETIME_ORIGINAL,
			ExifInterface.TAG_GPS_ALTITUDE,
			ExifInterface.TAG_GPS_ALTITUDE_REF,
			ExifInterface.TAG_GPS_LATITUDE,
			ExifInterface.TAG_GPS_LATITUDE_REF,
			ExifInterface.TAG_GPS_LONGITUDE,
			ExifInterface.TAG_GPS_LONGITUDE_REF,
			ExifInterface.TAG_GPS_TIMESTAMP,
			ExifInterface.TAG_GPS_DATESTAMP,
			ExifInterface.TAG_MAKE,
			ExifInterface.TAG_MODEL,
			ExifInterface.TAG_SOFTWARE,
			ExifInterface.TAG_ARTIST,
			ExifInterface.TAG_COPYRIGHT,
			ExifInterface.TAG_EXIF_VERSION,
			ExifInterface.TAG_USER_COMMENT,
			ExifInterface.TAG_IMAGE_DESCRIPTION,
			ExifInterface.TAG_MAKER_NOTE
		};
	}
}
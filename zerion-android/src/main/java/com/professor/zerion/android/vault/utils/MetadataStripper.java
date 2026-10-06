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
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
public class MetadataStripper {

	private static final int JPEG_QUALITY = 95;

	static final int MAX_SAMPLE_BYTES = 16 * 1024 * 1024;

	static final int MP4 = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;
	static final int WEBM = MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;

	static final String VP8 = "video/x-vnd.on2.vp8";
	static final String VP9 = "video/x-vnd.on2.vp9";
	static final String VORBIS = "audio/vorbis";
	static final String OPUS = "audio/opus";

	private static final Set<String> WEBM_CODECS =
			new HashSet<>(Arrays.asList(VP8, VP9, VORBIS, OPUS));

	private static final Set<String> MP4_CANNOT_CARRY =
			new HashSet<>(Arrays.asList(VP8, VORBIS));

	private static final int VORBIS_PAGE_SAMPLES = 4;

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
		} catch (Exception | OutOfMemoryError e) {
		}
		return fileData;
	}

	public byte[] stripImageMetadataOrThrow(byte[] imageData, String mimeType)
			throws IOException {
		try {
			return stripImageMetadata(imageData, mimeType, true);
		} catch (OutOfMemoryError e) {
			throw com.professor.zerion.android.attachment.MediaRefusedException
					.tooLarge("image too large to clean");
		}
	}

	public static String strippedImageMimeType(String mimeType) {
		if (mimeType.equals("image/png")) return "image/png";
		if (mimeType.equals("image/webp")) return "image/webp";
		return "image/jpeg";
	}

	private byte[] stripImageMetadata(byte[] imageData, String mimeType) throws IOException {
		return stripImageMetadata(imageData, mimeType, false);
	}

	private byte[] stripImageMetadata(byte[] imageData, String mimeType,
			boolean bounded) throws IOException {
		boolean known = com.professor.zerion.android.util
				.SafeImageDecoder.hasAllowedMagic(imageData);
		if (!known && !(bounded && isReencodableForSending(imageData))) {
			throw new IOException("unsupported image format");
		}
		BitmapFactory.Options bounds = bounded ? probeDimensions(imageData)
				: com.professor.zerion.android.util.SafeImageDecoder
						.probeBounds(imageData);
		if (bounds == null) {
			throw new IOException("unsupported image format");
		}

		int maxDimension = 4096;
		int sampleSize = bounded
				? boundedSampleSize(bounds.outWidth, bounds.outHeight,
						maxDimension)
				: calculateSampleSize(bounds.outWidth, bounds.outHeight,
						maxDimension);

		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = false;
		options.inSampleSize = sampleSize;
		options.inPreferredConfig = Bitmap.Config.ARGB_8888;

		Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length, options);
		if (bitmap == null) {
			throw new IOException("undecodable image");
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
			boolean encoded = bitmap.compress(format, quality, output);
			bitmap.recycle();
			if (!encoded) throw new IOException("image re-encode failed");
			strippedData = output.toByteArray();
		}

		if (mimeType.equals("image/jpeg") || mimeType.equals("image/jpg")) {
			strippedData = ensureNoExif(strippedData);
		}

		return strippedData;
	}

	public static boolean isReencodableForSending(byte[] data) {
		return com.professor.zerion.android.util.MediaMagic.isBmp(data)
				|| com.professor.zerion.android.util.MediaMagic.isAvif(data);
	}

	@Nullable
	private static BitmapFactory.Options probeDimensions(byte[] data) {
		BitmapFactory.Options bounds = new BitmapFactory.Options();
		bounds.inJustDecodeBounds = true;
		BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
		if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
		int max = com.professor.zerion.android.util.SafeImageDecoder
				.MAX_DIMENSION;
		if (bounds.outWidth > max || bounds.outHeight > max) return null;
		return bounds;
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

	static int boundedSampleSize(int width, int height, int maxDimension) {
		int sampleSize = 1;
		while (width / sampleSize > maxDimension
				|| height / sampleSize > maxDimension) {
			sampleSize *= 2;
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
				secureDelete(tempFile);
			}
		}
	}

	private static void secureDelete(File f) {
		SecureMemory.secureDeleteFile(f, 512L * 1024 * 1024, true);
	}

	private byte[] stripVideoMetadata(byte[] videoData) {

		File tempIn = null;
		File tempOut = null;
		try {
			tempIn = File.createTempFile("vid_in_", ".mp4", context.getCacheDir());
			try (FileOutputStream fos = new FileOutputStream(tempIn)) {
				fos.write(videoData);
			}
			tempOut = remux(fileSource(tempIn), "vid_remux_", false).getFile();
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
			if (tempIn != null) secureDelete(tempIn);
			if (tempOut != null) secureDelete(tempOut);
		}
	}

	public File stripVideoMetadataFromUri(Uri uri, ContentResolver resolver)
			throws IOException {
		return remuxForSending(uri, resolver, false).getFile();
	}

	public Remuxed remuxForSending(Uri uri, ContentResolver resolver,
			boolean allowWebm) throws IOException {
		return remux(extractor -> {
			ParcelFileDescriptor pfd;
			try {
				pfd = resolver.openFileDescriptor(uri, "r");
			} catch (SecurityException e) {
				throw new IOException(e);
			}
			if (pfd == null) throw new IOException("Cannot open video URI");
			try {
				extractor.setDataSource(pfd.getFileDescriptor());
			} catch (IOException | RuntimeException e) {
				pfd.close();
				throw e;
			}
			return pfd;
		}, "vid_clean_", allowWebm);
	}

	public static final class Remuxed {

		private final File file;
		private final boolean webm;
		private final boolean video;

		Remuxed(File file, boolean webm, boolean video) {
			this.file = file;
			this.webm = webm;
			this.video = video;
		}

		public File getFile() {
			return file;
		}

		public boolean isWebm() {
			return webm;
		}

		public boolean hasVideo() {
			return video;
		}

		public String getMimeType() {
			if (webm) return video ? "video/webm" : "audio/webm";
			return video ? "video/mp4" : "audio/mp4";
		}
	}

	private interface Source {

		Closeable open(MediaExtractor extractor) throws IOException;
	}

	private static Source fileSource(File file) {
		return extractor -> {
			extractor.setDataSource(file.getAbsolutePath());
			return () -> {
			};
		};
	}

	private Remuxed remux(Source source, String prefix, boolean allowWebm)
			throws IOException {
		List<String> mimes;
		try {
			mimes = keptTrackTypes(source);
		} catch (Exception | OutOfMemoryError e) {
			throw new IOException("Failed to read media tracks", e);
		}
		int[] containers = containersFor(mimes, allowWebm);
		if (containers.length == 0) {
			throw new IOException("No container can carry these tracks");
		}
		boolean video = hasVideo(mimes);
		Throwable last = null;
		for (int container : containers) {
			boolean webm = container == WEBM;
			int[] trims = webm && mimes.contains(VORBIS)
					? new int[] {VORBIS_PAGE_SAMPLES, 0} : new int[] {0};
			for (int trim : trims) {
				File out = null;
				try {
					out = File.createTempFile(prefix, webm ? ".webm" : ".mp4",
							context.getCacheDir());
					mux(source, container, out, trim);
					if (webm) {
						requireSameSamples(source, out);
					} else {
						Mp4MetadataScrubber.scrub(out);
					}
					return new Remuxed(out, webm, video);
				} catch (Exception | OutOfMemoryError e) {
					if (out != null) secureDelete(out);
					last = e;
				}
			}
		}
		throw new IOException("Failed to strip video metadata", last);
	}

	private static void mux(Source source, int container, File out,
			int vorbisTrim) throws IOException {
		MediaExtractor extractor = new MediaExtractor();
		MediaMuxer muxer = null;
		Closeable handle = null;
		try {
			handle = source.open(extractor);
			muxer = new MediaMuxer(out.getAbsolutePath(), container);
			int trackCount = extractor.getTrackCount();
			int[] trackMap = new int[trackCount];
			boolean[] includeTrack = new boolean[trackCount];
			int[] trim = new int[trackCount];
			for (int i = 0; i < trackCount; i++) {
				MediaFormat format = extractor.getTrackFormat(i);
				String mime = format.getString(MediaFormat.KEY_MIME);
				if (isKept(mime)) {
					trackMap[i] = muxer.addTrack(format);
					includeTrack[i] = true;
					if (VORBIS.equals(mime)) trim[i] = vorbisTrim;
				}
			}
			muxer.start();
			if (container == WEBM) {
				copyInterleaved(extractor, muxer, trackMap, includeTrack, trim);
			} else {
				copyTrackByTrack(extractor, muxer, trackMap, includeTrack);
			}
			muxer.stop();
		} finally {
			extractor.release();
			if (muxer != null) {
				try { muxer.release(); } catch (Exception ignored) {}
			}
			if (handle != null) {
				try { handle.close(); } catch (Exception ignored) {}
			}
		}
	}

	private static void copyTrackByTrack(MediaExtractor extractor,
			MediaMuxer muxer, int[] trackMap, boolean[] includeTrack)
			throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(512 * 1024);
		MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
		for (int i = 0; i < trackMap.length; i++) {
			if (!includeTrack[i]) continue;
			extractor.selectTrack(i);
			while (true) {
				buffer = sampleBuffer(buffer, extractor.getSampleSize());
				int sampleSize = extractor.readSampleData(buffer, 0);
				if (sampleSize < 0) break;
				bufferInfo.offset = 0;
				bufferInfo.size = sampleSize;
				bufferInfo.presentationTimeUs = extractor.getSampleTime();
				bufferInfo.flags = extractor.getSampleFlags();
				muxer.writeSampleData(trackMap[i], buffer, bufferInfo);
				extractor.advance();
			}
			extractor.unselectTrack(i);
		}
	}

	private static void copyInterleaved(MediaExtractor extractor,
			MediaMuxer muxer, int[] trackMap, boolean[] includeTrack,
			int[] trim) throws IOException {
		for (int i = 0; i < trackMap.length; i++) {
			if (includeTrack[i]) extractor.selectTrack(i);
		}
		ByteBuffer buffer = ByteBuffer.allocate(512 * 1024);
		MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
		while (true) {
			int track = extractor.getSampleTrackIndex();
			if (track < 0) break;
			buffer = sampleBuffer(buffer, extractor.getSampleSize());
			int sampleSize = extractor.readSampleData(buffer, 0);
			if (sampleSize < 0) break;
			int size = sampleSize - trim[track];
			if (size <= 0) throw new IOException("empty sample");
			bufferInfo.offset = 0;
			bufferInfo.size = size;
			bufferInfo.presentationTimeUs = extractor.getSampleTime();
			bufferInfo.flags = extractor.getSampleFlags();
			muxer.writeSampleData(trackMap[track], buffer, bufferInfo);
			extractor.advance();
		}
	}

	private static void requireSameSamples(Source source, File out)
			throws IOException {
		List<String> in = sampleCounts(source);
		List<String> written = sampleCounts(fileSource(out));
		if (in.isEmpty() || !in.equals(written)) {
			throw new IOException("remuxed samples do not match");
		}
	}

	private static List<String> sampleCounts(Source source)
			throws IOException {
		MediaExtractor extractor = new MediaExtractor();
		Closeable handle = null;
		try {
			handle = source.open(extractor);
			int trackCount = extractor.getTrackCount();
			String[] types = new String[trackCount];
			long[] counts = new long[trackCount];
			long[] bytes = new long[trackCount];
			for (int i = 0; i < trackCount; i++) {
				String mime = extractor.getTrackFormat(i)
						.getString(MediaFormat.KEY_MIME);
				if (isKept(mime)) {
					types[i] = mime;
					extractor.selectTrack(i);
				}
			}
			while (true) {
				int track = extractor.getSampleTrackIndex();
				if (track < 0) break;
				long size = extractor.getSampleSize();
				if (size < 0) break;
				counts[track]++;
				bytes[track] += size;
				extractor.advance();
			}
			List<String> out = new ArrayList<>();
			for (int i = 0; i < trackCount; i++) {
				if (types[i] != null) {
					out.add(types[i] + " " + counts[i] + " " + bytes[i]);
				}
			}
			Collections.sort(out);
			return out;
		} finally {
			extractor.release();
			if (handle != null) {
				try { handle.close(); } catch (Exception ignored) {}
			}
		}
	}

	private static List<String> keptTrackTypes(Source source)
			throws IOException {
		MediaExtractor extractor = new MediaExtractor();
		Closeable handle = null;
		try {
			handle = source.open(extractor);
			List<String> mimes = new ArrayList<>();
			for (int i = 0; i < extractor.getTrackCount(); i++) {
				String mime = extractor.getTrackFormat(i)
						.getString(MediaFormat.KEY_MIME);
				if (isKept(mime)) mimes.add(mime);
			}
			return mimes;
		} finally {
			extractor.release();
			if (handle != null) {
				try { handle.close(); } catch (Exception ignored) {}
			}
		}
	}

	private static boolean isKept(@Nullable String mime) {
		return mime != null && (mime.startsWith("video/")
				|| mime.startsWith("audio/"));
	}

	static boolean hasVideo(List<String> mimes) {
		for (String m : mimes) {
			if (m.startsWith("video/")) return true;
		}
		return false;
	}

	static int[] containersFor(List<String> mimes, boolean allowWebm) {
		if (mimes.isEmpty()) return new int[0];
		boolean needsWebm = false;
		boolean webmCodecs = true;
		int videos = 0;
		int audios = 0;
		for (String m : mimes) {
			if (MP4_CANNOT_CARRY.contains(m)) needsWebm = true;
			if (!WEBM_CODECS.contains(m)) webmCodecs = false;
			if (m.startsWith("video/")) videos++;
			else audios++;
		}
		boolean webm = allowWebm && webmCodecs && videos <= 1 && audios <= 1;
		if (needsWebm) return webm ? new int[] {WEBM} : new int[0];
		return webm ? new int[] {MP4, WEBM} : new int[] {MP4};
	}

	static ByteBuffer sampleBuffer(ByteBuffer buffer, long sampleSize)
			throws IOException {
		if (sampleSize <= buffer.capacity()) return buffer;
		if (sampleSize > MAX_SAMPLE_BYTES) {
			throw new IOException("media sample too large");
		}
		return ByteBuffer.allocate((int) sampleSize);
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
		if (officeData.length < 4 || officeData[0] != 'P' || officeData[1]
				!= 'K') {
			return officeData;
		}
		try (java.io.ByteArrayInputStream bin =
					new java.io.ByteArrayInputStream(officeData);
				java.util.zip.ZipInputStream zin =
						new java.util.zip.ZipInputStream(bin);
				java.io.ByteArrayOutputStream bout =
						new java.io.ByteArrayOutputStream(officeData.length);
				java.util.zip.ZipOutputStream zout =
						new java.util.zip.ZipOutputStream(bout)) {
			byte[] buf = new byte[8192];
			java.util.zip.ZipEntry e;
			while ((e = zin.getNextEntry()) != null) {
				String n = e.getName();
				if (n.startsWith("docProps/") || n.endsWith("/core.xml")
						|| n.endsWith("/app.xml")
						|| n.endsWith("/custom.xml")) {
					continue;
				}
				java.util.zip.ZipEntry out =
						new java.util.zip.ZipEntry(n);
				zout.putNextEntry(out);
				int read;
				while ((read = zin.read(buf)) > 0) zout.write(buf, 0, read);
				zout.closeEntry();
			}
			zout.finish();
			return bout.toByteArray();
		} catch (Exception ex) {
			return officeData;
		}
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

		byte[] head = new byte[12];
		try (java.io.FileInputStream fis = new java.io.FileInputStream(imageFile)) {
			int read = 0;
			while (read < head.length) {
				int r = fis.read(head, read, head.length - read);
				if (r < 0) break;
				read += r;
			}
			if (read < 12 || !com.professor.zerion.android.util
					.SafeImageDecoder.hasAllowedMagic(head)) {
				throw new IOException("unsupported image format");
			}
		}

		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		BitmapFactory.decodeFile(imageFile.getAbsolutePath(), options);

		if (options.outWidth <= 0 || options.outHeight <= 0
				|| options.outWidth > com.professor.zerion.android.util
						.SafeImageDecoder.MAX_DIMENSION
				|| options.outHeight > com.professor.zerion.android.util
						.SafeImageDecoder.MAX_DIMENSION) {
			throw new IOException("unsupported image dimensions");
		}

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
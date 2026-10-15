package com.professor.zerion.android.vault.utils;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
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

	static final String[] MUXED_KEYS = {MediaFormat.KEY_MIME, "csd-0",
			"csd-1", "csd-2", MediaFormat.KEY_WIDTH, MediaFormat.KEY_HEIGHT,
			MediaFormat.KEY_ROTATION, MediaFormat.KEY_SAMPLE_RATE,
			MediaFormat.KEY_CHANNEL_COUNT, MediaFormat.KEY_DURATION,
			MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL};

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
		options.inPreferredColorSpace = android.graphics.ColorSpace.get(
				android.graphics.ColorSpace.Named.SRGB);

		Bitmap bitmap = BitmapFactory.decodeByteArray(imageData, 0, imageData.length, options);
		if (bitmap == null) {
			throw new IOException("undecodable image");
		}
		bitmap = com.professor.zerion.android.attachment.ImageOrientation
				.upright(bitmap, com.professor.zerion.android.attachment
						.ImageOrientation.of(imageData));

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

		byte[] encodedData;
		try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
			boolean encoded = bitmap.compress(format, quality, output);
			bitmap.recycle();
			if (!encoded) throw new IOException("image re-encode failed");
			encodedData = output.toByteArray();
		}

		return com.professor.zerion.android.attachment.ImageScrubber
				.scrub(encodedData);
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

	private static void secureDelete(File f) {
		SecureMemory.secureDeleteFile(f, 512L * 1024 * 1024, true);
	}

	public static void discard(File f) {
		secureDelete(f);
		com.professor.zerion.android.util.CacheSweeper.release(f);
	}

	private byte[] stripVideoMetadata(byte[] videoData) {

		File tempIn = null;
		File tempOut = null;
		try {
			tempIn = com.professor.zerion.android.util.CacheSweeper.claim(
					File.createTempFile("vid_in_", ".mp4",
							context.getCacheDir()));
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
			if (tempIn != null) discard(tempIn);
			if (tempOut != null) discard(tempOut);
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
					out = com.professor.zerion.android.util.CacheSweeper.claim(
							File.createTempFile(prefix, webm ? ".webm" : ".mp4",
									context.getCacheDir()));
					mux(source, container, out, trim);
					if (webm) {
						WebmMetadataScrubber.scrub(out);
						requireSameSamples(source, out);
					} else {
						Mp4MetadataScrubber.scrub(out);
					}
					return new Remuxed(out, webm, video);
				} catch (Exception | OutOfMemoryError e) {
					if (out != null) discard(out);
					last = e;
				}
			}
		}
		throw new IOException("Failed to strip video metadata", last);
	}

	static int rotationOf(MediaFormat format) {
		if (!format.containsKey(MediaFormat.KEY_ROTATION)) return 0;
		int degrees = format.getInteger(MediaFormat.KEY_ROTATION);
		degrees = ((degrees % 360) + 360) % 360;
		return degrees % 90 == 0 ? degrees : 0;
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
			int rotation = 0;
			for (int i = 0; i < trackCount; i++) {
				MediaFormat format = extractor.getTrackFormat(i);
				String mime = format.getString(MediaFormat.KEY_MIME);
				if (isKept(mime)) {
					trackMap[i] = muxer.addTrack(muxedFormat(format));
					includeTrack[i] = true;
					if (VORBIS.equals(mime)) trim[i] = vorbisTrim;
					if (rotation == 0 && mime.startsWith("video/")) {
						rotation = rotationOf(format);
					}
				}
			}
			if (rotation != 0) {
				if (container == WEBM) {
					throw new IOException("WebM cannot keep the rotation");
				}
				muxer.setOrientationHint(rotation);
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

	static ByteBuffer setupWithoutUserData(@Nullable String mime,
			ByteBuffer setup) {
		ByteBuffer view = setup.duplicate();
		byte[] bytes = new byte[view.remaining()];
		view.get(bytes);
		int kept = SeiUserDataFilter.filter(mime, bytes, 0, bytes.length);
		if (kept == bytes.length) return setup;
		return ByteBuffer.wrap(java.util.Arrays.copyOf(bytes, kept));
	}

	static MediaFormat muxedFormat(MediaFormat in) {
		MediaFormat out = new MediaFormat();
		String mime = in.containsKey(MediaFormat.KEY_MIME)
				? in.getString(MediaFormat.KEY_MIME) : null;
		for (String key : MUXED_KEYS) {
			if (!in.containsKey(key)) continue;
			switch (in.getValueTypeForKey(key)) {
				case MediaFormat.TYPE_INTEGER:
					out.setInteger(key, in.getInteger(key));
					break;
				case MediaFormat.TYPE_LONG:
					out.setLong(key, in.getLong(key));
					break;
				case MediaFormat.TYPE_STRING:
					out.setString(key, in.getString(key));
					break;
				case MediaFormat.TYPE_BYTE_BUFFER:
					out.setByteBuffer(key,
							setupWithoutUserData(mime, in.getByteBuffer(key)));
					break;
				default:
					break;
			}
		}
		return out;
	}

	private static void copyTrackByTrack(MediaExtractor extractor,
			MediaMuxer muxer, int[] trackMap, boolean[] includeTrack)
			throws IOException {
		ByteBuffer buffer = ByteBuffer.allocate(512 * 1024);
		MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
		for (int i = 0; i < trackMap.length; i++) {
			if (!includeTrack[i]) continue;
			String mime = extractor.getTrackFormat(i)
					.getString(MediaFormat.KEY_MIME);
			extractor.selectTrack(i);
			while (true) {
				buffer = sampleBuffer(buffer, extractor.getSampleSize());
				int sampleSize = extractor.readSampleData(buffer, 0);
				if (sampleSize < 0) break;
				bufferInfo.offset = 0;
				bufferInfo.size = SeiUserDataFilter.filter(mime, buffer,
						sampleSize);
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
		String[] mimes = new String[trackMap.length];
		for (int i = 0; i < trackMap.length; i++) {
			if (!includeTrack[i]) continue;
			mimes[i] = extractor.getTrackFormat(i)
					.getString(MediaFormat.KEY_MIME);
			extractor.selectTrack(i);
		}
		ByteBuffer buffer = ByteBuffer.allocate(512 * 1024);
		MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
		while (true) {
			int track = extractor.getSampleTrackIndex();
			if (track < 0) break;
			buffer = sampleBuffer(buffer, extractor.getSampleSize());
			int sampleSize = extractor.readSampleData(buffer, 0);
			if (sampleSize < 0) break;
			int size = SeiUserDataFilter.filter(mimes[track], buffer,
					sampleSize - trim[track]);
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
}
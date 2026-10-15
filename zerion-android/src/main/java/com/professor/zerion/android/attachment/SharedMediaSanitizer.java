package com.professor.zerion.android.attachment;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.professor.zerion.android.util.CacheSweeper;
import com.professor.zerion.android.util.MediaMagic;
import com.professor.zerion.android.util.SafeImageDecoder;
import com.professor.zerion.android.vault.utils.MetadataStripper;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

import javax.annotation.Nullable;

@NotNullByDefault
public class SharedMediaSanitizer {

	public static final class Cleaned {

		private final String mimeType;
		private final byte[] data;

		Cleaned(String mimeType, byte[] data) {
			this.mimeType = mimeType;
			this.data = data;
		}

		public String getMimeType() {
			return mimeType;
		}

		public byte[] getData() {
			return data;
		}
	}

	private static final int HIDDEN_CONTAINER_WINDOW = 4096;

	private static final String WAV = "audio/wav";

	private final Context context;
	private final MetadataStripper stripper;
	private final ContentResolver resolver;

	public SharedMediaSanitizer(Context context) {
		this.context = context.getApplicationContext();
		this.stripper = new MetadataStripper(context);
		this.resolver = this.context.getContentResolver();
	}

	public Cleaned sanitize(Uri source, String declaredMime, byte[] data,
			long maxBytes) throws IOException {
		try {
			return clean(source, declaredMime, data, maxBytes);
		} catch (OutOfMemoryError e) {
			throw MediaRefusedException.tooLarge("media too large to clean");
		}
	}

	public Cleaned sanitize(String declaredMime, byte[] data, long maxBytes)
			throws IOException {
		if (!needsRemux(declaredMime, data)) {
			return sanitize(Uri.EMPTY, declaredMime, data, maxBytes);
		}
		File staged = CacheSweeper.claim(File.createTempFile("vid_in_", null,
				context.getCacheDir()));
		try {
			try (OutputStream out = new FileOutputStream(staged)) {
				out.write(data);
			}
			return sanitize(Uri.fromFile(staged), declaredMime, data,
					maxBytes);
		} finally {
			SecureMemory.secureDeleteFile(staged);
			CacheSweeper.release(staged);
		}
	}

	public static boolean canClean(@Nullable String declaredMime) {
		String type = base(declaredMime);
		if (type.startsWith("image/")) return !type.contains("svg");
		return type.startsWith("video/") || type.startsWith("audio/")
				|| type.equals(AttachmentDocuments.PDF)
				|| type.equals(AttachmentDocuments.TEXT);
	}

	static String base(@Nullable String type) {
		if (type == null) return "";
		int semi = type.indexOf(';');
		String b = semi >= 0 ? type.substring(0, semi) : type;
		return b.trim().toLowerCase(Locale.ROOT);
	}

	private Cleaned clean(Uri source, String declaredMime, byte[] data,
			long maxBytes) throws IOException {
		if (isPlainText(declaredMime, data)) {
			return new Cleaned(AttachmentDocuments.TEXT, data);
		}
		if (GifMetadataStripper.isGif(data)) {
			byte[] gif = rebuiltGif(data);
			if (gif != null) {
				if (gif.length > maxBytes) {
					throw MediaRefusedException.tooLarge(
							"cleaned image too large");
				}
				return new Cleaned("image/gif", gif);
			}
		}
		if (SafeImageDecoder.hasAllowedMagic(data)
				|| MetadataStripper.isReencodableForSending(data)) {
			String mime = reencodedType(declaredMime, data);
			byte[] clean;
			try {
				clean = stripper.stripImageMetadataOrThrow(data, mime);
			} catch (IOException e) {
				throw MediaRefusedException.cannotClean(e);
			}
			if (clean.length > maxBytes) {
				throw MediaRefusedException.tooLarge("cleaned image too large");
			}
			return new Cleaned(MetadataStripper.strippedImageMimeType(mime),
					clean);
		}
		if (declaredMime.startsWith("image/")) {
			throw MediaRefusedException.cannotClean(
					"image type cannot be cleaned");
		}
		if (cannotBeCleaned(data)) {
			throw MediaRefusedException.cannotClean(
					"media type cannot be cleaned");
		}
		if (needsRemux(declaredMime, data)) {
			MetadataStripper.Remuxed remuxed;
			try {
				remuxed = stripper.remuxForSending(source, resolver, true);
			} catch (IOException e) {
				throw MediaRefusedException.cannotClean(e);
			}
			File f = remuxed.getFile();
			try {
				if (f.length() > maxBytes) {
					throw MediaRefusedException.tooLarge(
							"cleaned media too large");
				}
				return new Cleaned(remuxed.getMimeType(), readAll(f));
			} finally {
				MetadataStripper.discard(f);
			}
		}
		try {
			if (MediaMagic.isRiff(data, "WAVE")) {
				return new Cleaned(WAV,
						AudioTagStripper.waveWithoutMetadata(data));
			}
			if (AudioTagStripper.hasLeadingTag(data)
					|| MediaMagic.isMpegAudioFrame(data, 0)
					|| startsWith(data, "fLaC") || startsWith(data, "OggS")) {
				byte[] clean = cleanStream(AudioTagStripper.withoutTags(data));
				String type = audioType(clean);
				if (type == null) {
					throw new IOException("audio type not recognised");
				}
				return new Cleaned(type, clean);
			}
		} catch (IOException e) {
			throw MediaRefusedException.cannotClean(e);
		}
		String type = base(declaredMime);
		if (type.equals(AttachmentDocuments.PDF) || AttachmentDocuments
				.contentMatches(AttachmentDocuments.PDF, data)) {
			return new Cleaned(AttachmentDocuments.PDF,
					new PdfCleaner(context).clean(data, maxBytes));
		}
		throw MediaRefusedException.cannotClean("file type cannot be cleaned");
	}

	@Nullable
	private static byte[] rebuiltGif(byte[] data) {
		try {
			return GifMetadataStripper.withoutMetadata(data);
		} catch (IOException e) {
			return null;
		}
	}

	static String reencodedType(String declaredMime, byte[] data) {
		if (MediaMagic.isBmp(data)) return "image/png";
		if (MediaMagic.isAvif(data)) return "image/jpeg";
		return declaredMime.startsWith("image/") ? declaredMime : "image/jpeg";
	}

	static byte[] cleanStream(byte[] rest) throws IOException {
		if (startsWith(rest, "fLaC")) {
			return AudioTagStripper.flacWithoutMetadata(rest);
		}
		if (startsWith(rest, "OggS")) {
			return OggCommentStripper.withoutComments(rest);
		}
		if (hidesAnotherContainer(rest)) {
			throw new IOException("media type cannot be cleaned");
		}
		if (MediaMagic.isMpegAudioFrame(rest, 0)
				|| isFrameAfterPadding(rest)) {
			return MpegAudioFrames.rebuilt(rest);
		}
		if (!MediaMagic.isAudio(rest)) {
			throw new IOException("content behind the tags is not audio");
		}
		return rest;
	}

	@Nullable
	static String audioType(byte[] clean) {
		if (startsWith(clean, "OggS")) return "audio/ogg";
		if (startsWith(clean, "fLaC")) return "audio/flac";
		if (startsWith(clean, "#!AMR-WB")) return "audio/amr-wb";
		if (startsWith(clean, "#!AMR")) return "audio/amr";
		if (MediaMagic.isRiff(clean, "WAVE")) return WAV;
		int i = 0;
		while (i < clean.length && clean[i] == 0) i++;
		if (!MediaMagic.isMpegAudioFrame(clean, i)) return null;
		return (clean[i + 1] & 0x06) == 0 ? "audio/aac" : "audio/mpeg";
	}

	private static boolean isFrameAfterPadding(byte[] rest) {
		int i = 0;
		while (i < rest.length && rest[i] == 0) i++;
		return i > 0 && MediaMagic.isMpegAudioFrame(rest, i);
	}

	static boolean hidesAnotherContainer(byte[] rest) {
		if (MediaMagic.isStillImage(rest) || MediaMagic.isRiffContainer(rest)
				|| isIsoMedia(rest) || MediaMagic.isEbml(rest)) {
			return true;
		}
		int window = Math.min(rest.length, HIDDEN_CONTAINER_WINDOW);
		for (int i = 1; i < window; i++) {
			if (MediaMagic.hasContainerSignatureAt(rest, i)) return true;
		}
		return false;
	}

	private static boolean startsWith(byte[] d, String magic) {
		if (d.length < magic.length()) return false;
		for (int i = 0; i < magic.length(); i++) {
			if (d[i] != (byte) magic.charAt(i)) return false;
		}
		return true;
	}

	static boolean cannotBeCleaned(byte[] data) {
		if (MediaMagic.isStillImage(data)) return true;
		return MediaMagic.isRiffContainer(data)
				&& !MediaMagic.isRiff(data, "WAVE")
				&& !MediaMagic.isRiff(data, "AVI ");
	}

	static boolean isPlainText(String declaredMime, byte[] data) {
		return AttachmentDocuments.TEXT.equals(base(declaredMime))
				&& AttachmentDocuments.contentMatches(
				AttachmentDocuments.TEXT, data)
				&& !AttachmentDocuments.contentMatches(
				AttachmentDocuments.PDF, data)
				&& !looksLikeMedia(data);
	}

	private static boolean looksLikeMedia(byte[] data) {
		return GifMetadataStripper.isGif(data)
				|| SafeImageDecoder.hasAllowedMagic(data)
				|| MetadataStripper.isReencodableForSending(data)
				|| cannotBeCleaned(data) || isIsoMedia(data)
				|| MediaMagic.isEbml(data) || MediaMagic.isRiffContainer(data)
				|| AudioTagStripper.hasLeadingTag(data)
				|| MediaMagic.isMpegAudioFrame(data, 0)
				|| startsWith(data, "fLaC") || startsWith(data, "OggS");
	}

	static boolean needsRemux(String declaredMime, byte[] data) {
		if (isPlainText(declaredMime, data)) return false;
		return declaredMime.startsWith("video/")
				|| declaredMime.startsWith("audio/mp4")
				|| declaredMime.equals("audio/x-m4a")
				|| declaredMime.equals("audio/3gpp")
				|| isIsoMedia(data)
				|| MediaMagic.isEbml(data)
				|| MediaMagic.isRiff(data, "AVI ");
	}

	static boolean isIsoMedia(byte[] data) {
		return data.length >= 12 && MediaMagic.isIsoMedia(data);
	}

	private static byte[] readAll(File f) throws IOException {
		try (InputStream in = new FileInputStream(f);
				ByteArrayOutputStream out = new ByteArrayOutputStream(
						(int) Math.min(f.length(), Integer.MAX_VALUE))) {
			byte[] buf = new byte[64 * 1024];
			int n;
			while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
			return out.toByteArray();
		}
	}
}

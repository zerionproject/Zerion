package com.professor.zerion.android.attachment;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;

import com.professor.zerion.android.util.MediaMagic;
import com.professor.zerion.android.util.SafeImageDecoder;
import com.professor.zerion.android.vault.utils.MetadataStripper;
import com.professor.zerion.android.vault.utils.SecureMemory;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

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

	private final MetadataStripper stripper;
	private final ContentResolver resolver;

	public SharedMediaSanitizer(Context context) {
		this.stripper = new MetadataStripper(context);
		this.resolver = context.getApplicationContext().getContentResolver();
	}

	public Cleaned sanitize(Uri source, String declaredMime, byte[] data,
			long maxBytes) throws IOException {
		try {
			return clean(source, declaredMime, data, maxBytes);
		} catch (OutOfMemoryError e) {
			throw MediaRefusedException.tooLarge("media too large to clean");
		}
	}

	private Cleaned clean(Uri source, String declaredMime, byte[] data,
			long maxBytes) throws IOException {
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
				SecureMemory.secureDeleteFile(f);
			}
		}
		try {
			if (MediaMagic.isRiff(data, "WAVE")) {
				return new Cleaned(declaredMime,
						AudioTagStripper.waveWithoutMetadata(data));
			}
			if (AudioTagStripper.hasLeadingTag(data)
					|| MediaMagic.isMpegAudioFrame(data, 0)
					|| startsWith(data, "fLaC") || startsWith(data, "OggS")) {
				return new Cleaned(declaredMime,
						cleanStream(AudioTagStripper.withoutTags(data)));
			}
		} catch (IOException e) {
			throw MediaRefusedException.cannotClean(e);
		}
		return new Cleaned(declaredMime, data);
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
		if (!MediaMagic.isAudio(rest) && !isFrameAfterPadding(rest)) {
			throw new IOException("content behind the tags is not audio");
		}
		return rest;
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

	static boolean needsRemux(String declaredMime, byte[] data) {
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

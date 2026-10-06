package com.professor.zerion.android.util;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Arrays;

@NotNullByDefault
public final class MediaMagic {

	private static final String[] ISO_FIRST_BOXES = {"ftyp", "moov", "wide",
			"mdat", "free", "skip", "pnot", "foov", "moof", "udta", "uuid"};

	private static final String[] STILL_IMAGE_BRANDS = {"heic", "heix",
			"hevc", "hevx", "heim", "heis", "hevm", "hevs", "mif1", "msf1",
			"avif", "avis"};

	private MediaMagic() {
	}

	public static boolean isIsoMedia(byte[] d) {
		if (d.length < 8) return false;
		for (String box : ISO_FIRST_BOXES) {
			if (ascii(d, 4, box)) return true;
		}
		return false;
	}

	public static boolean isEbml(byte[] d) {
		return d.length >= 4 && u8(d, 0) == 0x1A && u8(d, 1) == 0x45
				&& u8(d, 2) == 0xDF && u8(d, 3) == 0xA3;
	}

	public static boolean isTiff(byte[] d) {
		if (d.length < 4) return false;
		byte m = d[2];
		byte n = d[3];
		if (ascii(d, 0, "II")) {
			return ((m == 42 || m == 43 || m == 'U') && n == 0)
					|| (m == 'R' && (n == 'O' || n == 'S'));
		}
		if (ascii(d, 0, "MM")) {
			return (m == 0 && (n == 42 || n == 43)) || (m == 'O' && n == 'R');
		}
		return false;
	}

	public static boolean isJpegXl(byte[] d) {
		if (d.length >= 2 && u8(d, 0) == 0xFF && u8(d, 1) == 0x0A) {
			return true;
		}
		return d.length >= 12 && u8(d, 0) == 0 && u8(d, 1) == 0
				&& u8(d, 2) == 0 && u8(d, 3) == 0x0C && ascii(d, 4, "JXL ")
				&& u8(d, 8) == 0x0D && u8(d, 9) == 0x0A && u8(d, 10) == 0x87
				&& u8(d, 11) == 0x0A;
	}

	public static boolean isJpeg2000(byte[] d) {
		if (d.length >= 4 && u8(d, 0) == 0xFF && u8(d, 1) == 0x4F
				&& u8(d, 2) == 0xFF && u8(d, 3) == 0x51) {
			return true;
		}
		return d.length >= 12 && u8(d, 0) == 0 && u8(d, 1) == 0
				&& u8(d, 2) == 0 && u8(d, 3) == 0x0C && ascii(d, 4, "jP  ")
				&& u8(d, 8) == 0x0D && u8(d, 9) == 0x0A && u8(d, 10) == 0x87
				&& u8(d, 11) == 0x0A;
	}

	public static boolean isRiffContainer(byte[] d) {
		return d.length >= 12 && (ascii(d, 0, "RIFF") || ascii(d, 0, "RIFX")
				|| ascii(d, 0, "RF64"));
	}

	public static boolean isRiff(byte[] d, String form) {
		return d.length >= 12 && ascii(d, 0, "RIFF") && ascii(d, 8, form);
	}

	public static boolean isRawWithOwnSignature(byte[] d) {
		if (d.length >= 4 && u8(d, 0) == 0x76 && u8(d, 1) == 0x2F
				&& u8(d, 2) == 0x31 && u8(d, 3) == 0x01) {
			return true;
		}
		if (ascii(d, 0, "FUJIFILMCCD-RAW")) return true;
		if (ascii(d, 0, "II") && ascii(d, 6, "HEAPCCDR")) return true;
		if (d.length >= 4 && u8(d, 0) == 0 && ascii(d, 1, "MRM")) return true;
		return ascii(d, 0, "FOVb");
	}

	public static boolean isStillImage(byte[] d) {
		if (SafeImageDecoder.hasAllowedMagic(d)) return true;
		if (isTiff(d) || isJpegXl(d) || isJpeg2000(d)) return true;
		if (isRawWithOwnSignature(d)) return true;
		if (d.length >= 4 && ascii(d, 0, "8BPS")) return true;
		if (isBmp(d)) return true;
		if (d.length >= 12 && ascii(d, 4, "ftyp")) {
			for (String brand : STILL_IMAGE_BRANDS) {
				if (ascii(d, 8, brand)) return true;
			}
		}
		return false;
	}

	public static boolean isBmp(byte[] d) {
		if (d.length < 18 || d[0] != 'B' || d[1] != 'M') return false;
		long dib = u8(d, 14) | (u8(d, 15) << 8) | (u8(d, 16) << 16)
				| ((long) u8(d, 17) << 24);
		return dib == 12 || dib == 40 || dib == 52 || dib == 56
				|| dib == 64 || dib == 108 || dib == 124;
	}

	public static boolean isAvif(byte[] d) {
		return d.length >= 12 && ascii(d, 4, "ftyp")
				&& (ascii(d, 8, "avif") || ascii(d, 8, "avis"));
	}

	public static boolean hasContainerSignatureAt(byte[] d, int off) {
		if (off < 0 || d.length - off < 4) return false;
		byte[] s = Arrays.copyOfRange(d, off, Math.min(d.length, off + 32));
		if (u8(s, 0) == 0xFF && u8(s, 1) == 0xD8 && u8(s, 2) == 0xFF
				&& isJpegMarker(u8(s, 3))) {
			return true;
		}
		if (u8(s, 0) == 0x89 && ascii(s, 1, "PNG")) return true;
		if (ascii(s, 0, "GIF87a") || ascii(s, 0, "GIF89a")) return true;
		if (ascii(s, 0, "RIFF") || ascii(s, 0, "RIFX") || ascii(s, 0, "RF64")) {
			return true;
		}
		if (isTiff(s) || isJpeg2000(s) || isRawWithOwnSignature(s)) return true;
		if (u8(s, 0) == 0 && isJpegXl(s)) return true;
		if (isIsoMedia(s) || isEbml(s)) return true;
		return ascii(s, 0, "8BPS") || ascii(s, 0, "OggS")
				|| ascii(s, 0, "fLaC");
	}

	private static boolean isJpegMarker(int m) {
		return (m >= 0xE0 && m <= 0xEF) || (m >= 0xC0 && m <= 0xC4)
				|| m == 0xDB || m == 0xFE;
	}

	public static boolean isVideo(byte[] d) {
		return isIsoMedia(d) || isEbml(d);
	}

	public static boolean isAudio(byte[] d) {
		if (isMpegAudioFrame(d, 0)) return true;
		if (d.length >= 4 && (ascii(d, 0, "OggS") || ascii(d, 0, "fLaC"))) {
			return true;
		}
		if (d.length >= 5 && ascii(d, 0, "#!AMR")) return true;
		return isRiff(d, "WAVE") || isIsoMedia(d) || isEbml(d);
	}

	public static boolean isMpegAudioFrame(byte[] d, int off) {
		if (off < 0 || d.length - off < 2) return false;
		int b0 = u8(d, off);
		int b1 = u8(d, off + 1);
		if (b0 != 0xFF || (b1 & 0xE0) != 0xE0) return false;
		int version = (b1 >> 3) & 3;
		int layer = (b1 >> 1) & 3;
		if (layer == 0) return (b1 & 0xF6) == 0xF0;
		return version != 1;
	}

	static boolean ascii(byte[] d, int off, String s) {
		if (off < 0 || d.length - off < s.length()) return false;
		for (int i = 0; i < s.length(); i++) {
			if (d[off + i] != (byte) s.charAt(i)) return false;
		}
		return true;
	}

	private static int u8(byte[] d, int i) {
		return d[i] & 0xFF;
	}
}

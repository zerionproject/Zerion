package com.professor.zerion.android.vault.utils;

import org.briarproject.nullsafety.NotNullByDefault;

import java.nio.ByteBuffer;
import java.util.Arrays;

import javax.annotation.Nullable;

@NotNullByDefault
final class SeiUserDataFilter {

	static final String AVC = "video/avc";
	static final String HEVC = "video/hevc";

	private static final int AVC_SEI = 6;
	private static final int HEVC_PREFIX_SEI = 39;
	private static final int HEVC_SUFFIX_SEI = 40;
	private static final int USER_DATA_UNREGISTERED = 5;
	private static final int RBSP_STOP = 0x80;

	private SeiUserDataFilter() {
	}

	static int filter(@Nullable String mime, ByteBuffer sample, int size) {
		if (size <= 0 || !(AVC.equals(mime) || HEVC.equals(mime))) {
			return size;
		}
		if (sample.hasArray()) {
			return filter(mime, sample.array(), sample.arrayOffset(), size);
		}
		byte[] copy = new byte[size];
		ByteBuffer view = sample.duplicate();
		view.clear();
		view.get(copy);
		int kept = filter(mime, copy, 0, size);
		if (kept != size) {
			view.clear();
			view.put(copy, 0, kept);
		}
		return kept;
	}

	static int filter(@Nullable String mime, byte[] d, int off, int size) {
		boolean hevc = HEVC.equals(mime);
		if (size <= 0 || (!hevc && !AVC.equals(mime))) return size;
		int end = off + size;
		int code = startCode(d, off, end);
		if (code < 0) return size;
		for (int i = off; i < code - 2; i++) {
			if (d[i] != 0) return size;
		}
		int write = off;
		int unit = off;
		while (code >= 0) {
			int nal = code + 1;
			int next = startCode(d, nal, end);
			int unitEnd = next < 0 ? end : zeroRunStart(d, nal, next - 2);
			if (!dropped(d, nal, unitEnd, hevc)) {
				if (write != unit) {
					System.arraycopy(d, unit, d, write, unitEnd - unit);
				}
				write += unitEnd - unit;
			}
			unit = unitEnd;
			code = next;
		}
		return write - off;
	}

	private static int startCode(byte[] d, int from, int end) {
		for (int i = from + 2; i < end; i++) {
			if (d[i] == 1 && d[i - 1] == 0 && d[i - 2] == 0) return i;
		}
		return -1;
	}

	private static int zeroRunStart(byte[] d, int from, int zero) {
		while (zero > from && d[zero - 1] == 0) zero--;
		return zero;
	}

	private static boolean dropped(byte[] d, int from, int to, boolean hevc) {
		if (to <= from) return false;
		int header = hevc ? 2 : 1;
		if (hevc) {
			int type = (d[from] >> 1) & 0x3F;
			if (type != HEVC_PREFIX_SEI && type != HEVC_SUFFIX_SEI) {
				return false;
			}
		} else if ((d[from] & 0x1F) != AVC_SEI) {
			return false;
		}
		if (to - from < header) return true;
		return mayCarryUserData(unescaped(d, from + header, to));
	}

	static byte[] unescaped(byte[] d, int from, int to) {
		byte[] out = new byte[to - from];
		int n = 0;
		int zeros = 0;
		for (int i = from; i < to; i++) {
			int b = d[i] & 0xFF;
			if (zeros >= 2 && b == 3) {
				zeros = 0;
				continue;
			}
			out[n++] = (byte) b;
			zeros = b == 0 ? zeros + 1 : 0;
		}
		return n == out.length ? out : Arrays.copyOf(out, n);
	}

	static boolean mayCarryUserData(byte[] rbsp) {
		int end = rbsp.length;
		while (end > 0 && rbsp[end - 1] == 0) end--;
		if (end == 0 || (rbsp[end - 1] & 0xFF) != RBSP_STOP) return true;
		end--;
		int pos = 0;
		while (pos < end) {
			long type = 0;
			while (pos < end && (rbsp[pos] & 0xFF) == 0xFF) {
				type += 0xFF;
				pos++;
			}
			if (pos == end) return true;
			type += rbsp[pos++] & 0xFF;
			long size = 0;
			while (pos < end && (rbsp[pos] & 0xFF) == 0xFF) {
				size += 0xFF;
				pos++;
			}
			if (pos == end) return true;
			size += rbsp[pos++] & 0xFF;
			if (type == USER_DATA_UNREGISTERED || size > end - pos) return true;
			pos += (int) size;
		}
		return false;
	}
}

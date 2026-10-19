package com.professor.zerion.android.maxfont;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

final class RealFont {

	private static final File FILE =
			new File("src/main/res/font/plex_sans_variable.ttf");
	private static final float MISSING_GLYPH_EM = 1.25f;
	private static final float BOLD_WIDENING = 0.064f;
	private static RealFont instance;

	private final ByteBuffer font;
	private final Map<String, Integer> tables = new HashMap<>();
	private final Map<Integer, Float> advances = new HashMap<>();
	private final float upem;
	private final int metricsCount;
	private final int cmap;
	private final int cmapFormat;
	final float topEm;
	final float ascentEm;
	final float descentEm;
	final float bottomEm;

	private RealFont() throws Exception {
		font = ByteBuffer.wrap(Files.readAllBytes(FILE.toPath()));
		int count = u16(4);
		for (int i = 0; i < count; i++) {
			int record = 12 + 16 * i;
			String tag = new String(new byte[] {font.get(record),
					font.get(record + 1), font.get(record + 2),
					font.get(record + 3)}, "US-ASCII");
			tables.put(tag, font.getInt(record + 8));
		}
		int head = tables.get("head");
		int hhea = tables.get("hhea");
		upem = u16(head + 18);
		topEm = font.getShort(head + 42) / upem;
		bottomEm = -font.getShort(head + 38) / upem;
		ascentEm = font.getShort(hhea + 4) / upem;
		descentEm = -font.getShort(hhea + 6) / upem;
		metricsCount = u16(hhea + 34);
		int table = tables.get("cmap");
		int subtables = u16(table + 2);
		int best = -1;
		int bestFormat = -1;
		for (int i = 0; i < subtables; i++) {
			int record = table + 4 + 8 * i;
			int platform = u16(record);
			int encoding = u16(record + 2);
			int offset = table + font.getInt(record + 4);
			int format = u16(offset);
			boolean unicode = platform == 0
					|| (platform == 3 && (encoding == 1 || encoding == 10));
			if (!unicode) continue;
			if (format == 12 || (format == 4 && bestFormat != 12)) {
				best = offset;
				bestFormat = format;
			}
		}
		if (best < 0) throw new IllegalStateException("no unicode cmap");
		cmap = best;
		cmapFormat = bestFormat;
	}

	private int u16(int offset) {
		return font.getShort(offset) & 0xffff;
	}

	private long u32(int offset) {
		return font.getInt(offset) & 0xffffffffL;
	}

	static synchronized RealFont get() {
		if (instance == null) {
			try {
				instance = new RealFont();
			} catch (Exception e) {
				throw new AssertionError(e);
			}
		}
		return instance;
	}

	private int glyph(int cp) {
		if (cmapFormat == 12) {
			long groups = u32(cmap + 12);
			for (int i = 0; i < groups; i++) {
				int g = cmap + 16 + 12 * i;
				long start = u32(g);
				long end = u32(g + 4);
				if (cp >= start && cp <= end) {
					return (int) (u32(g + 8) + cp - start);
				}
			}
			return 0;
		}
		if (cp > 0xffff) return 0;
		int segments = u16(cmap + 6) / 2;
		int ends = cmap + 14;
		int starts = ends + 2 * segments + 2;
		int deltas = starts + 2 * segments;
		int ranges = deltas + 2 * segments;
		for (int i = 0; i < segments; i++) {
			int end = u16(ends + 2 * i);
			if (cp > end) continue;
			int start = u16(starts + 2 * i);
			if (cp < start) return 0;
			int delta = font.getShort(deltas + 2 * i);
			int rangeOffset = u16(ranges + 2 * i);
			if (rangeOffset == 0) return (cp + delta) & 0xffff;
			int address = ranges + 2 * i + rangeOffset + 2 * (cp - start);
			int g = u16(address);
			return g == 0 ? 0 : (g + delta) & 0xffff;
		}
		return 0;
	}

	private static boolean zeroWidth(int cp) {
		return cp < 0x20 || (cp >= 0x7f && cp < 0xa0)
				|| (cp >= 0x200b && cp <= 0x200f)
				|| (cp >= 0x2028 && cp <= 0x202e)
				|| (cp >= 0x2060 && cp <= 0x2064) || cp == 0xfeff
				|| (cp >= 0x0300 && cp <= 0x036f)
				|| (cp >= 0xfe00 && cp <= 0xfe0f);
	}

	synchronized float advanceEm(int cp, int weight) {
		if (zeroWidth(cp)) return 0f;
		Float regular = advances.get(cp);
		if (regular == null) {
			int g = glyph(cp);
			if (g == 0) {
				regular = MISSING_GLYPH_EM;
			} else {
				int hmtx = tables.get("hmtx");
				int index = Math.min(g, metricsCount - 1);
				regular = u16(hmtx + 4 * index) / upem;
			}
			advances.put(cp, regular);
		}
		float t = Math.max(0f, Math.min(1f, (weight - 400) / 300f));
		return regular * (1f + BOLD_WIDENING * t);
	}

	static boolean visible(int cp) {
		return !zeroWidth(cp);
	}
}

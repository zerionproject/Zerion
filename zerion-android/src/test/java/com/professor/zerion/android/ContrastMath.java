package com.professor.zerion.android;

public final class ContrastMath {

	private ContrastMath() {
	}

	private static double channel(int c) {
		double s = c / 255.0;
		return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
	}

	public static double luminance(int c) {
		return 0.2126 * channel((c >> 16) & 0xFF)
				+ 0.7152 * channel((c >> 8) & 0xFF)
				+ 0.0722 * channel(c & 0xFF);
	}

	public static int over(int top, int bottom) {
		double a = ((top >>> 24) & 0xFF) / 255.0;
		int out = 0xFF000000;
		for (int shift : new int[] {16, 8, 0}) {
			int v = (int) Math.round(((top >> shift) & 0xFF) * a
					+ ((bottom >> shift) & 0xFF) * (1 - a));
			out |= v << shift;
		}
		return out;
	}

	public static double ratio(int fg, int bg) {
		int opaque = over(bg, 0xFF000000);
		double a = luminance(over(fg, opaque));
		double b = luminance(opaque);
		return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
	}
}

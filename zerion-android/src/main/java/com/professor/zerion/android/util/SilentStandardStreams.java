package com.professor.zerion.android.util;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.OutputStream;
import java.io.PrintStream;

@NotNullByDefault
public final class SilentStandardStreams {

	private static final PrintStream DISCARD =
			new PrintStream(new OutputStream() {
				@Override
				public void write(int b) {
				}

				@Override
				public void write(byte[] b, int off, int len) {
				}
			}, false);

	private SilentStandardStreams() {
	}

	public static void install() {
		System.setOut(DISCARD);
		System.setErr(DISCARD);
	}

	public static boolean isInstalled() {
		return System.out == DISCARD && System.err == DISCARD;
	}
}

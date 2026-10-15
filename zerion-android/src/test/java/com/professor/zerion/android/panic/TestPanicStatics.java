package com.professor.zerion.android.panic;

public final class TestPanicStatics {

	private TestPanicStatics() {
	}

	public static void reset() {
		WipePasswordManager.clearInstance();
	}
}

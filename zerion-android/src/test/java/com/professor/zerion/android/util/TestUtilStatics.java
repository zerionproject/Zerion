package com.professor.zerion.android.util;

public final class TestUtilStatics {

	private TestUtilStatics() {
	}

	public static void reset() {
		ExternalHandoff.resetForTests();
		ExternalHandoff.cleaner = ExternalHandoff.AS_IS;
		SecureClipboard.resetForTests();
		com.professor.zerion.android.conversation.MediaViewer.clearAll();
	}
}

package com.professor.zerion.android.attachment;

public final class SharedMediaSanitizerProbe {

	private SharedMediaSanitizerProbe() {
	}

	public static boolean hidesAnotherContainer(byte[] rest) {
		return SharedMediaSanitizer.hidesAnotherContainer(rest);
	}
}

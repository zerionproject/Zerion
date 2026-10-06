package com.professor.zerion.android;

import org.zerionproject.core.api.crypto.KeyStrengthener;

public final class KeyStrengthenerForTests {

	private KeyStrengthenerForTests() {
	}

	public static KeyStrengthener create() {
		return new AndroidKeyStrengthener();
	}
}

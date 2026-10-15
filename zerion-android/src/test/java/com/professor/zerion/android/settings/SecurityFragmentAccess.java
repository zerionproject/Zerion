package com.professor.zerion.android.settings;

import com.professor.zerion.android.security.AntiForensics;

public final class SecurityFragmentAccess {

	private SecurityFragmentAccess() {
	}

	public static boolean usbPanicFailed(boolean on,
			AntiForensics.UsbPanicState state) {
		return SecurityFragment.usbPanicFailed(on, state);
	}
}

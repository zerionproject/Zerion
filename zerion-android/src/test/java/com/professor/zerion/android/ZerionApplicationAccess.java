package com.professor.zerion.android;

import android.app.Application;

public final class ZerionApplicationAccess {

	private ZerionApplicationAccess() {
	}

	public static void armAtStart(Application app, AndroidComponent component) {
		((ZerionApplicationImpl) app).armUsbPanicOfThisProcess(component);
	}
}

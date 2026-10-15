package com.professor.zerion.android;

import android.app.Application;
import android.content.Context;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class ProcessRole {

	private ProcessRole() {
	}

	public static boolean isMain(Context context) {
		return context.getPackageName().equals(Application.getProcessName());
	}
}

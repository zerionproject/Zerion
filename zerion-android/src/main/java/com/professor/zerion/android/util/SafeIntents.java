package com.professor.zerion.android.util;

import android.content.Intent;
import android.os.Bundle;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class SafeIntents {

	private SafeIntents() {
	}

	public static boolean dropUnreadableExtras(@Nullable Intent intent) {
		if (intent == null) return true;
		try {
			Bundle extras = intent.getExtras();
			if (extras != null) {
				for (String key : extras.keySet()) {
					extras.get(key);
				}
			}
			return true;
		} catch (RuntimeException e) {
			intent.replaceExtras((Bundle) null);
			return false;
		}
	}
}

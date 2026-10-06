package com.professor.zerion.android.util;

import android.content.ClipboardManager;

import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowClipboardManager;

@Implements(ClipboardManager.class)
public class ClearableClipboardShadow extends ShadowClipboardManager {

	@Implementation
	protected void clearPrimaryClip() {
		setPrimaryClip(null);
	}
}

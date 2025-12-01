package com.professor.zerion.android.logging;

import org.briarproject.bramble.util.AndroidUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;

@NotNullByDefault
public interface LogEncrypter {
	@Nullable
	byte[] encryptLogs();
}

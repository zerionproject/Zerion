package com.professor.zerion.android.logging;

import org.briarproject.nullsafety.NotNullByDefault;

import androidx.annotation.Nullable;

@NotNullByDefault
public interface LogDecrypter {
	@Nullable
	String decryptLogs(@Nullable byte[] logKey);
}

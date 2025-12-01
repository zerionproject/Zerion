package com.professor.zerion.android.logging;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;

@NotNullByDefault
class LogDecrypterImpl implements LogDecrypter {

	@Inject
	LogDecrypterImpl() {
	}

	@Nullable
	@Override
	public String decryptLogs(@Nullable byte[] logKey) {
		return null;
	}
}

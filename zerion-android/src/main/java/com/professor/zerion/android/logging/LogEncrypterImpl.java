package com.professor.zerion.android.logging;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;

@NotNullByDefault
class LogEncrypterImpl implements LogEncrypter {

	@Inject
	LogEncrypterImpl() {
	}

	@Nullable
	@Override
	public byte[] encryptLogs() {
		return null;
	}

}

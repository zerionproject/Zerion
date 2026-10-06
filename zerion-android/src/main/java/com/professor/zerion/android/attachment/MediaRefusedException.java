package com.professor.zerion.android.attachment;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;

import androidx.annotation.StringRes;

@NotNullByDefault
public class MediaRefusedException extends IOException {

	private final boolean tooLarge;

	private MediaRefusedException(String message, boolean tooLarge,
			Throwable cause) {
		super(message, cause);
		this.tooLarge = tooLarge;
	}

	private MediaRefusedException(String message, boolean tooLarge) {
		super(message);
		this.tooLarge = tooLarge;
	}

	public static MediaRefusedException cannotClean(String message) {
		return new MediaRefusedException(message, false);
	}

	public static MediaRefusedException cannotClean(IOException cause) {
		if (cause instanceof MediaRefusedException) {
			return (MediaRefusedException) cause;
		}
		return new MediaRefusedException("media cannot be cleaned", false,
				cause);
	}

	public static MediaRefusedException tooLarge(String message) {
		return new MediaRefusedException(message, true);
	}

	public boolean isTooLarge() {
		return tooLarge;
	}

	@StringRes
	public int getUserMessage() {
		return tooLarge ? R.string.media_refused_too_large
				: R.string.media_refused_cannot_clean;
	}
}

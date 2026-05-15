package org.briarproject.bramble.api;

public class UnsupportedVersionException extends FormatException {

	private final boolean tooOld;

	public UnsupportedVersionException(boolean tooOld) {
		this.tooOld = tooOld;
	}

	public boolean isTooOld() {
		return tooOld;
	}
}

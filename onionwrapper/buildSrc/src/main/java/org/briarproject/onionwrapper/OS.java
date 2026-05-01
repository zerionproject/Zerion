package org.briarproject.onionwrapper;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public enum OS {
	Linux("linux"),
	Windows("windows"),
	MacOS("macos");

	private String id;

	OS(String id) {
		this.id = id;
	}

	public String getId() {
		return id;
	}
}

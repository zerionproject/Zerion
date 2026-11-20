package com.professor.zerion.android.vault.keepass;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@NotNullByDefault
public final class KdbxEntry {

	public static final String KEY_TITLE = "Title";
	public static final String KEY_USERNAME = "UserName";
	public static final String KEY_PASSWORD = "Password";
	public static final String KEY_URL = "URL";
	public static final String KEY_NOTES = "Notes";

	public final Map<String, String> strings;
	public final List<String> groupPath;
	public final boolean inRecycleBin;
	public final boolean hasBinary;

	KdbxEntry(Map<String, String> strings, List<String> groupPath,
			boolean inRecycleBin, boolean hasBinary) {
		this.strings = Collections.unmodifiableMap(strings);
		this.groupPath = Collections.unmodifiableList(groupPath);
		this.inRecycleBin = inRecycleBin;
		this.hasBinary = hasBinary;
	}

	public String string(String key) {
		String value = strings.get(key);
		return value == null ? "" : value;
	}
}

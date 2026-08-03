package org.zerionproject.core.api.contact;

import org.briarproject.nullsafety.NotNullByDefault;

import static org.zerionproject.core.api.contact.HandshakeLinkConstants.FORMAT_VERSION_CLASSICAL;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.FORMAT_VERSION_HYBRID;

@NotNullByDefault
public enum ContactType {

	ZERION(FORMAT_VERSION_HYBRID, "Post-Quantum (Zerion)"),

	BRIAR(FORMAT_VERSION_CLASSICAL, "Classical Mode");

	private final int formatVersion;
	private final String displayName;

	ContactType(int formatVersion, String displayName) {
		this.formatVersion = formatVersion;
		this.displayName = displayName;
	}

	public int getFormatVersion() {
		return formatVersion;
	}

	public String getDisplayName() {
		return displayName;
	}

	public boolean isPostQuantum() {
		return this == ZERION;
	}

	public static ContactType fromFormatVersion(int formatVersion) {
		for (ContactType type : values()) {
			if (type.formatVersion == formatVersion) {
				return type;
			}
		}
		throw new IllegalArgumentException(
				"Unknown format version: " + formatVersion);
	}
}

package org.zerionproject.core.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class Versions {

	private final List<Byte> supported;

	public Versions(List<Byte> supported) {
		this.supported = supported;
	}

	public List<Byte> getSupportedVersions() {
		return supported;
	}
}

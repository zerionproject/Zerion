package org.zerionproject.core.api.plugin;

import org.zerionproject.core.util.StringUtils;

public class TransportId {

	public static int MAX_TRANSPORT_ID_LENGTH = 100;

	private final String id;

	public TransportId(String id) {
		int length = StringUtils.toUtf8(id).length;
		if (length == 0 || length > MAX_TRANSPORT_ID_LENGTH)
			throw new IllegalArgumentException();
		this.id = id;
	}

	public String getString() {
		return id;
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof TransportId && id.equals(((TransportId) o).id);
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public String toString() {
		return id;
	}
}

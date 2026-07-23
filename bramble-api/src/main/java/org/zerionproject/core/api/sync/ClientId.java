package org.zerionproject.core.api.sync;

import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class ClientId implements Comparable<ClientId> {

	public static int MAX_CLIENT_ID_LENGTH = 100;

	private final String id;

	public ClientId(String id) {
		int length = StringUtils.toUtf8(id).length;
		if (length == 0 || length > MAX_CLIENT_ID_LENGTH)
			throw new IllegalArgumentException();
		this.id = id;
	}

	public String getString() {
		return id;
	}

	@Override
	public int compareTo(ClientId clientId) {
		return id.compareTo(clientId.getString());
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof ClientId && id.equals(((ClientId) o).id);
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

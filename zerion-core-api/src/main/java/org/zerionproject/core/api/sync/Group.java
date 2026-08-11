package org.zerionproject.core.api.sync;

import static org.zerionproject.core.api.sync.SyncConstants.MAX_GROUP_DESCRIPTOR_LENGTH;

public class Group {

	public enum Visibility {

		INVISIBLE(0),
		VISIBLE(1),
		SHARED(2);

		private final int value;

		Visibility(int value) {
			this.value = value;
		}

		public int getValue() {
			return value;
		}

		public static Visibility min(Visibility a, Visibility b) {
			return a.getValue() < b.getValue() ? a : b;
		}
	}

	public static final int FORMAT_VERSION = 1;

	private final GroupId id;
	private final ClientId clientId;
	private final int majorVersion;
	private final byte[] descriptor;

	public Group(GroupId id, ClientId clientId, int majorVersion,
			byte[] descriptor) {
		if (descriptor.length > MAX_GROUP_DESCRIPTOR_LENGTH)
			throw new IllegalArgumentException();
		this.id = id;
		this.clientId = clientId;
		this.majorVersion = majorVersion;
		this.descriptor = descriptor;
	}

	public GroupId getId() {
		return id;
	}

	public ClientId getClientId() {
		return clientId;
	}

	public int getMajorVersion() {
		return majorVersion;
	}

	public byte[] getDescriptor() {
		return descriptor;
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof Group && id.equals(((Group) o).id);
	}
}

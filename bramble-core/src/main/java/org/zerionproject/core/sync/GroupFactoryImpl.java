package org.zerionproject.core.sync;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupFactory;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.util.ByteUtils;
import org.zerionproject.core.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.core.api.sync.Group.FORMAT_VERSION;
import static org.zerionproject.core.api.sync.GroupId.LABEL;
import static org.zerionproject.core.util.ByteUtils.INT_32_BYTES;

@Immutable
@NotNullByDefault
class GroupFactoryImpl implements GroupFactory {

	private static final byte[] FORMAT_VERSION_BYTES =
			new byte[] {FORMAT_VERSION};

	private final CryptoComponent crypto;

	@Inject
	GroupFactoryImpl(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	@Override
	public Group createGroup(ClientId c, int majorVersion, byte[] descriptor) {
		byte[] majorVersionBytes = new byte[INT_32_BYTES];
		ByteUtils.writeUint32(majorVersion, majorVersionBytes, 0);
		byte[] hash = crypto.hash(LABEL, FORMAT_VERSION_BYTES,
				StringUtils.toUtf8(c.getString()), majorVersionBytes,
				descriptor);
		return new Group(new GroupId(hash), c, majorVersion, descriptor);
	}
}

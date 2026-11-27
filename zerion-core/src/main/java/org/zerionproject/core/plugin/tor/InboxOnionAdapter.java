package org.zerionproject.core.plugin.tor;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.plugin.OnionTargetListener;

import java.io.IOException;
import java.net.Socket;

@NotNullByDefault
public interface InboxOnionAdapter {

	boolean isConnected();

	OnionTargetListener openOnionTarget() throws IOException;

	String publishInboxOnion(String target, String privateKeyBlob,
			byte[] clientAuthPublicKey) throws IOException;

	void removeInboxOnion(String onion) throws IOException;

	default boolean isInboxPowProtected(String onion) {
		return false;
	}

	Socket connectToInbox(String onion, byte[] clientAuthPrivateKey)
			throws IOException;

	void forgetInboxClientKey(String onion) throws IOException;
}

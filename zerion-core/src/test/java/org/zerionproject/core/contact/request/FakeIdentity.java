package org.zerionproject.core.contact.request;

import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.test.TestUtils;

import java.lang.reflect.Proxy;

public final class FakeIdentity {

	public final LocalAuthor author = TestUtils.getLocalAuthor();
	public final KeyPair handshake;
	public volatile KeyPair perAdd;
	public volatile int rotations = 0;
	public final IdentityManager manager;

	public FakeIdentity(CryptoComponent crypto) {
		handshake = crypto.generateAgreementKeyPair();
		perAdd = crypto.generateHybridAgreementKeyPair();
		manager = (IdentityManager) Proxy.newProxyInstance(
				IdentityManager.class.getClassLoader(),
				new Class<?>[] {IdentityManager.class},
				(proxy, method, args) -> {
					switch (method.getName()) {
						case "getLocalAuthor":
							return author;
						case "getHybridHandshakeKeys":
							return perAdd;
						case "getHandshakeKeys":
							return handshake;
						case "rotateHybridHandshakeKeys":
							perAdd = crypto.generateHybridAgreementKeyPair();
							rotations++;
							return null;
						case "hashCode":
							return System.identityHashCode(proxy);
						case "equals":
							return proxy == args[0];
						default:
							throw new UnsupportedOperationException(
									method.getName());
					}
				});
	}
}

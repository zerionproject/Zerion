package org.zerionproject.core.api.keyagreement;

import org.zerionproject.core.api.data.BdfList;

import java.io.IOException;

public abstract class KeyAgreementListener {

	private final BdfList descriptor;

	public KeyAgreementListener(BdfList descriptor) {
		this.descriptor = descriptor;
	}

	public BdfList getDescriptor() {
		return descriptor;
	}

	public abstract KeyAgreementConnection accept() throws IOException;

	public abstract void close();
}

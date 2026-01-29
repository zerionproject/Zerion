package org.briarproject.bramble.api.keyagreement;

import org.briarproject.bramble.api.data.BdfList;

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

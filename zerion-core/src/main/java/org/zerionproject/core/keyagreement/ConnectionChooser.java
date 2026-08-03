package org.zerionproject.core.keyagreement;

import org.zerionproject.core.api.keyagreement.KeyAgreementConnection;

import java.util.concurrent.Callable;

import javax.annotation.Nullable;

interface ConnectionChooser {

	void submit(Callable<KeyAgreementConnection> task);

	@Nullable
	KeyAgreementConnection poll(long timeout) throws InterruptedException;

	void stop();
}

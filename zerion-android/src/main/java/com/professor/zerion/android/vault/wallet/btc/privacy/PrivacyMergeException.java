package com.professor.zerion.android.vault.wallet.btc.privacy;

import java.io.IOException;

public final class PrivacyMergeException extends IOException {

	public final int clusterCount;

	public PrivacyMergeException(int clusterCount) {
		super("This payment combines " + clusterCount + " privacy clusters");
		this.clusterCount = clusterCount;
	}
}

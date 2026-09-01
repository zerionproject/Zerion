package com.professor.zerion.android.vault.wallet.btc.privacy;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class PrivacyMeta {

	public final String outpoint;
	public final long valueSat;
	public final String address;
	public final UtxoOrigin origin;
	public final String clusterId;
	public final boolean frozen;
	@Nullable
	public final String label;

	public PrivacyMeta(String outpoint, long valueSat, String address,
			UtxoOrigin origin, String clusterId, boolean frozen,
			@Nullable String label) {
		this.outpoint = outpoint;
		this.valueSat = valueSat;
		this.address = address;
		this.origin = origin;
		this.clusterId = clusterId;
		this.frozen = frozen;
		this.label = label;
	}
}

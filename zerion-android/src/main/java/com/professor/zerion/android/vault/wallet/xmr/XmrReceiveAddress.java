package com.professor.zerion.android.vault.wallet.xmr;

import androidx.annotation.Nullable;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public final class XmrReceiveAddress {

	public final String walletId;
	public final int index;
	public final String address;
	@Nullable
	public final String label;
	public final long issuedAt;
	@Nullable
	public final Boolean used;

	public XmrReceiveAddress(String walletId, int index, String address,
			@Nullable String label, long issuedAt, @Nullable Boolean used) {
		this.walletId = walletId;
		this.index = index;
		this.address = address;
		this.label = label;
		this.issuedAt = issuedAt;
		this.used = used;
	}

	public boolean isPrimary() {
		return index == 0;
	}

	public String shortPreview() {
		if (address.length() <= 16) return address;
		return address.substring(0, 8) + "…" + address.substring(address.length() - 8);
	}
}

package com.professor.zerion.android.vault.wallet;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class WalletRecord {

	public final String id;
	public final WalletCoin coin;
	public final String name;
	public final long createdTimestamp;
	public final boolean hasPassword;

	public WalletRecord(String id, WalletCoin coin, String name,
			long createdTimestamp, boolean hasPassword) {
		this.id = id;
		this.coin = coin;
		this.name = name;
		this.createdTimestamp = createdTimestamp;
		this.hasPassword = hasPassword;
	}
}

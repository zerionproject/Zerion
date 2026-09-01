package com.professor.zerion.android.vault.wallet;

import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public enum WalletCoin {
	BTC("BTC", "Bitcoin"),
	XMR("XMR", "Monero");

	private final String code;
	private final String label;

	WalletCoin(String code, String label) {
		this.code = code;
		this.label = label;
	}

	public String getCode() {
		return code;
	}

	public String getLabel() {
		return label;
	}

	public static WalletCoin fromCode(String code) {
		for (WalletCoin c : values()) {
			if (c.code.equals(code)) {
				return c;
			}
		}
		throw new IllegalArgumentException("Unknown coin");
	}
}

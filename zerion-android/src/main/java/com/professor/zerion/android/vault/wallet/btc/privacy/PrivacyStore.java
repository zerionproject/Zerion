package com.professor.zerion.android.vault.wallet.btc.privacy;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

@NotNullByDefault
public interface PrivacyStore {

	Set<String> frozen();

	Map<String, String> labels();

	Map<String, String> originHints();

	void setFrozen(String outpoint, boolean frozen);

	void setLabel(String outpoint, @Nullable String label);

	void putOriginHint(String address, String clusterId);

	PrivacyStore NONE = new PrivacyStore() {
		@Override
		public Set<String> frozen() {
			return Collections.emptySet();
		}

		@Override
		public Map<String, String> labels() {
			return Collections.emptyMap();
		}

		@Override
		public Map<String, String> originHints() {
			return Collections.emptyMap();
		}

		@Override
		public void setFrozen(String outpoint, boolean frozen) {
		}

		@Override
		public void setLabel(String outpoint, @Nullable String label) {
		}

		@Override
		public void putOriginHint(String address, String clusterId) {
		}
	};
}

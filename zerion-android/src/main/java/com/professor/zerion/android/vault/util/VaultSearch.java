package com.professor.zerion.android.vault.util;

import com.professor.zerion.android.vault.model.VaultItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public final class VaultSearch {

	public static final int SORT_NAME = 0;
	public static final int SORT_RECENT = 1;

	private VaultSearch() {
	}

	public static List<VaultItem> filterSort(List<VaultItem> items, String query, int sortMode) {
		List<VaultItem> out = new ArrayList<>();
		String q = query == null ? "" : query.trim().toLowerCase(Locale.US);
		for (VaultItem it : items) {
			if (it == null) continue;
			if (q.isEmpty() || (it.name != null && it.name.toLowerCase(Locale.US).contains(q))) out.add(it);
		}
		if (sortMode == SORT_RECENT)
			Collections.sort(out, (a, b) -> Long.compare(b.modifiedTimestamp, a.modifiedTimestamp));
		else
			Collections.sort(out, (a, b) -> (a.name == null ? "" : a.name).compareToIgnoreCase(b.name == null ? "" : b.name));
		return out;
	}
}

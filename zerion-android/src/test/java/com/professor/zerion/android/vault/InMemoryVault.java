package com.professor.zerion.android.vault;

import com.professor.zerion.android.vault.model.VaultItem;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import javax.annotation.Nullable;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public final class InMemoryVault {

	public final VaultManager manager = mock(VaultManager.class);
	public volatile boolean unlocked = true;
	public volatile long lockGeneration = 1;

	private final Map<String, VaultItem> items = new LinkedHashMap<>();
	private final Map<String, byte[]> contents = new HashMap<>();
	private final Map<String, char[]> passwords = new HashMap<>();
	private long clock = 1_000_000L;

	public InMemoryVault() throws Exception {
		when(manager.vaultExists()).thenReturn(true);
		when(manager.isUnlocked()).thenAnswer(a -> unlocked);
		when(manager.getLockGeneration()).thenAnswer(a -> lockGeneration);
		when(manager.listItems()).thenAnswer(a -> list());
		when(manager.addItem(any(), anyString(), any())).thenAnswer(a ->
				add(a.getArgument(0), a.getArgument(1), a.getArgument(2),
						null));
		when(manager.addMediaItem(any(), anyString(), any(), any()))
				.thenAnswer(a -> add(a.getArgument(0), a.getArgument(1),
						a.getArgument(2), null));
		when(manager.addItemWithPassword(any(), anyString(), any(), any()))
				.thenAnswer(a -> add(a.getArgument(0), a.getArgument(1),
						a.getArgument(2), a.getArgument(3)));
		when(manager.getItemContent(anyString())).thenAnswer(a ->
				content(a.getArgument(0), null, false));
		when(manager.loadDocumentContentSecure(anyString())).thenAnswer(a ->
				content(a.getArgument(0), null, false));
		when(manager.getItemContentWithPassword(anyString(), any()))
				.thenAnswer(a -> content(a.getArgument(0),
						a.getArgument(1), true));
		when(manager.itemHasExtraPassword(anyString())).thenAnswer(a ->
				hasPassword(a.getArgument(0)));
		doAnswer(a -> {
			remove(a.getArgument(0));
			return null;
		}).when(manager).deleteItem(anyString());
		when(manager.withoutActivityRefresh(any())).thenAnswer(a ->
				((Callable<?>) a.getArgument(0)).call());
	}

	public synchronized List<VaultItem> list() {
		return new ArrayList<>(items.values());
	}

	@Nullable
	public synchronized VaultItem named(String name) {
		for (VaultItem i : items.values()) {
			if (i.name.equals(name)) return i;
		}
		return null;
	}

	public synchronized byte[] raw(String id) {
		return contents.get(id).clone();
	}

	public synchronized int size() {
		return items.size();
	}

	private synchronized VaultItem add(VaultItem.ItemType type, String name,
			byte[] content, @Nullable char[] password) {
		VaultItem created = password == null
				? VaultItem.createNew(type, name, content.length, new byte[0],
						new byte[0])
				: VaultItem.createNewWithPassword(type, name, content.length,
						new byte[0], new byte[0], new byte[16], 1, 1, 1);
		long at = ++clock;
		VaultItem item = new VaultItem(created.id, created.type, created.name,
				at, at, created.size, created.thumbnailKey,
				created.hasExtraPassword, created.extraPasswordSalt,
				created.extraPasswordMemoryKb, created.extraPasswordIterations,
				created.extraPasswordParallelism, created.encryptedKey,
				created.nonce, created.version);
		items.put(item.id, item);
		contents.put(item.id, content.clone());
		if (password != null) passwords.put(item.id, password.clone());
		return item;
	}

	private synchronized byte[] content(String id, @Nullable char[] password,
			boolean withPassword) {
		byte[] c = contents.get(id);
		if (c == null) throw new IllegalArgumentException("no item " + id);
		char[] expected = passwords.get(id);
		if (withPassword && (expected == null
				|| !Arrays.equals(expected, password))) {
			throw new SecurityException("Wrong password");
		}
		return c.clone();
	}

	private synchronized boolean hasPassword(String id) {
		return passwords.containsKey(id);
	}

	private synchronized void remove(String id) {
		items.remove(id);
		contents.remove(id);
		passwords.remove(id);
	}
}

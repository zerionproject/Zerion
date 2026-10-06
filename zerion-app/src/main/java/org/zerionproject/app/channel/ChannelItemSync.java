package org.zerionproject.app.channel;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class ChannelItemSync {

	static final int MAX_REMOVED_LOG = 512;
	static final int CURSOR_BYTES = 16;
	private static final int EPOCH_BYTES = 8;
	static final String NS_SHARED_STATE = "zerion-channels-item-sync";
	static final String NS_SHARED_CURSOR = "zerion-channels-sync-cursor";
	private static final String NS_STATE_PREFIX = "zerion-channels-item-sync:";
	private static final String NS_CURSOR_PREFIX =
			"zerion-channels-sync-cursor:";

	private final SettingsManager settings;
	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;
	private final String kind;
	private final SecureRandom random = new SecureRandom();

	ChannelItemSync(SettingsManager settings, BdfReaderFactory readerFactory,
			BdfWriterFactory writerFactory, String kind) {
		this.settings = settings;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
		this.kind = kind;
	}

	static final class Delta {

		final byte[] epoch;
		final long head;
		final boolean full;
		final Set<String> changed;
		final List<String> removed;

		Delta(byte[] epoch, long head, boolean full, Set<String> changed,
				List<String> removed) {
			this.epoch = epoch;
			this.head = head;
			this.full = full;
			this.changed = changed;
			this.removed = removed;
		}

		byte[] cursor() {
			return ChannelItemSync.cursor(epoch, head);
		}
	}

	private static final class State {
		byte[] epoch = new byte[EPOCH_BYTES];
		long head;
		long floor;
		final Map<String, Long> revs = new LinkedHashMap<>();
		final Map<String, byte[]> digests = new LinkedHashMap<>();
		final List<Object[]> removed = new ArrayList<>();
	}

	private static String stateNamespace(byte[] channelId) {
		return NS_STATE_PREFIX + ChannelStore.hex(channelId);
	}

	private static String cursorNamespace(byte[] channelId) {
		return NS_CURSOR_PREFIX + ChannelStore.hex(channelId);
	}

	synchronized void recordWrite(byte[] channelId, List<String> keys,
			List<byte[]> digests) throws DbException {
		State st = load(channelId);
		boolean changed = false;
		if (isZero(st.epoch)) random.nextBytes(st.epoch);
		Set<String> present = new HashSet<>(keys);
		for (String k : new ArrayList<>(st.revs.keySet())) {
			if (present.contains(k)) continue;
			st.head++;
			st.revs.remove(k);
			st.digests.remove(k);
			st.removed.add(new Object[] {st.head, k});
			changed = true;
		}
		for (int i = 0; i < keys.size(); i++) {
			String k = keys.get(i);
			byte[] d = digests.get(i);
			byte[] old = st.digests.get(k);
			if (old != null && Arrays.equals(old, d)) continue;
			st.head++;
			st.revs.put(k, st.head);
			st.digests.put(k, d);
			changed = true;
		}
		while (st.removed.size() > MAX_REMOVED_LOG) {
			Object[] dropped = st.removed.remove(0);
			st.floor = Math.max(st.floor, (Long) dropped[0]);
		}
		if (changed) store(channelId, st);
	}

	synchronized Delta since(byte[] channelId, @Nullable byte[] cursor)
			throws DbException {
		State st = load(channelId);
		boolean known = cursor != null && cursor.length == CURSOR_BYTES
				&& Arrays.equals(Arrays.copyOfRange(cursor, 0, EPOCH_BYTES),
				st.epoch);
		long rev = known ? ByteBuffer.wrap(cursor, EPOCH_BYTES, 8).getLong()
				: -1L;
		if (!known || rev < st.floor || rev > st.head) {
			return new Delta(st.epoch, st.head, true,
					new HashSet<>(st.revs.keySet()),
					Collections.<String>emptyList());
		}
		Set<String> changed = new HashSet<>();
		for (Map.Entry<String, Long> e : st.revs.entrySet()) {
			if (e.getValue() > rev) changed.add(e.getKey());
		}
		List<String> removed = new ArrayList<>();
		for (Object[] r : st.removed) {
			if ((Long) r[0] > rev && !st.revs.containsKey((String) r[1])) {
				removed.add((String) r[1]);
			}
		}
		return new Delta(st.epoch, st.head, false, changed, removed);
	}

	static byte[] cursor(byte[] epoch, long head) {
		return ByteBuffer.allocate(CURSOR_BYTES).put(epoch).putLong(head)
				.array();
	}

	synchronized void restart(byte[] channelId) throws DbException {
		State st = load(channelId);
		byte[] epoch = new byte[EPOCH_BYTES];
		do {
			random.nextBytes(epoch);
		} while (isZero(epoch) || Arrays.equals(epoch, st.epoch));
		st.epoch = epoch;
		st.removed.clear();
		st.floor = st.head;
		store(channelId, st);
	}

	@Nullable
	byte[] cursor(byte[] channelId) throws DbException {
		String v = settings.getSettings(cursorNamespace(channelId)).get(kind);
		if (v == null || v.isEmpty()) return null;
		try {
			byte[] c = java.util.Base64.getDecoder().decode(v);
			return c.length == CURSOR_BYTES ? c : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	void setCursor(byte[] channelId, @Nullable byte[] cursor)
			throws DbException {
		if (cursor == null) {
			settings.deleteSettings(cursorNamespace(channelId),
					Collections.singletonList(kind));
			return;
		}
		Settings s = new Settings();
		s.put(kind, java.util.Base64.getEncoder()
				.withoutPadding().encodeToString(cursor));
		settings.mergeSettings(s, cursorNamespace(channelId));
	}

	synchronized void removeAll(byte[] channelId) throws DbException {
		List<String> k = Collections.singletonList(kind);
		settings.deleteSettings(stateNamespace(channelId), k);
		settings.deleteSettings(cursorNamespace(channelId), k);
	}

	private State load(byte[] channelId) throws DbException {
		String v = settings.getSettings(stateNamespace(channelId)).get(kind);
		State st = new State();
		if (v == null || v.isEmpty()) return st;
		try {
			BdfReader r = readerFactory.createReader(new ByteArrayInputStream(
					java.util.Base64.getDecoder().decode(v)));
			BdfDictionary d = r.readDictionary();
			st.epoch = d.getRaw("epoch");
			if (st.epoch.length != EPOCH_BYTES) throw new FormatException();
			st.head = d.getLong("head");
			st.floor = d.getLong("floor");
			for (Object o : d.getList("items")) {
				BdfList e = (BdfList) o;
				st.revs.put(e.getString(0), e.getLong(1));
				st.digests.put(e.getString(0), e.getRaw(2));
			}
			for (Object o : d.getList("removed")) {
				BdfList e = (BdfList) o;
				st.removed.add(new Object[] {e.getLong(0), e.getString(1)});
			}
			return st;
		} catch (IOException | IllegalArgumentException
				| ClassCastException e) {
			return new State();
		}
	}

	private static boolean isZero(byte[] b) {
		for (byte x : b) {
			if (x != 0) return false;
		}
		return true;
	}

	private void store(byte[] channelId, State st) throws DbException {
		BdfDictionary d = new BdfDictionary();
		d.put("epoch", st.epoch);
		d.put("head", st.head);
		d.put("floor", st.floor);
		BdfList items = new BdfList();
		for (Map.Entry<String, Long> e : st.revs.entrySet()) {
			BdfList entry = new BdfList();
			entry.add(e.getKey());
			entry.add(e.getValue());
			entry.add(st.digests.get(e.getKey()));
			items.add(entry);
		}
		d.put("items", items);
		BdfList removed = new BdfList();
		for (Object[] r : st.removed) {
			BdfList entry = new BdfList();
			entry.add(r[0]);
			entry.add(r[1]);
			removed.add(entry);
		}
		d.put("removed", removed);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = writerFactory.createWriter(out);
		try {
			w.writeDictionary(d);
			w.flush();
		} catch (IOException e) {
			throw new DbException(e);
		}
		Settings s = new Settings();
		s.put(kind, java.util.Base64.getEncoder().withoutPadding()
				.encodeToString(out.toByteArray()));
		settings.mergeSettings(s, stateNamespace(channelId));
	}
}

package org.zerionproject.app.channel;

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
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

@ThreadSafe
@NotNullByDefault
final class ChannelLinkCandidates {

	static final String NS = "zerion-channels-link-candidates";
	static final int MAX_PER_CHANNEL = 4;
	private static final long MINUTE_MS = 60_000L;

	private final SettingsManager settings;
	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;

	ChannelLinkCandidates(SettingsManager settings,
			BdfReaderFactory readerFactory, BdfWriterFactory writerFactory) {
		this.settings = settings;
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
	}

	static final class Candidate {
		final long id;
		final String onion;
		final String origin;
		@Nullable
		final byte[] capability;
		final boolean applied;
		@Nullable
		final byte[] approvedFor;
		final long lastOkMs;
		final long sinceMs;
		final boolean failed;

		Candidate(long id, String onion, String origin,
				@Nullable byte[] capability, boolean applied,
				@Nullable byte[] approvedFor, long lastOkMs, long sinceMs,
				boolean failed) {
			this.id = id;
			this.onion = onion;
			this.origin = origin;
			this.capability = capability;
			this.applied = applied;
			this.approvedFor = approvedFor;
			this.lastOkMs = lastOkMs;
			this.sinceMs = sinceMs;
			this.failed = failed;
		}

		Candidate withOnion(String next) {
			return new Candidate(id, next, origin, capability, applied,
					approvedFor, lastOkMs, sinceMs, failed);
		}

		Candidate withCapability(@Nullable byte[] next,
				@Nullable byte[] fromApproval) {
			return new Candidate(id, onion, origin, next, false,
					fromApproval, lastOkMs, sinceMs, failed);
		}

		Candidate applied(boolean next) {
			return new Candidate(id, onion, origin, capability, next,
					approvedFor, lastOkMs, sinceMs, failed);
		}

		Candidate withoutApproval() {
			return new Candidate(id, onion, origin, capability, applied,
					null, lastOkMs, sinceMs, failed);
		}

		boolean reachedChangesNothing(long now) {
			return !failed && lastOkMs >= 0L
					&& lastOkMs / MINUTE_MS == now / MINUTE_MS;
		}

		Candidate reached(long now) {
			return new Candidate(id, onion, origin, capability, applied,
					approvedFor, now, -1L, false);
		}

		Candidate failedAt(long now) {
			if (failed) return this;
			return new Candidate(id, onion, origin, capability, applied,
					approvedFor, lastOkMs, lastOkMs < 0L && sinceMs < 0L
					? now : sinceMs, true);
		}

		long dormantAt() {
			long reference = lastOkMs >= 0L ? lastOkMs : sinceMs;
			return reference >= 0L
					? reference + ChannelSyncStatusStore.DORMANT_AFTER_MS
					: Long.MAX_VALUE;
		}
	}

	synchronized List<Candidate> list(byte[] channelId) throws DbException {
		String v = settings.getSettings(NS).get(ChannelStore.hex(channelId));
		List<Candidate> out = new ArrayList<>();
		if (v == null || v.isEmpty()) return out;
		try {
			BdfReader reader = readerFactory.createReader(
					new ByteArrayInputStream(
							java.util.Base64.getDecoder().decode(v)));
			for (Object o : reader.readList()) {
				BdfList e = (BdfList) o;
				String onion = e.getString(1);
				long since = e.getLong(4);
				int n = e.size();
				out.add(new Candidate(e.getLong(0), onion,
						n > 5 ? e.getString(5) : onion, e.getOptionalRaw(2),
						e.getBoolean(3), n > 6 ? e.getOptionalRaw(6) : null,
						n > 7 ? e.getLong(7) : -1L, since,
						n > 8 ? e.getBoolean(8) : since >= 0L));
			}
		} catch (IOException | IllegalArgumentException
				| ClassCastException e) {
			return new ArrayList<>();
		}
		return out;
	}

	@Nullable
	synchronized Candidate get(byte[] channelId, long id)
			throws DbException {
		for (Candidate c : list(channelId)) {
			if (c.id == id) return c;
		}
		return null;
	}

	@Nullable
	synchronized Candidate add(byte[] channelId, long id, String origin,
			@Nullable byte[] capability, boolean anyCapability)
			throws DbException {
		List<Candidate> all = list(channelId);
		for (Candidate c : all) {
			if (c.origin.equals(origin) && (anyCapability
					|| java.util.Arrays.equals(c.capability, capability))) {
				return c;
			}
		}
		if (all.size() >= MAX_PER_CHANNEL) return null;
		Candidate fresh = new Candidate(id, origin, origin, capability,
				false, null, -1L, -1L, false);
		all.add(fresh);
		write(channelId, all);
		return fresh;
	}

	synchronized void update(byte[] channelId, Candidate next)
			throws DbException {
		List<Candidate> all = list(channelId);
		for (int i = 0; i < all.size(); i++) {
			if (all.get(i).id == next.id) {
				all.set(i, next);
				write(channelId, all);
				return;
			}
		}
	}

	synchronized void remove(byte[] channelId, long id) throws DbException {
		List<Candidate> all = list(channelId);
		List<Candidate> kept = new ArrayList<>();
		for (Candidate c : all) {
			if (c.id != id) kept.add(c);
		}
		if (kept.size() != all.size()) write(channelId, kept);
	}

	synchronized void removeAll(byte[] channelId) throws DbException {
		settings.deleteSettings(NS, Collections.singletonList(
				ChannelStore.hex(channelId)));
	}

	synchronized List<String> channels() throws DbException {
		List<String> out = new ArrayList<>();
		for (Map.Entry<String, String> e
				: settings.getSettings(NS).entrySet()) {
			if (e.getValue() != null && !e.getValue().isEmpty()) {
				out.add(e.getKey());
			}
		}
		return out;
	}

	private void write(byte[] channelId, List<Candidate> all)
			throws DbException {
		if (all.isEmpty()) {
			removeAll(channelId);
			return;
		}
		BdfList list = new BdfList();
		for (Candidate c : all) {
			BdfList e = new BdfList();
			e.add(c.id);
			e.add(c.onion);
			e.add(c.capability);
			e.add(c.applied);
			e.add(c.sinceMs);
			e.add(c.origin);
			e.add(c.approvedFor);
			e.add(c.lastOkMs);
			e.add(c.failed);
			list.add(e);
		}
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = writerFactory.createWriter(out);
		try {
			w.writeList(list);
			w.flush();
		} catch (IOException e) {
			throw new DbException(e);
		}
		Settings s = new Settings();
		s.put(ChannelStore.hex(channelId), java.util.Base64.getEncoder()
				.withoutPadding().encodeToString(out.toByteArray()));
		settings.mergeSettings(s, NS);
	}
}

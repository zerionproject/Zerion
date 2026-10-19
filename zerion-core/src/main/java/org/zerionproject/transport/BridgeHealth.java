package org.zerionproject.transport;

import org.bouncycastle.util.encoders.Base64;
import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.settings.Settings;
import org.zerionproject.core.api.settings.SettingsManager;
import org.zerionproject.core.api.system.Clock;
import org.zerionproject.tor.BridgeLines;

import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.inject.Inject;
import javax.inject.Singleton;

@Singleton
@ThreadSafe
@NotNullByDefault
public class BridgeHealth {

	public static final String NAMESPACE = "tor-bridge-health";

	public static final int SAFE_MINIMUM = 3;

	static final String KEY_RECORDS = "records";
	static final String KEY_SECRET = "id-secret";
	static final String KEY_OWNER = "owner";
	static final int MAX_FAILURES = 6;
	static final int MAX_BACKOFF_HOURS = 24;
	static final int MAX_RECORDS = 64;
	static final int FORGET_AFTER_DAYS = 30;

	private static final long HOUR_MS = 60L * 60 * 1000;
	private static final Charset ASCII = Charset.forName("US-ASCII");
	private static final byte[] ID_PREFIX =
			"zerion-bridge-health-v1\n".getBytes(ASCII);
	private static final Pattern RECORD =
			Pattern.compile("[0-9a-f]{16}:[0-9]{1,2}:[0-9]{1,9}:[0-9]{1,7}");
	private static final int SECRET_BYTES = 32;

	public interface Owner {
		String id();
	}

	private static final class State {
		final Map<String, Record> records;
		@Nullable
		final byte[] secret;

		State(Map<String, Record> records, @Nullable byte[] secret) {
			this.records = records;
			this.secret = secret;
		}
	}

	static final class Record {
		final int failures;
		final long failedHour;
		final long okDay;

		Record(int failures, long failedHour, long okDay) {
			this.failures = failures;
			this.failedHour = failedHour;
			this.okDay = okDay;
		}

		long resumeHour() {
			if (failures == 0) return 0;
			return failedHour + backoffHours(failures) + 1;
		}

		long lastDay() {
			return Math.max(okDay, failedHour / 24);
		}
	}

	private final SettingsManager settingsManager;
	private final Clock clock;
	private final Owner owner;
	private final SecureRandom random = new SecureRandom();
	private final Object lock = new Object();

	@Inject
	public BridgeHealth(SettingsManager settingsManager, Clock clock,
			Owner owner) {
		this.settingsManager = settingsManager;
		this.clock = clock;
		this.owner = owner;
	}

	static long backoffHours(int failures) {
		if (failures <= 0) return 0;
		long hours = 1L << Math.min(failures - 1, 30);
		return Math.min(hours, MAX_BACKOFF_HOURS);
	}

	public void record(String fingerprint, boolean connected) {
		long hour = hour();
		long day = hour / 24;
		synchronized (lock) {
			State state = load();
			if (state == null) return;
			byte[] secret = state.secret == null ? newSecret() : state.secret;
			Map<String, Record> records = state.records;
			String id = id(secret, fingerprint);
			Record old = records.get(id);
			Record updated;
			if (connected) {
				if (old != null && old.failures == 0 && old.okDay == day) {
					return;
				}
				updated = new Record(0, old == null ? 0 : old.failedHour, day);
			} else {
				if (old != null && old.failures > 0
						&& hour < old.resumeHour()) {
					return;
				}
				int failures = old == null ? 1
						: Math.min(old.failures + 1, MAX_FAILURES);
				updated = new Record(failures, hour,
						old == null ? 0 : old.okDay);
			}
			records.remove(id);
			records.put(id, updated);
			save(prune(records, day), secret);
		}
	}

	public void maintain() {
		synchronized (lock) {
			load();
		}
	}

	public boolean allFailing(List<String> lines) {
		if (lines.isEmpty()) return false;
		State state;
		synchronized (lock) {
			state = load();
		}
		if (state == null || state.secret == null) return false;
		for (String line : lines) {
			String fp = BridgeLines.fingerprint(line);
			Record r = fp == null ? null
					: state.records.get(id(state.secret, fp));
			if (r == null || r.failures == 0) return false;
		}
		return true;
	}

	public List<String> select(List<String> lines) {
		State state;
		synchronized (lock) {
			state = load();
		}
		if (state == null || state.secret == null
				|| state.records.isEmpty()) {
			return lines;
		}
		Map<String, Record> records = state.records;
		byte[] secret = state.secret;
		long hour = hour();
		List<String> healthy = new ArrayList<>();
		List<String> retrying = new ArrayList<>();
		final Map<String, Long> resting = new LinkedHashMap<>();
		for (String line : lines) {
			String fp = BridgeLines.fingerprint(line);
			Record r = fp == null ? null : records.get(id(secret, fp));
			if (r == null || r.failures == 0) {
				healthy.add(line);
			} else if (hour >= r.resumeHour()) {
				retrying.add(line);
			} else {
				resting.put(line, r.resumeHour());
			}
		}
		if (resting.isEmpty()) {
			if (retrying.isEmpty()) return lines;
			healthy.addAll(retrying);
			return healthy;
		}
		if (healthy.isEmpty() && retrying.isEmpty()) return lines;
		List<String> out = new ArrayList<>(healthy);
		out.addAll(retrying);
		int minimum = Math.min(SAFE_MINIMUM, lines.size());
		if (out.size() < minimum) {
			List<String> soonest = new ArrayList<>(resting.keySet());
			Collections.sort(soonest, new Comparator<String>() {
				@Override
				public int compare(String a, String b) {
					long x = resting.get(a);
					long y = resting.get(b);
					return x < y ? -1 : (x == y ? 0 : 1);
				}
			});
			for (String line : soonest) {
				if (out.size() >= minimum) break;
				out.add(line);
			}
		}
		return out;
	}

	String id(String fingerprint) {
		synchronized (lock) {
			State state = load();
			return state == null || state.secret == null ? ""
					: id(state.secret, fingerprint);
		}
	}

	static String id(byte[] secret, String fingerprint) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(secret, "HmacSHA256"));
			mac.update(ID_PREFIX);
			byte[] d = mac.doFinal(fingerprint.getBytes(ASCII));
			StringBuilder sb = new StringBuilder(16);
			for (int i = 0; i < 8; i++) {
				sb.append(Character.forDigit((d[i] >> 4) & 0xF, 16));
				sb.append(Character.forDigit(d[i] & 0xF, 16));
			}
			return sb.toString();
		} catch (GeneralSecurityException e) {
			throw new AssertionError(e);
		}
	}

	private byte[] newSecret() {
		byte[] secret = new byte[SECRET_BYTES];
		random.nextBytes(secret);
		return secret;
	}

	private long hour() {
		long ms = clock.currentTimeMillis();
		return ms <= 0 ? 0 : ms / HOUR_MS;
	}

	@Nullable
	private State load() {
		Settings s;
		try {
			s = settingsManager.getSettings(NAMESPACE);
		} catch (DbException e) {
			return null;
		}
		String current = owner.id();
		String stored = s.get(KEY_OWNER);
		String records = s.get(KEY_RECORDS);
		byte[] secret = decodeSecret(s.get(KEY_SECRET));
		if (secret == null || !current.equals(stored)) {
			boolean leftover = (stored != null && !stored.isEmpty())
					|| (records != null && !records.isEmpty())
					|| secret != null;
			if (leftover) clear();
			return new State(new LinkedHashMap<String, Record>(), null);
		}
		Map<String, Record> decoded = decode(records);
		long hour = hour();
		Map<String, Record> kept =
				prune(withoutFuture(decoded, hour), hour / 24);
		if (kept.isEmpty()) {
			clear();
			return new State(new LinkedHashMap<String, Record>(), null);
		}
		if (kept.size() != decoded.size()) save(kept, secret);
		return new State(kept, secret);
	}

	@Nullable
	private static byte[] decodeSecret(@Nullable String value) {
		if (value == null || value.isEmpty()) return null;
		try {
			byte[] secret = Base64.decode(value);
			return secret.length == SECRET_BYTES ? secret : null;
		} catch (RuntimeException e) {
			return null;
		}
	}

	private void clear() {
		try {
			settingsManager.deleteSettings(NAMESPACE,
					Arrays.asList(KEY_RECORDS, KEY_SECRET, KEY_OWNER));
		} catch (DbException ignored) {
		}
	}

	static Map<String, Record> withoutFuture(Map<String, Record> records,
			long hour) {
		Map<String, Record> out = new LinkedHashMap<>();
		for (Map.Entry<String, Record> e : records.entrySet()) {
			Record r = e.getValue();
			if (r.failedHour > hour + 1 || r.okDay > hour / 24 + 1) continue;
			out.put(e.getKey(), r);
		}
		return out;
	}

	private void save(Map<String, Record> records, byte[] secret) {
		Settings update = new Settings();
		update.put(KEY_RECORDS, encode(records));
		update.put(KEY_SECRET, Base64.toBase64String(secret));
		update.put(KEY_OWNER, owner.id());
		try {
			settingsManager.mergeSettings(update, NAMESPACE);
		} catch (DbException ignored) {
		}
	}

	static Map<String, Record> decode(@Nullable String value) {
		Map<String, Record> out = new LinkedHashMap<>();
		if (value == null || value.isEmpty()) return out;
		for (String entry : value.split(";")) {
			if (out.size() >= MAX_RECORDS) break;
			if (!RECORD.matcher(entry).matches()) continue;
			String[] f = entry.split(":");
			int failures = Integer.parseInt(f[1]);
			if (failures > MAX_FAILURES) continue;
			out.put(f[0], new Record(failures, Long.parseLong(f[2]),
					Long.parseLong(f[3])));
		}
		return out;
	}

	static String encode(Map<String, Record> records) {
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Record> e : records.entrySet()) {
			if (sb.length() > 0) sb.append(';');
			Record r = e.getValue();
			sb.append(e.getKey()).append(':').append(r.failures).append(':')
					.append(r.failedHour).append(':').append(r.okDay);
		}
		return sb.toString();
	}

	private static Map<String, Record> prune(Map<String, Record> records,
			long today) {
		Map<String, Record> out = new LinkedHashMap<>();
		for (Map.Entry<String, Record> e : records.entrySet()) {
			if (today - e.getValue().lastDay() <= FORGET_AFTER_DAYS) {
				out.put(e.getKey(), e.getValue());
			}
		}
		while (out.size() > MAX_RECORDS) {
			out.remove(out.keySet().iterator().next());
		}
		return out;
	}
}

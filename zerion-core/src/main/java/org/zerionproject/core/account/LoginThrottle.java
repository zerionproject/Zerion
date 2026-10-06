package org.zerionproject.core.account;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

import javax.annotation.Nullable;
import javax.annotation.concurrent.ThreadSafe;

import static org.zerionproject.core.util.StringUtils.UTF_8;

@ThreadSafe
@NotNullByDefault
public final class LoginThrottle {

	private static final int FORMAT = 1;

	public interface Policy {

		int freeFailures();

		long lockoutMs(int failures);

		long decayMs();
	}

	public static final long NEVER_DECAYS = Long.MAX_VALUE;

	public static final Policy SIGN_IN = new Policy() {
		@Override
		public int freeFailures() {
			return 2;
		}

		@Override
		public long lockoutMs(int failures) {
			int doublings = Math.max(0, failures - 3);
			long duration = 300_000L << Math.min(doublings, 20);
			return Math.min(duration, 86_400_000L);
		}

		@Override
		public long decayMs() {
			return NEVER_DECAYS;
		}
	};

	public static final Policy VAULT = new Policy() {
		@Override
		public int freeFailures() {
			return 0;
		}

		@Override
		public long lockoutMs(int failures) {
			int n = Math.min(failures, 10);
			return 1000L * (2L * n - 1L);
		}

		@Override
		public long decayMs() {
			return 60_000L;
		}
	};

	public interface Store {

		@Nullable
		String load();

		void save(String state);

		void clear();
	}

	private final Store store;
	private final LongSupplier monotonicMs;
	private final Supplier<String> bootId;
	private final Policy policy;

	private int failures = 0;
	private long lastFailureAt = 0;
	private long lockedUntil = 0;

	public LoginThrottle(Store store, LongSupplier monotonicMs,
			Supplier<String> bootId, Policy policy) {
		this.store = store;
		this.monotonicMs = monotonicMs;
		this.bootId = bootId;
		this.policy = policy;
		load();
	}

	public static LoginThrottle inFile(File stateFile, Policy policy) {
		return new LoginThrottle(fileStore(stateFile),
				() -> System.nanoTime() / 1_000_000L, linuxBootId(), policy);
	}

	public synchronized int failures() {
		decayIfQuiet(monotonicMs.getAsLong());
		return failures;
	}

	public synchronized long remainingLockoutMs() {
		long now = monotonicMs.getAsLong();
		decayIfQuiet(now);
		return Math.max(0, lockedUntil - now);
	}

	public synchronized long recordFailure() {
		long now = monotonicMs.getAsLong();
		decayIfQuiet(now);
		failures++;
		lastFailureAt = now;
		long lockout = 0;
		if (failures > policy.freeFailures()) {
			lockout = policy.lockoutMs(failures);
			lockedUntil = now + lockout;
		}
		save();
		return lockout;
	}

	public static final class Attempt {

		private final int failures;
		private final long lastFailureAt;
		private final long lockedUntil;

		private Attempt(int failures, long lastFailureAt, long lockedUntil) {
			this.failures = failures;
			this.lastFailureAt = lastFailureAt;
			this.lockedUntil = lockedUntil;
		}
	}

	public synchronized Attempt beginAttempt() {
		long now = monotonicMs.getAsLong();
		decayIfQuiet(now);
		Attempt before = new Attempt(failures, lastFailureAt, lockedUntil);
		recordFailure();
		return before;
	}

	public synchronized void cancel(Attempt attempt) {
		failures = attempt.failures;
		lastFailureAt = attempt.lastFailureAt;
		lockedUntil = attempt.lockedUntil;
		save();
	}

	public synchronized void reset() {
		failures = 0;
		lastFailureAt = 0;
		lockedUntil = 0;
		store.clear();
	}

	private void decayIfQuiet(long now) {
		if (failures == 0) return;
		if (policy.decayMs() == NEVER_DECAYS) return;
		if (now < lockedUntil) return;
		if (now - Math.max(lastFailureAt, lockedUntil) > policy.decayMs()) {
			failures = 0;
			lastFailureAt = 0;
			lockedUntil = 0;
			save();
		}
	}

	private void load() {
		String line;
		try {
			line = store.load();
		} catch (RuntimeException e) {
			return;
		}
		if (line == null) return;
		String[] parts = line.split(",", -1);
		if (parts.length != 5) {
			store.clear();
			return;
		}
		try {
			if (Integer.parseInt(parts[0]) != FORMAT) {
				store.clear();
				return;
			}
			int storedFailures = Math.max(0, Integer.parseInt(parts[1]));
			String storedBoot = parts[2];
			long storedLast = Long.parseLong(parts[3]);
			long storedUntil = Long.parseLong(parts[4]);
			long now = monotonicMs.getAsLong();
			failures = storedFailures;
			if (storedBoot.equals(bootId.get())) {
				lastFailureAt = storedLast;
				lockedUntil = storedUntil;
			} else {
				lastFailureAt = now;
				lockedUntil = failures > policy.freeFailures()
						? now + policy.lockoutMs(failures) : 0;
				save();
			}
		} catch (NumberFormatException e) {
			store.clear();
		}
	}

	private void save() {
		if (failures == 0) {
			store.clear();
			return;
		}
		store.save(FORMAT + "," + failures + "," + bootId.get() + ","
				+ lastFailureAt + "," + lockedUntil);
	}

	public static Supplier<String> linuxBootId() {
		return () -> {
			File f = new File("/proc/sys/kernel/random/boot_id");
			try (BufferedReader r = new BufferedReader(new InputStreamReader(
					new FileInputStream(f), UTF_8))) {
				String id = r.readLine();
				return id == null ? "" : id.trim().replace(",", "");
			} catch (IOException | RuntimeException e) {
				return "";
			}
		};
	}

	public static Store fileStore(File f) {
		return new Store() {
			@Override
			@Nullable
			public String load() {
				if (!f.exists()) return null;
				try (BufferedReader r = new BufferedReader(new InputStreamReader(
						new FileInputStream(f), UTF_8))) {
					return r.readLine();
				} catch (IOException e) {
					return null;
				}
			}

			@Override
			public void save(String state) {
				try {
					writeDurably(f, state.getBytes(UTF_8));
				} catch (IOException ignored) {
				}
			}

			@Override
			public void clear() {
				if (f.exists() && !f.delete()) {
					try {
						writeDurably(f, new byte[0]);
					} catch (IOException ignored) {
					}
				}
			}
		};
	}

	public static void writeDurably(File target, byte[] data)
			throws IOException {
		File dir = target.getAbsoluteFile().getParentFile();
		if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
			throw new IOException("cannot create " + dir);
		}
		File tmp = new File(dir, target.getName() + ".tmp");
		try (FileOutputStream out = new FileOutputStream(tmp)) {
			out.write(data);
			out.flush();
			out.getFD().sync();
		}
		try {
			Files.move(tmp.toPath(), target.toPath(),
					StandardCopyOption.ATOMIC_MOVE,
					StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			if (!tmp.renameTo(target)) {
				tmp.delete();
				throw e;
			}
		}
		syncDirectory(dir);
	}

	public static void syncDirectory(@Nullable File dir) {
		if (dir == null) return;
		try (FileChannel c = FileChannel.open(dir.toPath(),
				StandardOpenOption.READ)) {
			c.force(true);
		} catch (IOException | RuntimeException ignored) {
		}
	}
}

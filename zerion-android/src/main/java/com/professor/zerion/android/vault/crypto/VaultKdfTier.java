package com.professor.zerion.android.vault.crypto;

import android.app.ActivityManager;
import android.content.Context;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class VaultKdfTier {

	public static final long TIER_A_MIN_TOTAL_RAM_BYTES = 6_000_000_000L;
	public static final long TIER_B_MIN_TOTAL_RAM_BYTES = 3_500_000_000L;

	public static final int TIER_A_MEMORY_KB = 1024 * 1024;
	public static final int TIER_A_ITERATIONS = 1;
	public static final int TIER_B_MEMORY_KB = 512 * 1024;
	public static final int TIER_B_ITERATIONS = 2;
	public static final int TIER_PARALLELISM = 1;

	public static final Argon2.Argon2Params TIER_A = new Argon2.Argon2Params(
			TIER_A_MEMORY_KB, TIER_A_ITERATIONS, TIER_PARALLELISM,
			Argon2.DEFAULT_HASH_LENGTH);
	public static final Argon2.Argon2Params TIER_B = new Argon2.Argon2Params(
			TIER_B_MEMORY_KB, TIER_B_ITERATIONS, TIER_PARALLELISM,
			Argon2.DEFAULT_HASH_LENGTH);
	public static final Argon2.Argon2Params TIER_C = new Argon2.Argon2Params(
			Argon2.DEFAULT_MEMORY_KB, Argon2.DEFAULT_ITERATIONS,
			Argon2.DEFAULT_PARALLELISM, Argon2.DEFAULT_HASH_LENGTH);

	private VaultKdfTier() {
	}

	public interface NativeCheck {
		boolean works();
	}

	public static Argon2.Argon2Params choose(long totalRamBytes,
			boolean is64BitProcess, boolean lowRamDevice,
			NativeCheck nativeCheck) {
		if (!is64BitProcess || lowRamDevice
				|| totalRamBytes < TIER_B_MIN_TOTAL_RAM_BYTES) {
			return TIER_C;
		}
		if (!nativeCheck.works()) return TIER_C;
		if (totalRamBytes >= TIER_A_MIN_TOTAL_RAM_BYTES) return TIER_A;
		return TIER_B;
	}

	public static Argon2.Argon2Params forThisDevice(Context context) {
		try {
			ActivityManager am = (ActivityManager) context.getSystemService(
					Context.ACTIVITY_SERVICE);
			if (am == null) return TIER_C;
			ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
			am.getMemoryInfo(info);
			return choose(info.totalMem, android.os.Process.is64Bit(),
					am.isLowRamDevice(), Argon2::nativeSelfTestPasses);
		} catch (RuntimeException e) {
			return TIER_C;
		}
	}

	public static boolean sameCost(Argon2.Argon2Params a,
			Argon2.Argon2Params b) {
		return a.memoryKb == b.memoryKb && a.iterations == b.iterations
				&& a.parallelism == b.parallelism;
	}

	public static boolean atLeast(int memoryKb, int iterations,
			Argon2.Argon2Params target) {
		if (memoryKb != target.memoryKb) return memoryKb > target.memoryKb;
		return iterations >= target.iterations;
	}

	public static boolean atLeast(Argon2.Argon2Params p,
			Argon2.Argon2Params target) {
		return atLeast(p.memoryKb, p.iterations, target);
	}

	@Nullable
	public static Argon2.Argon2Params knownTier(int memoryKb, int iterations,
			int parallelism) {
		Argon2.Argon2Params[] tiers = {TIER_A, TIER_B, TIER_C};
		for (Argon2.Argon2Params t : tiers) {
			if (t.memoryKb == memoryKb && t.iterations == iterations
					&& t.parallelism == parallelism) {
				return t;
			}
		}
		return null;
	}
}

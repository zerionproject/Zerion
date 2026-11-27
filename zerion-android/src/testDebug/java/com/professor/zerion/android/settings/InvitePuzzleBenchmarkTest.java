package com.professor.zerion.android.settings;

import org.junit.Test;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.contact.request.ContactRequestCrypto;
import org.zerionproject.core.crypto.FastPasswordCryptoForTests;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class InvitePuzzleBenchmarkTest {

	@Test
	public void theBenchmarkMeasuresThePaidLevelsUpToTheCap() {
		assertArrayEquals(new int[] {16, 20, 24},
				InvitePuzzleBenchmark.paidDifficulties());
	}

	@Test
	public void eachLevelIsSolvedWithTheRealSolverAndTimed() {
		CryptoComponent crypto = FastPasswordCryptoForTests.create();
		AtomicLong nanos = new AtomicLong();
		long[] ms = InvitePuzzleBenchmark.measure(
				new ContactRequestCrypto(crypto), new int[] {0, 4, 8, 25},
				new Random(7), () -> nanos.addAndGet(3_000_000L));
		assertEquals(3, ms[0]);
		assertEquals(3, ms[1]);
		assertEquals(3, ms[2]);
		assertEquals(InvitePuzzleBenchmark.NOT_SOLVED, ms[3]);
	}

	@Test
	public void theBenchmarkExistsOnlyInDebugBuilds() throws Exception {
		assertTrue(new File("src/debug/java/com/professor/zerion/android/"
				+ "settings/InvitePuzzleBenchmark.java").isFile());
		assertFalse(new File("src/main/java/com/professor/zerion/android/"
				+ "settings/InvitePuzzleBenchmark.java").exists());
		String release = new String(Files.readAllBytes(new File(
				"src/release/java/com/professor/zerion/android/settings/"
						+ "LifecycleTests.java").toPath()),
				StandardCharsets.UTF_8);
		assertFalse(release.contains("Puzzle"));
	}
}

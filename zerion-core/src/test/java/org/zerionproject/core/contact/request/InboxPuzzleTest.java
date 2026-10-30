package org.zerionproject.core.contact.request;

import org.junit.Test;
import org.zerionproject.core.contact.request.ContactRequestRecords.Hello;
import org.zerionproject.core.contact.request.ContactRequestRecords.Solution;
import org.zerionproject.core.crypto.PasswordCryptoForTests;
import org.zerionproject.core.test.BrambleTestCase;

import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.DIFFICULTY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.EPOCH_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_MAX_LEVEL_EPOCHS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_WINDOW_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.FLOOD_ZERO_WORK_PER_10_MIN;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.REPLAY_WINDOW_MS;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;

public class InboxPuzzleTest extends BrambleTestCase {

	private static final long START = 2_000_000L * EPOCH_MS;

	private final ContactRequestCrypto crypto =
			new ContactRequestCrypto(PasswordCryptoForTests.create());
	private final byte[] secret = getRandomBytes(32);
	private final byte[] linkId = getRandomBytes(32);
	private final Random random = new Random(7);
	private final AtomicInteger floods = new AtomicInteger();
	private InboxPuzzle puzzle = new InboxPuzzle(crypto, secret, linkId,
			floods::incrementAndGet);
	private long counter = 0;

	private Solution solve(InboxPuzzle p, long epoch, int level) {
		byte[] x = crypto.solve(p.nonce(epoch, level), linkId,
				DIFFICULTY[level], random, () -> false);
		assertNotNull(x);
		return new Solution(epoch, level, x);
	}

	private void events(int n, long at) {
		for (int i = 0; i < n; i++) {
			byte[] x = new byte[8];
			x[0] = (byte) (counter >> 8);
			x[1] = (byte) counter++;
			x[7] = 1;
			assertTrue(puzzle.admit(new Solution(at / EPOCH_MS, 3, x), at));
		}
	}

	@Test
	public void theThresholdsGiveTheFourLevels() {
		assertEquals(0, InboxPuzzle.levelFor(0));
		assertEquals(0, InboxPuzzle.levelFor(9));
		assertEquals(1, InboxPuzzle.levelFor(10));
		assertEquals(1, InboxPuzzle.levelFor(29));
		assertEquals(2, InboxPuzzle.levelFor(30));
		assertEquals(2, InboxPuzzle.levelFor(99));
		assertEquals(3, InboxPuzzle.levelFor(100));
		assertEquals(3, InboxPuzzle.levelFor(100000));
		assertEquals(24, DIFFICULTY[3]);
		assertEquals(4, DIFFICULTY.length);
	}

	@Test
	public void theLevelRisesAtOnceWithinAnEpochAndNeverFalls() {
		assertEquals(0, puzzle.hello(START).level);
		events(9, START + 1);
		assertEquals(0, puzzle.hello(START + 2).level);
		events(1, START + 3);
		Hello h = puzzle.hello(START + 4);
		assertEquals(1, h.level);
		assertEquals(16, h.difficulty);
		events(20, START + 5);
		assertEquals(2, puzzle.getLevel(START + 6));
		assertEquals(2, puzzle.getLevel(START + EPOCH_MS - 1));
	}

	@Test
	public void eachEpochStartsFromTheEventsOfTheLastTenMinutes() {
		events(35, START + 1000);
		assertEquals(2, puzzle.getLevel(START + 2000));
		assertEquals(2, puzzle.getLevel(START + EPOCH_MS));
		assertEquals(0, puzzle.getLevel(START + 2 * EPOCH_MS));
	}

	@Test
	public void connectionFloodsAloneHoldLevelOneAtMost() {
		for (int i = 0; i < 30; i++) puzzle.zeroWork(START + i);
		assertEquals(0, puzzle.getLevel(START + 100));
		for (int i = 0; i < 1000; i++) puzzle.zeroWork(START + 200 + i);
		assertEquals(1, puzzle.getLevel(START + 2000));
		assertEquals(1, puzzle.getLevel(START + EPOCH_MS - 1));
		assertEquals(0, puzzle.getLevel(START + 2 * EPOCH_MS));
	}

	@Test
	public void zeroWorkOlderThanAMinuteDoesNotCount() {
		for (int i = 0; i < 31; i++) {
			puzzle.zeroWork(START + i * 2001L);
		}
		assertEquals(0, puzzle.getLevel(START + 31 * 2001L));
	}

	@Test
	public void theCurrentLevelAndOneBelowAreAcceptedInTheCurrentEpoch() {
		long now = START + 10;
		events(10, now);
		Hello h = puzzle.hello(now);
		assertEquals(1, h.level);
		Solution atLevel = solve(puzzle, h.epoch, 1);
		assertTrue(puzzle.isValid(atLevel, null, now));
		Solution zero = new Solution(h.epoch, 0, getRandomBytes(8));
		assertFalse(puzzle.isValid(zero, h, now));
		Hello zeroHello = new Hello(1, 0, 0, h.epoch, puzzle.nonce(h.epoch, 0));
		assertTrue(puzzle.isValid(zero, zeroHello, now));
		assertFalse(puzzle.isValid(zero, null, now));
		events(20, now);
		assertEquals(2, puzzle.getLevel(now));
		assertTrue(puzzle.isValid(atLevel, null, now));
		assertFalse(puzzle.isValid(zero, zeroHello, now));
	}

	@Test
	public void thePreviousEpochFinalLevelAndOneBelowAreAccepted() {
		long now = START + 10;
		events(10, now);
		Hello h = puzzle.hello(now);
		Solution s = solve(puzzle, h.epoch, 1);
		long next = START + EPOCH_MS + 5;
		assertTrue(puzzle.isValid(s, null, next));
		long later = START + 2 * EPOCH_MS + 5;
		assertFalse(puzzle.isValid(s, null, later));
	}

	@Test
	public void aWrongSolutionOrOneForAnotherLinkIsRefused() {
		long now = START + 10;
		events(10, now);
		Hello h = puzzle.hello(now);
		Solution s = solve(puzzle, h.epoch, 1);
		byte[] wrong = s.x.clone();
		do {
			wrong[7]++;
		} while (crypto.isSolution(puzzle.nonce(h.epoch, 1), linkId, wrong,
				16));
		assertFalse(puzzle.isValid(new Solution(h.epoch, 1, wrong), null,
				now));
		byte[] otherLink = getRandomBytes(32);
		InboxPuzzle other = new InboxPuzzle(crypto, secret, otherLink);
		for (int i = 0; i < 10; i++) {
			other.admit(new Solution(h.epoch, 3, getRandomBytes(8)), now);
		}
		assertEquals(crypto.isSolution(other.nonce(h.epoch, 1), otherLink,
				s.x, 16), other.isValid(s, null, now));
		assertFalse(puzzle.isValid(new Solution(h.epoch, 4, s.x), null, now));
		assertFalse(puzzle.isValid(new Solution(h.epoch + 1, 1, s.x), null,
				now));
	}

	@Test
	public void anAdmittedSolutionIsNotAcceptedAgain() {
		long now = START + 10;
		events(10, now);
		Hello h = puzzle.hello(now);
		Solution s = solve(puzzle, h.epoch, 1);
		assertTrue(puzzle.isValid(s, null, now));
		assertTrue(puzzle.admit(s, now));
		assertFalse(puzzle.isValid(s, null, now + 1));
		assertFalse(puzzle.admit(s, now + 1));
		assertFalse(puzzle.isValid(s, null, now + REPLAY_WINDOW_MS - 1));
	}

	@Test
	public void aRestartVoidsOutstandingSolutions() {
		long now = START + 10;
		events(10, now);
		Hello h = puzzle.hello(now);
		Solution s = solve(puzzle, h.epoch, 1);
		puzzle = new InboxPuzzle(crypto, getRandomBytes(32), linkId);
		counter = 0;
		events(10, now);
		assertFalse(puzzle.isValid(s, null, now));
	}

	@Test
	public void validationAloneCountsNoEvent() {
		long now = START + 10;
		Hello h = puzzle.hello(now);
		for (int i = 0; i < 50; i++) {
			Solution s = new Solution(h.epoch, 0, getRandomBytes(8));
			assertTrue(puzzle.isValid(s, h, now));
		}
		assertEquals(0, puzzle.getLevel(now));
	}

	@Test
	public void aBurstOfFiftySolvedRequestsIsNotAFlood() {
		events(50, START + 1);
		for (int i = 0; i < 50; i++) puzzle.zeroWork(START + 2 + i);
		assertEquals(2, puzzle.getLevel(START + 100));
		assertFalse(puzzle.isFlooded());
		assertEquals(0, floods.get());
		assertEquals(2, puzzle.getLevel(START + EPOCH_MS));
		assertEquals(0, puzzle.getLevel(START + 2 * EPOCH_MS));
		assertFalse(puzzle.isFlooded());
		assertEquals(0, floods.get());
	}

	@Test
	public void aSustainedZeroWorkFloodIsReportedOnce() {
		assertEquals(300, FLOOD_ZERO_WORK_PER_10_MIN);
		for (int i = 0; i < FLOOD_ZERO_WORK_PER_10_MIN; i++) {
			puzzle.zeroWork(START + i * 1000L);
		}
		assertFalse("the limit itself is not a flood", puzzle.isFlooded());
		assertEquals(0, floods.get());
		puzzle.zeroWork(START + FLOOD_ZERO_WORK_PER_10_MIN * 1000L);
		assertTrue(puzzle.isFlooded());
		assertEquals(1, floods.get());
		puzzle.zeroWork(START + FLOOD_ZERO_WORK_PER_10_MIN * 1000L + 1);
		puzzle.hello(START + FLOOD_ZERO_WORK_PER_10_MIN * 1000L + 2);
		assertEquals("reported once", 1, floods.get());
	}

	@Test
	public void zeroWorkOlderThanTenMinutesDoesNotCountTowardsAFlood() {
		for (int i = 0; i < 200; i++) puzzle.zeroWork(START + i);
		long later = START + FLOOD_WINDOW_MS;
		for (int i = 0; i < 200; i++) puzzle.zeroWork(later + i);
		assertFalse(puzzle.isFlooded());
		for (int i = 0; i < 101; i++) puzzle.zeroWork(later + 200 + i);
		assertTrue(puzzle.isFlooded());
	}

	@Test
	public void levelThreeHeldForThreeEpochsIsAFlood() {
		assertEquals(3, FLOOD_MAX_LEVEL_EPOCHS);
		events(100, START + 1);
		assertEquals(3, puzzle.getLevel(START + 2));
		assertEquals(1, puzzle.getMaxLevelEpochs());
		events(100, START + EPOCH_MS + 1);
		assertEquals(3, puzzle.getLevel(START + EPOCH_MS + 2));
		assertEquals(2, puzzle.getMaxLevelEpochs());
		assertFalse(puzzle.isFlooded());
		assertEquals(3, puzzle.getLevel(START + 2 * EPOCH_MS + 1));
		assertEquals(3, puzzle.getMaxLevelEpochs());
		assertTrue(puzzle.isFlooded());
		assertEquals(1, floods.get());
	}

	@Test
	public void theLevelThreeTimerResetsWhenTheLevelFalls() {
		events(100, START + 1);
		assertEquals(3, puzzle.getLevel(START + EPOCH_MS + 1));
		assertEquals(2, puzzle.getMaxLevelEpochs());
		assertEquals(0, puzzle.getLevel(START + 2 * EPOCH_MS + 1));
		assertEquals(0, puzzle.getMaxLevelEpochs());
		events(100, START + 2 * EPOCH_MS + 2);
		assertEquals(3, puzzle.getLevel(START + 2 * EPOCH_MS + 3));
		assertEquals(1, puzzle.getMaxLevelEpochs());
		assertEquals(3, puzzle.getLevel(START + 3 * EPOCH_MS + 1));
		assertEquals(2, puzzle.getMaxLevelEpochs());
		assertFalse(puzzle.isFlooded());
		assertEquals(0, puzzle.getLevel(START + 5 * EPOCH_MS + 1));
		assertEquals(0, puzzle.getMaxLevelEpochs());
		assertFalse(puzzle.isFlooded());
		assertEquals(0, floods.get());
	}
}

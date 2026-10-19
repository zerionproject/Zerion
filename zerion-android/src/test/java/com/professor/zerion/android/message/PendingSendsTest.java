package com.professor.zerion.android.message;

import com.professor.zerion.R;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PendingSendsTest {

	private static final String SCOPE = "s";

	private final PendingSends store = new PendingSends();
	private final List<Runnable> io = new ArrayList<>();
	private final AtomicInteger attempts = new AtomicInteger();
	private final List<PendingSends.Change> heard = new ArrayList<>();
	private final Set<String> shown = new HashSet<>();
	private long now = 1_000_000L;

	@Before
	public void setUp() {
		store.clock = () -> now;
		store.addListener(heard::add, SCOPE);
	}

	private PendingSends.Attempt failing() {
		return () -> {
			attempts.incrementAndGet();
			return PendingSends.Result.failed(null);
		};
	}

	private PendingSends.Attempt storing(String identity) {
		return () -> {
			attempts.incrementAndGet();
			return PendingSends.Result.stored(identity, null);
		};
	}

	private PendingSends.Entry add(String text, PendingSends.Attempt a) {
		return store.add(SCOPE, text, null, text.length(), true, a);
	}

	private List<PendingSends.Entry> visible() {
		return PendingSends.visible(store.entries(SCOPE), shown::contains);
	}

	private void runIo() {
		while (!io.isEmpty()) io.remove(0).run();
	}

	@Test
	public void anEntryIsSendingUntilItsAttemptHasRunOnce() {
		PendingSends.Entry e = add("hello", storing("a"));
		assertEquals(MessageState.SENDING, e.getState());
		assertTrue(store.start(e, io::add));
		assertFalse("a second start sends nothing", store.start(e, io::add));
		runIo();
		assertEquals(1, attempts.get());
		assertEquals("stored, waiting for its item", MessageState.SENT,
				e.getState());
	}

	@Test
	public void aSendReadsAsFinishedOnlyOnceAllOfItIsWritten() {
		List<MessageState> seen = new ArrayList<>();
		List<String> identities = new ArrayList<>();
		PendingSends.Entry e = add("hello",
				() -> PendingSends.Result.handedOver("a", null));
		store.start(e, io::add);
		store.clock = () -> {
			seen.add(e.getState());
			identities.add(e.getIdentity());
			return now;
		};

		runIo();

		assertFalse("the send read the clock as it ended", seen.isEmpty());
		for (MessageState s : seen) {
			assertSame("nothing reads as finished while it is written",
					MessageState.SENDING, s);
		}
		for (String identity : identities) {
			assertEquals("its item is set before it reads as finished", "a",
					identity);
		}
		assertSame(MessageState.WAITING, e.getState());
	}

	@Test
	public void twoIdenticalSendsEachStayUntilTheirOwnItemShows() {
		PendingSends.Entry first = add("ok", storing("one"));
		PendingSends.Entry second = add("ok", storing("two"));
		store.start(first, io::add);
		runIo();
		shown.add("one");
		assertEquals("the first is shown, the second is still sending",
				Collections.singletonList(second), visible());
		assertEquals(MessageState.SENDING, second.getState());

		store.start(second, io::add);
		runIo();
		assertEquals("its item is not on screen yet", 1, visible().size());
		shown.add("two");
		assertTrue(visible().isEmpty());
		assertEquals(2, store.settle(SCOPE, shown::contains));
		assertTrue(store.entries(SCOPE).isEmpty());
	}

	@Test
	public void aStoredSendWhoseItemNeverShowsLeavesAfterAWhile() {
		PendingSends.Entry e = add("hello", storing("later"));
		store.start(e, io::add);
		runIo();
		assertEquals(0, store.settle(SCOPE, shown::contains));
		assertEquals(1, store.entries(SCOPE).size());
		now += PendingSends.RESOLVED_TTL_MS + 1;
		assertTrue(store.entries(SCOPE).isEmpty());
	}

	@Test
	public void aSendStoredWhileNobodyWatchesIsDroppedButItsNoticeKept() {
		store.reset();
		store.clock = () -> now;
		PendingSends.Notice notice = PendingSends.Notice.of(1);
		PendingSends.Entry e = store.add(SCOPE, "hello", new byte[1000],
				1000, false, () -> PendingSends.Result.stored("a", notice));
		store.start(e, io::add);
		runIo();
		assertTrue("nothing kept for an unwatched screen",
				store.entries(SCOPE).isEmpty());
		assertEquals(Collections.singletonList(notice),
				store.takeNotices(SCOPE));
		assertTrue("a notice is shown once",
				store.takeNotices(SCOPE).isEmpty());
	}

	@Test
	public void aSendThatEndsUnsentWhileNobodyWatchesSaysWhyOnTheNextVisit() {
		store.reset();
		store.clock = () -> now;
		PendingSends.Notice failed = PendingSends.Notice.of(1);
		PendingSends.Notice refused = PendingSends.Notice.of(2);
		PendingSends.Entry first = store.add(SCOPE, "hello", null, 5, true,
				() -> PendingSends.Result.failed(failed));
		PendingSends.Entry second = store.add(SCOPE, "again", null, 5, true,
				() -> PendingSends.Result.refused(refused));
		store.start(first, io::add);
		store.start(second, io::add);
		runIo();

		assertEquals(MessageState.NOT_SENT, first.getState());
		assertEquals(MessageState.NOT_SENT, second.getState());
		assertEquals(Arrays.asList(failed, refused), store.takeNotices(SCOPE));
		assertTrue("a notice is shown once",
				store.takeNotices(SCOPE).isEmpty());
	}

	@Test
	public void aFailureTheScreenHeardIsNotToldAgain() {
		PendingSends.Notice why = PendingSends.Notice.of(1);
		PendingSends.Entry e = add("hello",
				() -> PendingSends.Result.failed(why));
		store.start(e, io::add);
		runIo();

		assertSame(why, heard.get(heard.size() - 1).getNotice());
		assertTrue(store.takeNotices(SCOPE).isEmpty());
	}

	@Test
	public void anUntoldFailureGoesWithItsRow() {
		store.reset();
		store.clock = () -> now;
		PendingSends.Notice why = PendingSends.Notice.of(1);
		PendingSends.Entry locked = store.add(SCOPE, "a", null, 1, true,
				() -> PendingSends.Result.failed(why));
		store.start(locked, io::add);
		runIo();
		store.clear();
		List<PendingSends.Notice> told = store.takeNotices(SCOPE);
		assertFalse("the lock count tells what happened", told.contains(why));
		assertEquals(1, removedOnLock(told));

		PendingSends.Entry signedOut = store.add(SCOPE, "b", null, 1, true,
				() -> PendingSends.Result.failed(why));
		store.start(signedOut, io::add);
		runIo();
		store.clearSilently();
		assertTrue(store.takeNotices(SCOPE).isEmpty());
	}

	@Test
	public void aReturnedSendTakenBackBeforeItsReasonWasShownStillSaysWhy() {
		store.reset();
		store.clock = () -> now;
		PendingSends.Notice why = PendingSends.Notice.of(1);
		PendingSends.Entry e = store.add(SCOPE, "hello", null, 5, true,
				() -> PendingSends.Result.refused(why));
		store.start(e, io::add);
		runIo();

		assertTrue(store.discard(e));
		assertEquals(Collections.singletonList(why), store.takeNotices(SCOPE));
		assertTrue(store.restore(e));
		assertTrue("a notice is shown once",
				store.takeNotices(SCOPE).isEmpty());
	}

	@Test
	public void aScreenThatStartsListeningAsASendEndsHearsWhyOnce()
			throws Exception {
		store.reset();
		store.clock = () -> now;
		List<PendingSends.Change> late = new ArrayList<>();
		store.addListener(late::add, SCOPE);
		PendingSends.Notice why = PendingSends.Notice.of(1);
		PendingSends.Entry e = store.add(SCOPE, "hello", null, 5, true,
				() -> PendingSends.Result.failed(why));
		store.start(e, io::add);
		listenerArrivesJustAfterTheCheck();

		runIo();

		int told = store.takeNotices(SCOPE).size();
		for (PendingSends.Change c : late) {
			if (c.getNotice() != null) told++;
		}
		assertEquals("the reason is told exactly once", 1, told);
	}

	@SuppressWarnings("unchecked")
	private void listenerArrivesJustAfterTheCheck() throws Exception {
		Field f = PendingSends.class.getDeclaredField("listeners");
		f.setAccessible(true);
		AtomicBoolean unseen = new AtomicBoolean(true);
		f.set(store, new CopyOnWriteArrayList<Object>(
				(List<Object>) f.get(store)) {
			@Override
			public Iterator<Object> iterator() {
				return unseen.getAndSet(false)
						? Collections.emptyIterator() : super.iterator();
			}
		});
	}

	@Test
	public void retryingAFailedSendRunsItExactlyOnceMore() {
		PendingSends.Entry e = add("hello", failing());
		store.start(e, io::add);
		runIo();
		assertEquals(MessageState.NOT_SENT, e.getState());
		assertTrue(e.canRetry());
		assertTrue(store.retry(e, io::add));
		assertFalse("a second tap while sending does nothing",
				store.retry(e, io::add));
		runIo();
		assertEquals(2, attempts.get());
	}

	@Test
	public void aRefusedSendCannotBeRetriedButCanBeEditedOrDeleted() {
		PendingSends.Entry e = add("hello",
				() -> PendingSends.Result.refused(PendingSends.Notice.of(1)));
		store.start(e, io::add);
		runIo();
		assertEquals(MessageState.NOT_SENT, e.getState());
		assertTrue(e.isReturned());
		assertFalse(e.canRetry());
		assertFalse(store.retry(e, io::add));
		assertTrue(e.canEdit());
		assertTrue(e.canDiscard());
	}

	@Test
	public void mediaCannotBeEditedBackIntoTheComposer() {
		PendingSends.Entry e = store.add(SCOPE, "", new byte[10], 10, false,
				failing());
		store.start(e, io::add);
		runIo();
		assertFalse(e.canEdit());
		assertTrue(e.canDiscard());
	}

	@Test
	public void aDeletedSendCanBeRestoredInItsPlace() {
		PendingSends.Entry a = add("a", failing());
		PendingSends.Entry b = add("b", failing());
		store.start(a, io::add);
		store.start(b, io::add);
		runIo();
		assertTrue(store.discard(a));
		assertEquals(Collections.singletonList(b), store.entries(SCOPE));
		assertTrue(store.restore(a));
		assertEquals(Arrays.asList(a, b), store.entries(SCOPE));
		assertFalse("restored once", store.restore(a));
	}

	@Test
	public void anUnsentCopyCanBeAddedWithoutSendingIt() {
		PendingSends.Entry e = store.addNotSent(SCOPE, "again", null, 5,
				true, storing("x"));
		assertEquals(MessageState.NOT_SENT, e.getState());
		assertEquals("adding a copy is not a new failure",
				PendingSends.Kind.ADDED, heard.get(heard.size() - 1)
						.getKind());
		assertEquals(0, attempts.get());
		assertTrue(store.retry(e, io::add));
		runIo();
		assertEquals(1, attempts.get());
	}

	private int removedOnLock(List<PendingSends.Notice> notices) {
		for (PendingSends.Notice n : notices) {
			if (n.getPlural() == R.plurals.message_state_removed_on_lock) {
				return n.getCount();
			}
		}
		return 0;
	}

	@Test
	public void clearingRemovesEveryUnsentRowAndDropsRunningSendsWhenTheyEnd() {
		PendingSends.Entry failed = add("secret", failing());
		store.start(failed, io::add);
		runIo();
		PendingSends.Entry running = add("also secret", failing());
		store.start(running, io::add);

		store.clear();

		assertTrue(store.entries(SCOPE).isEmpty());
		runIo();
		assertTrue("a send that ends after the lock is not kept",
				store.entries(SCOPE).isEmpty());
		assertTrue(heard.stream().anyMatch(c ->
				c.getKind() == PendingSends.Kind.CLEARED));
		assertEquals("both unsent messages are counted, without their text",
				2, removedOnLock(store.takeNotices(SCOPE)));
	}

	@Test
	public void aSendThatGoesOutAfterTheLockIsNotCountedAsLost() {
		PendingSends.Entry running = add("hello", storing("a"));
		store.start(running, io::add);
		store.clear();
		runIo();
		assertEquals(0, removedOnLock(store.takeNotices(SCOPE)));
	}

	@Test
	public void aSendThatGoesOutAfterTheLockKeepsItsNotice() {
		PendingSends.Notice notice = PendingSends.Notice.of(1);
		PendingSends.Entry running = add("hello", () ->
				PendingSends.Result.handedOver("a", notice));
		store.start(running, io::add);
		store.clear();
		runIo();

		assertTrue("what happened to the post is still told",
				store.takeNotices(SCOPE).contains(notice));
		assertTrue(store.entries(SCOPE).isEmpty());
	}

	@Test
	public void aSendThatEndsAfterItsGroupIsRemovedLeavesNothing() {
		String group = PendingSends.groupScope(new byte[] {4});
		PendingSends.Entry running = store.add(group, "hello", null, 5, true,
				() -> PendingSends.Result.handedOver("a",
						PendingSends.Notice.of(1)));
		store.start(running, io::add);
		store.removeGroup(new byte[] {4});
		runIo();

		assertTrue(store.takeNotices(group).isEmpty());
	}

	@Test
	public void aSilentClearLeavesNothingToTellLater() {
		PendingSends.Entry failed = add("secret", failing());
		store.start(failed, io::add);
		runIo();
		store.clear();
		PendingSends.Entry told = store.add(SCOPE, "x", null, 1, true,
				() -> PendingSends.Result.handedOver("a",
						PendingSends.Notice.of(1)));
		store.start(told, io::add);
		PendingSends.Entry unheard = store.add("quiet", "y", null, 1, true,
				() -> PendingSends.Result.stored("b",
						PendingSends.Notice.of(2)));
		store.start(unheard, io::add);
		runIo();
		PendingSends.Entry running = add("later", failing());
		store.start(running, io::add);

		store.clearSilently();
		runIo();

		assertEquals(0, store.takeRemovedOnLock());
		assertTrue(store.takeNotices(SCOPE).isEmpty());
		assertTrue(store.takeNotices("quiet").isEmpty());
		assertTrue(store.entries(SCOPE).isEmpty());
	}

	@Test
	public void aSendTheLockLeftRunningTellsNothingAfterASignOut() {
		PendingSends.Entry running = add("later", failing());
		store.start(running, io::add);
		store.clear();
		store.clearSilently();
		runIo();

		assertEquals(0, store.takeRemovedOnLock());
		assertTrue(store.takeNotices(SCOPE).isEmpty());
	}

	@Test
	public void theUnlockCountIsTakenOnce() {
		PendingSends.Entry e = add("secret", failing());
		store.start(e, io::add);
		runIo();
		store.clear();

		assertEquals(1, store.takeRemovedOnLock());
		assertEquals(0, store.takeRemovedOnLock());
		assertEquals("the screen does not say it again", 0,
				removedOnLock(store.takeNotices(SCOPE)));
	}

	@Test
	public void removingOneGroupLeavesTheUndoOfAnother() {
		String kept = PendingSends.groupScope(new byte[] {5});
		PendingSends.Entry e = store.add(kept, "mine", null, 4, true,
				failing());
		store.start(e, io::add);
		runIo();
		assertTrue(store.discard(e));

		store.removeGroup(new byte[] {6});

		assertTrue("the undo still brings the row back", store.restore(e));
		assertEquals(1, store.entries(kept).size());
	}

	@Test
	public void removingAGroupEndsTheUndoOfItsOwnRows() {
		String gone = PendingSends.groupScope(new byte[] {5});
		PendingSends.Entry e = store.add(gone, "mine", null, 4, true,
				failing());
		store.start(e, io::add);
		runIo();
		assertTrue(store.discard(e));

		store.removeGroup(new byte[] {5});

		assertFalse(store.restore(e));
		assertTrue(store.entries(gone).isEmpty());
	}

	@Test
	public void anUndoOlderThanTheRemovalsKeptIsRefused() {
		String kept = PendingSends.groupScope(new byte[] {5});
		PendingSends.Entry e = store.add(kept, "mine", null, 4, true,
				failing());
		store.start(e, io::add);
		runIo();
		assertTrue(store.discard(e));

		for (int i = 0; i <= PendingSends.MAX_REMOVAL_EVENTS; i++) {
			store.removeGroup(new byte[] {6, (byte) i});
		}

		assertFalse("not restored when it cannot be checked",
				store.restore(e));
	}

	@Test
	public void aRemovalTellsScreensWhichScopesItTouched() {
		List<String[]> told = new ArrayList<>();
		store.addClearListener(told::add);
		store.removeChannel(new byte[] {2});

		assertEquals(1, told.size());
		String[] prefixes = told.get(0);
		assertTrue(PendingSends.affects(prefixes,
				PendingSends.channelPostScope(new byte[] {2})));
		assertTrue(PendingSends.affects(prefixes,
				PendingSends.channelCommentScope(new byte[] {2}, 3L)));
		assertFalse(PendingSends.affects(prefixes,
				PendingSends.groupScope(new byte[] {2})));
		assertFalse(PendingSends.affects(prefixes,
				PendingSends.channelPostScope(new byte[] {3})));
	}

	@Test
	public void noticesKeptForTheNextVisitSurviveTheLock() {
		store.reset();
		store.clock = () -> now;
		PendingSends.Notice notice = PendingSends.Notice.of(1);
		PendingSends.Entry e = store.add(SCOPE, "hello", null, 5, true,
				() -> PendingSends.Result.stored("a", notice));
		store.start(e, io::add);
		runIo();
		store.clear();
		assertTrue(store.takeNotices(SCOPE).contains(notice));
	}

	@Test
	public void anUndoAfterTheLockRestoresNothing() {
		PendingSends.Entry e = add("secret", failing());
		store.start(e, io::add);
		runIo();
		assertTrue(store.discard(e));
		store.clear();
		assertFalse(store.restore(e));
		assertTrue(store.entries(SCOPE).isEmpty());
	}

	@Test
	public void aClearTellsEveryScreenThatIsStillOpen() {
		int[] told = {0};
		store.addClearListener(prefixes -> told[0]++);
		store.clear();
		store.removeGroup(new byte[] {1});
		assertEquals(2, told[0]);
	}

	@Test
	public void leavingAGroupOrChannelRemovesItsUnsentRows() {
		byte[] group = new byte[] {1};
		byte[] channel = new byte[] {2};
		store.add(PendingSends.groupScope(group), "g", null, 1, true,
				failing());
		store.add(PendingSends.channelPostScope(channel), "p", null, 1,
				true, failing());
		store.add(PendingSends.channelCommentScope(channel, 7L), "c", null,
				1, true, failing());
		store.add(PendingSends.groupScope(new byte[] {3}), "other", null, 1,
				true, failing());

		store.removeGroup(group);
		store.removeChannel(channel);

		assertTrue(store.entries(PendingSends.groupScope(group)).isEmpty());
		assertTrue(store.entries(PendingSends.channelPostScope(channel))
				.isEmpty());
		assertTrue(store.entries(PendingSends.channelCommentScope(channel,
				7L)).isEmpty());
		assertEquals(1, store.entries(PendingSends.groupScope(
				new byte[] {3})).size());
	}

	@Test
	public void keptMediaStaysWithinTheLimitWithoutLosingText() {
		long big = PendingSends.MAX_KEPT_BYTES / 2 + 1;
		PendingSends.Entry text = add("a short note", failing());
		store.start(text, io::add);
		PendingSends.Entry small = store.add(SCOPE, "", null, 10, false,
				failing());
		store.start(small, io::add);
		PendingSends.Entry first = store.add(SCOPE, "", null, big, false,
				failing());
		store.start(first, io::add);
		runIo();
		PendingSends.Entry second = store.add(SCOPE, "", null, big + 1,
				false, failing());
		store.start(second, io::add);
		runIo();

		assertEquals("the largest media makes room, text and small media"
				+ " stay", Arrays.asList(text, small, first),
				store.entries(SCOPE));
		assertEquals(PendingSends.Kind.REMOVED, heard.get(heard.size() - 1)
				.getKind());
		boolean told = false;
		for (PendingSends.Notice n : store.takeNotices(SCOPE)) {
			if (n.getPlural() == R.plurals.message_state_removed_for_space) {
				told = n.getCount() == 1;
			}
		}
		assertTrue("the removal is told without text", told);
	}

	@Test
	public void aCrashingAttemptOrARefusedExecutorEndsAsNotSent() {
		PendingSends.Entry crash = add("hello", () -> {
			throw new IllegalStateException();
		});
		store.start(crash, io::add);
		runIo();
		assertEquals(MessageState.NOT_SENT, crash.getState());
		PendingSends.Entry refused = add("hello", storing("a"));
		store.start(refused, r -> {
			throw new java.util.concurrent.RejectedExecutionException();
		});
		assertEquals(MessageState.NOT_SENT, refused.getState());
	}

	@Test
	public void aQueuedItemRetriesOnlyOnceAtATime() {
		assertTrue(store.beginQueuedRetry("c", 7L));
		assertFalse(store.beginQueuedRetry("c", 7L));
		assertTrue(store.isQueuedRetry("c", 7L));
		store.endQueuedRetry("c", 7L);
		assertFalse(store.isQueuedRetry("c", 7L));
	}

	@Test
	public void onlyListenersOfAScopeHearIt() {
		List<PendingSends.Change> other = new ArrayList<>();
		store.addListener(other::add, "elsewhere");
		PendingSends.Entry e = add("hello", failing());
		store.start(e, io::add);
		runIo();
		assertTrue(other.isEmpty());
		assertSame(e, heard.get(heard.size() - 1).getEntry());
		assertEquals(PendingSends.Kind.FAILED,
				heard.get(heard.size() - 1).getKind());
	}
}

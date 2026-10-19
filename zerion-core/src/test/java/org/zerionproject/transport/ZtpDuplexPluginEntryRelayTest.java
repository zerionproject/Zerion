package org.zerionproject.transport;

import org.junit.Before;
import org.junit.Test;
import org.zerionproject.core.api.Cancellable;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.plugin.event.TorEntryRelayEvent;
import org.zerionproject.core.api.system.TaskScheduler;
import org.zerionproject.tor.TorWrapper.Observer;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.annotation.Nullable;
import javax.net.SocketFactory;

import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.transport.EntryRelayHealth.UNANSWERED_MS;

public class ZtpDuplexPluginEntryRelayTest {

	private static final String ONION =
			"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";

	private static final class Scheduled {
		final Runnable task;
		final long delayMs;

		Scheduled(Runnable task, long delayMs) {
			this.task = task;
			this.delayMs = delayMs;
		}
	}

	private final AtomicReference<Observer> observer = new AtomicReference<>();
	private final List<Boolean> reported = new ArrayList<>();
	private final List<Scheduled> scheduled = new ArrayList<>();
	private final AtomicLong now = new AtomicLong(5_000_000L);
	private ZtpDuplexPlugin plugin;

	@Before
	public void setUp() {
		ZtpTorTransportTest.StubTor tor = new ZtpTorTransportTest.StubTor() {
			@Override
			public void setObserver(@Nullable Observer o) {
				observer.set(o);
			}
		};
		Executor direct = Runnable::run;
		TaskScheduler scheduler = new TaskScheduler() {
			@Override
			public Cancellable schedule(Runnable task, Executor executor,
					long delay, TimeUnit unit) {
				scheduled.add(new Scheduled(task, unit.toMillis(delay)));
				return () -> {
				};
			}

			@Override
			public Cancellable scheduleWithFixedDelay(Runnable task,
					Executor executor, long delay, long interval,
					TimeUnit unit) {
				throw new AssertionError();
			}
		};
		ZtpTorTransport transport = new ZtpTorTransport(tor,
				SocketFactory.getDefault(), SocketFactory.getDefault(), direct,
				null, null, () -> {
		});
		EventBus bus = new EventBus() {
			@Override
			public void addListener(EventListener l) {
			}

			@Override
			public void removeListener(EventListener l) {
			}

			@Override
			public void broadcast(Event e) {
				if (e instanceof TorEntryRelayEvent) {
					reported.add(((TorEntryRelayEvent) e).isFailing());
				}
			}
		};
		plugin = new ZtpDuplexPlugin(direct, direct, scheduler,
				SocketFactory.getDefault(), tor, transport, null, null, null,
				null, bus, null, null);
		plugin.setClock(now::get);
	}

	private void runDueChecks() {
		List<Scheduled> due = new ArrayList<>(scheduled);
		scheduled.clear();
		for (Scheduled s : due) s.task.run();
	}

	@Test
	public void aGuardWarningIsNotShownWhileTorStillPublishesItsAddress() {
		Observer o = observer.get();
		o.onBootstrapPercentage(100);
		o.onEntryRelayFailing();
		o.onHsDescriptorUpload(ONION);
		assertFalse("the onion directory took the address, so Tor carries"
				+ " circuits", plugin.isEntryRelayFailing());
	}

	@Test
	public void aGuardWarningIsNotShownWhileLookupsAreAnswered() {
		Observer o = observer.get();
		o.onBootstrapPercentage(100);
		o.onEntryRelayFailing();
		for (int i = 0; i < 10; i++) {
			o.onHsDirectoryRequest();
			now.addAndGet(20_000L);
			o.onHsDirectoryAnswer();
			now.addAndGet(UNANSWERED_MS);
			runDueChecks();
		}
		assertFalse("contacts that are switched off are answered with"
				+ " not found, which proves the path works",
				plugin.isEntryRelayFailing());
		assertEquals(emptyList(), reported);
	}

	@Test
	public void anUnansweredLookupIsNoticedWithoutAnotherTorEvent() {
		Observer o = observer.get();
		o.onBootstrapPercentage(100);
		o.onEntryRelayFailing();
		o.onHsDirectoryRequest();
		assertEquals("one check is scheduled for the end of the wait", 1,
				scheduled.size());
		assertEquals(UNANSWERED_MS, scheduled.get(0).delayMs);
		o.onHsDirectoryRequest();
		assertEquals("a lookup during a wait schedules no second check", 1,
				scheduled.size());
		now.addAndGet(UNANSWERED_MS);
		assertFalse(plugin.isEntryRelayFailing());
		runDueChecks();
		assertTrue(plugin.isEntryRelayFailing());
		assertEquals(singletonList(true), reported);
		o.onHsDirectoryAnswer();
		assertFalse(plugin.isEntryRelayFailing());
		assertEquals(asList(true, false), reported);
	}

	@Test
	public void aCheckAfterAnAnswerChangesNothing() {
		Observer o = observer.get();
		o.onBootstrapPercentage(100);
		o.onEntryRelayFailing();
		o.onHsDirectoryRequest();
		now.addAndGet(UNANSWERED_MS - 1);
		o.onHsDirectoryAnswer();
		now.addAndGet(1);
		runDueChecks();
		assertFalse(plugin.isEntryRelayFailing());
		assertEquals(emptyList(), reported);
	}

	@Test
	public void aWarningAfterLookupsWentUnansweredIsShownAtOnce() {
		Observer o = observer.get();
		o.onBootstrapPercentage(100);
		o.onHsDirectoryRequest();
		now.addAndGet(UNANSWERED_MS);
		runDueChecks();
		assertFalse("without the guard warning Tor is only slow",
				plugin.isEntryRelayFailing());
		o.onEntryRelayFailing();
		assertTrue(plugin.isEntryRelayFailing());
		assertEquals(singletonList(true), reported);
	}

	@Test
	public void aWarningDuringBootstrapOutlivesTheEndOfBootstrap() {
		Observer o = observer.get();
		o.onBootstrapPercentage(95);
		o.onEntryRelayFailing();
		o.onBootstrapPercentage(98);
		o.onBootstrapPercentage(100);
		o.onHsDirectoryRequest();
		now.addAndGet(UNANSWERED_MS);
		runDueChecks();
		assertTrue("Tor warns once per run, often on its first circuit",
				plugin.isEntryRelayFailing());
	}

	@Test
	public void aNewTorRunForgetsTheWarning() {
		Observer o = observer.get();
		o.onBootstrapPercentage(100);
		o.onEntryRelayFailing();
		o.onHsDirectoryRequest();
		now.addAndGet(UNANSWERED_MS);
		runDueChecks();
		assertTrue(plugin.isEntryRelayFailing());
		o.onBootstrapPercentage(5);
		assertFalse(plugin.isEntryRelayFailing());
		o.onBootstrapPercentage(100);
		o.onHsDirectoryRequest();
		now.addAndGet(UNANSWERED_MS);
		runDueChecks();
		assertFalse("the new run has not warned", plugin.isEntryRelayFailing());
		assertEquals(asList(true, false), reported);
	}
}

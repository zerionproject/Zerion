package com.professor.zerion.android.security;

import android.app.Application;
import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.android.security.AntiForensics.UsbPanicState;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AntiForensicsTest {

	private static final Executor DIRECT = Runnable::run;

	private static final AntiForensics.Registrar REGISTERS = (c, r, f) -> {
		c.registerReceiver(r, f);
		return null;
	};

	private static Intent usb(boolean connected, boolean configured,
			String... functions) {
		Intent i = new Intent(AntiForensics.ACTION_USB_STATE);
		i.putExtra(AntiForensics.EXTRA_CONNECTED, connected);
		i.putExtra(AntiForensics.EXTRA_CONFIGURED, configured);
		for (String f : functions) i.putExtra(f, true);
		return i;
	}

	private static final class Action implements AntiForensics.PanicAction {

		private final Queue<Object> outcomes = new ArrayDeque<>();
		private final boolean otherwise;
		final AtomicInteger runs = new AtomicInteger();
		final AtomicInteger giveUps = new AtomicInteger();

		Action(boolean otherwise, Object... first) {
			this.otherwise = otherwise;
			for (Object o : first) outcomes.add(o);
		}

		@Override
		public boolean run() {
			runs.incrementAndGet();
			Object o = outcomes.poll();
			if (o instanceof RuntimeException) throw (RuntimeException) o;
			return o == null ? otherwise : (Boolean) o;
		}

		@Override
		public void giveUp() {
			giveUps.incrementAndGet();
		}
	}

	private static final class ManualExecutor implements Executor {

		final Queue<Runnable> queued = new ArrayDeque<>();

		@Override
		public void execute(Runnable r) {
			queued.add(r);
		}

		void runOne() {
			queued.remove().run();
		}
	}

	private static AntiForensics af(Executor executor) {
		return new AntiForensics(RuntimeEnvironment.getApplication(), REGISTERS,
				executor);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void idleFor(long ms) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
	}

	@Test
	public void onlyAConfiguredDataFunctionCountsAsTransfer() {
		assertTrue(AntiForensics.isDataTransfer(usb(true, true, "mtp")));
		assertTrue(AntiForensics.isDataTransfer(usb(true, true, "adb")));
		assertTrue(AntiForensics.isDataTransfer(usb(true, true, "ptp")));
		assertFalse("a charger configures no data function",
				AntiForensics.isDataTransfer(usb(true, true)));
		assertFalse("audio accessory or car head unit",
				AntiForensics.isDataTransfer(usb(true, true, "audio_source")));
		assertFalse("not yet configured by a host",
				AntiForensics.isDataTransfer(usb(true, false, "mtp")));
		assertFalse(AntiForensics.isDataTransfer(usb(false, false, "mtp")));
		assertFalse(AntiForensics.isDataTransfer(null));
		assertFalse(AntiForensics.isDataTransfer(
				new Intent("android.intent.action.BATTERY_CHANGED")));
	}

	@Test
	public void enablingReportsArmedOnlyWhenTheReceiverIsRegistered() {
		Application app = RuntimeEnvironment.getApplication();
		shadowOf(app).grantPermissions(app.getPackageName()
				+ ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION");
		AntiForensics af = new AntiForensics(app);
		assertEquals(UsbPanicState.OFF, af.getUsbPanicState());
		assertTrue(af.armUsbPanic(new Action(true)));
		assertEquals(UsbPanicState.ARMED, af.getUsbPanicState());
		af.disarmUsbPanic();
		assertEquals(UsbPanicState.OFF, af.getUsbPanicState());
	}

	@Test
	public void aReceiverThatCannotBeRegisteredIsReportedAsFailed() {
		AtomicInteger calls = new AtomicInteger();
		AntiForensics af = new AntiForensics(
				RuntimeEnvironment.getApplication(), (c, r, f) -> {
					calls.incrementAndGet();
					throw new SecurityException("receiver refused");
				}, DIRECT);
		assertFalse(af.armUsbPanic(new Action(true)));
		assertEquals(UsbPanicState.FAILED, af.getUsbPanicState());
		assertFalse("every arm tries to register again",
				af.armUsbPanic(new Action(true)));
		assertEquals(2, calls.get());
		af.disarmUsbPanic();
		assertEquals(UsbPanicState.OFF, af.getUsbPanicState());
	}

	@Test
	public void aLaterArmCanSucceedAfterAFailedOne() {
		AtomicInteger calls = new AtomicInteger();
		AntiForensics af = new AntiForensics(
				RuntimeEnvironment.getApplication(), (c, r, f) -> {
					if (calls.incrementAndGet() == 1) {
						throw new IllegalStateException("too many receivers");
					}
					return REGISTERS.register(c, r, f);
				}, DIRECT);
		assertFalse(af.armUsbPanic(new Action(true)));
		assertEquals(UsbPanicState.FAILED, af.getUsbPanicState());
		assertTrue(af.armUsbPanic(new Action(true)));
		assertEquals(UsbPanicState.ARMED, af.getUsbPanicState());
		af.disarmUsbPanic();
	}

	@Test
	public void aSuccessfulPanicHandlesTheConnectionOnce() {
		AntiForensics af = af(DIRECT);
		Action action = new Action(true);
		af.onUsbState(usb(true, true, "mtp"));
		idle();
		assertEquals("disarmed", 0, action.runs.get());
		af.armUsbPanic(action);
		af.onUsbState(usb(true, true, "mtp"));
		idle();
		assertTrue(af.isHandledForThisConnection());
		af.onUsbState(usb(true, true, "mtp"));
		af.onUsbState(usb(true, true, "adb"));
		idle();
		assertEquals("once per connection", 1, action.runs.get());
		af.onUsbState(usb(false, false));
		af.onUsbState(usb(true, true, "adb"));
		idle();
		assertEquals("a new connection runs it again", 2, action.runs.get());
		af.disarmUsbPanic();
		af.onUsbState(usb(false, false));
		af.onUsbState(usb(true, true, "adb"));
		idle();
		assertEquals("disarmed again", 2, action.runs.get());
		assertEquals(0, action.giveUps.get());
	}

	@Test
	public void aFailedPanicIsNeverCountedAsHandled() {
		AntiForensics af = af(DIRECT);
		Action action = new Action(false);
		af.armUsbPanic(action);
		af.onUsbState(usb(true, true, "mtp"));
		idle();
		assertFalse(af.isHandledForThisConnection());
		idleFor(10 * AntiForensics.RETRY_DELAY_MS);
		assertEquals(AntiForensics.MAX_ATTEMPTS, action.runs.get());
		assertFalse(af.isHandledForThisConnection());
		assertEquals("the last failure falls back once", 1,
				action.giveUps.get());
		af.onUsbState(usb(true, true, "mtp"));
		idleFor(10 * AntiForensics.RETRY_DELAY_MS);
		assertEquals("no attempts beyond the bound",
				AntiForensics.MAX_ATTEMPTS, action.runs.get());
		af.onUsbState(usb(false, false));
		af.onUsbState(usb(true, true, "mtp"));
		idle();
		assertEquals("a new connection starts over",
				AntiForensics.MAX_ATTEMPTS + 1, action.runs.get());
		af.disarmUsbPanic();
	}

	@Test
	public void aFailedPanicIsRetriedUntilItSucceeds() {
		AntiForensics af = af(DIRECT);
		Action action = new Action(true, false,
				new IllegalStateException("sign-out could not start"));
		af.armUsbPanic(action);
		af.onUsbState(usb(true, true, "adb"));
		idle();
		assertEquals(1, action.runs.get());
		assertFalse(af.isHandledForThisConnection());
		idleFor(AntiForensics.RETRY_DELAY_MS);
		assertEquals("an exception counts as a failure", 2, action.runs.get());
		assertFalse(af.isHandledForThisConnection());
		idleFor(AntiForensics.RETRY_DELAY_MS);
		assertEquals(3, action.runs.get());
		assertTrue(af.isHandledForThisConnection());
		idleFor(10 * AntiForensics.RETRY_DELAY_MS);
		assertEquals(3, action.runs.get());
		assertEquals(0, action.giveUps.get());
		af.disarmUsbPanic();
	}

	@Test
	public void rapidUsbEventsRunTheActionOneAtATime() {
		ManualExecutor executor = new ManualExecutor();
		AntiForensics af = af(executor);
		Action action = new Action(true, false);
		af.armUsbPanic(action);
		for (int i = 0; i < 20; i++) {
			af.onUsbState(usb(true, true, i % 2 == 0 ? "mtp" : "adb"));
		}
		assertEquals("one run while one is in flight", 1,
				executor.queued.size());
		executor.runOne();
		idle();
		assertFalse(af.isHandledForThisConnection());
		af.onUsbState(usb(true, true, "mtp"));
		assertEquals(1, executor.queued.size());
		executor.runOne();
		for (int i = 0; i < 5; i++) af.onUsbState(usb(true, true, "mtp"));
		idleFor(10 * AntiForensics.RETRY_DELAY_MS);
		assertTrue(af.isHandledForThisConnection());
		assertEquals(0, executor.queued.size());
		assertEquals(2, action.runs.get());
		af.disarmUsbPanic();
	}

	@Test
	public void unpluggingDuringTheActionDropsItsRetry() {
		ManualExecutor executor = new ManualExecutor();
		AntiForensics af = af(executor);
		Action action = new Action(false);
		af.armUsbPanic(action);
		af.onUsbState(usb(true, true, "mtp"));
		af.onUsbState(usb(false, false));
		executor.runOne();
		idleFor(10 * AntiForensics.RETRY_DELAY_MS);
		assertEquals(0, executor.queued.size());
		assertEquals(0, action.giveUps.get());
		af.onUsbState(usb(true, true, "mtp"));
		assertEquals("a new connection is tried at once", 1,
				executor.queued.size());
		af.disarmUsbPanic();
	}

	@Test
	public void aReconnectWhileTheActionRunsIsHandledOnceItEnds() {
		ManualExecutor executor = new ManualExecutor();
		AntiForensics af = af(executor);
		Action action = new Action(true, false);
		af.armUsbPanic(action);
		af.onUsbState(usb(true, true, "mtp"));
		af.onUsbState(usb(false, false));
		af.onUsbState(usb(true, true, "adb"));
		assertEquals("one run at a time", 1, executor.queued.size());
		executor.runOne();
		idle();
		assertEquals("the new connection is tried", 1, executor.queued.size());
		executor.runOne();
		idle();
		assertTrue(af.isHandledForThisConnection());
		assertEquals(2, action.runs.get());

		ManualExecutor second = new ManualExecutor();
		AntiForensics other = af(second);
		Action succeeds = new Action(true);
		other.armUsbPanic(succeeds);
		other.onUsbState(usb(true, true, "mtp"));
		other.onUsbState(usb(false, false));
		other.onUsbState(usb(true, true, "mtp"));
		second.runOne();
		idle();
		assertEquals("an earlier connection does not cover a new one", 1,
				second.queued.size());
		second.runOne();
		idle();
		assertTrue(other.isHandledForThisConnection());
		af.disarmUsbPanic();
		other.disarmUsbPanic();
	}

	private static AntiForensics.PanicAction signOut(AtomicBoolean signedIn,
			AtomicInteger signedOut) {
		return new AntiForensics.PanicAction() {
			@Override
			public boolean run() {
				if (signedIn.get()) signedOut.incrementAndGet();
				return true;
			}

			@Override
			public void giveUp() {
			}
		};
	}

	@Test
	public void signingInWhileConnectedIsCaughtWhenTheAppArmsAgain() {
		AntiForensics af = af(DIRECT);
		AtomicBoolean signedIn = new AtomicBoolean(false);
		AtomicInteger signedOut = new AtomicInteger();
		AntiForensics.PanicAction action = signOut(signedIn, signedOut);
		af.armUsbPanic(action);
		af.onUsbState(usb(true, true, "mtp"));
		idle();
		assertEquals("nothing to end while signed out", 0, signedOut.get());
		signedIn.set(true);
		af.armUsbPanic(action);
		idle();
		assertEquals(1, signedOut.get());
		af.disarmUsbPanic();
	}

	@Test
	public void signingInWhileTheCheckRunsIsNotMissed() {
		ManualExecutor executor = new ManualExecutor();
		AntiForensics af = af(executor);
		AtomicBoolean signedIn = new AtomicBoolean(false);
		AtomicInteger signedOut = new AtomicInteger();
		AntiForensics.PanicAction action = signOut(signedIn, signedOut);
		af.armUsbPanic(action);
		af.onUsbState(usb(true, true, "adb"));
		executor.runOne();
		signedIn.set(true);
		af.armUsbPanic(action);
		idle();
		assertEquals("checked again after the sign-in", 1,
				executor.queued.size());
		executor.runOne();
		idle();
		assertEquals(1, signedOut.get());
		assertTrue(af.isHandledForThisConnection());
		af.disarmUsbPanic();
	}

	@Test
	public void disarmingDuringTheActionStopsRetriesAndTheFallback() {
		ManualExecutor executor = new ManualExecutor();
		AntiForensics af = af(executor);
		Action action = new Action(false);
		af.armUsbPanic(action);
		af.onUsbState(usb(true, true, "mtp"));
		af.disarmUsbPanic();
		executor.runOne();
		idleFor(10 * AntiForensics.RETRY_DELAY_MS);
		assertEquals(1, action.runs.get());
		assertEquals(0, action.giveUps.get());
		assertEquals(0, executor.queued.size());
	}

	@Test
	public void aChangedSettingWhileArmedTakesEffectAtOnce() {
		AntiForensics af = af(DIRECT);
		Action signOut = new Action(true);
		Action wipe = new Action(true);
		af.armUsbPanic(signOut);
		af.armUsbPanic(wipe);
		assertEquals(UsbPanicState.ARMED, af.getUsbPanicState());
		af.onUsbState(usb(true, true, "mtp"));
		idle();
		assertEquals(0, signOut.runs.get());
		assertEquals(1, wipe.runs.get());
		af.disarmUsbPanic();
	}
}

package com.professor.zerion.android.conversation;

import android.app.Application;
import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.conversation.ContactConnectionLines.Line;
import com.professor.zerion.android.navdrawer.TorPublishTracker;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.api.connection.ContactConnectionDiagnostics;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Authorization;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.OwnRotation;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.PeerAddress;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Reach;
import org.zerionproject.core.api.connection.ContactConnectionSnapshot.Tor;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.lifecycle.LifecycleManager;
import org.zerionproject.core.api.system.AndroidExecutor;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import androidx.lifecycle.Observer;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.api.connection.ContactConnectionSnapshot.UNKNOWN;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ContactConnectionViewModelTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final AndroidExecutor SAME_THREAD = new AndroidExecutor() {
		@Override
		public <V> Future<V> runOnBackgroundThread(Callable<V> c) {
			FutureTask<V> f = new FutureTask<>(c);
			f.run();
			return f;
		}

		@Override
		public void runOnBackgroundThread(Runnable r) {
			r.run();
		}

		@Override
		public <V> Future<V> runOnUiThread(Callable<V> c) {
			FutureTask<V> f = new FutureTask<>(c);
			f.run();
			return f;
		}

		@Override
		public void runOnUiThread(Runnable r) {
			r.run();
		}
	};

	private static void advance(long ms) {
		shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms));
	}

	@Test
	public void refreshesOnlyWhileTheSectionIsVisible() {
		AtomicInteger reads = new AtomicInteger();
		AtomicReference<Reach> reach = new AtomicReference<>(Reach.DIALING);
		ContactConnectionDiagnostics diagnostics = c -> {
			reads.incrementAndGet();
			return new ContactConnectionSnapshot(Tor.CONNECTED, reach.get(),
					UNKNOWN, UNKNOWN, PeerAddress.CURRENT, UNKNOWN,
					OwnRotation.IDLE, Authorization.ACTIVE, 0, 0, 0);
		};
		TorPublishTracker tracker = mock(TorPublishTracker.class);
		when(tracker.isPublished()).thenReturn(true);
		ContactConnectionViewModel vm = new ContactConnectionViewModel(
				(Application) RuntimeEnvironment.getApplication(), Runnable::run,
				mock(LifecycleManager.class), mock(TransactionManager.class),
				SAME_THREAD, diagnostics, tracker);
		vm.setContactId(new ContactId(1));
		assertEquals(0, reads.get());

		AtomicReference<List<Line>> latest = new AtomicReference<>();
		Observer<List<Line>> observer = latest::set;
		vm.getLines().observeForever(observer);
		assertEquals(1, reads.get());
		assertEquals(R.string.contact_connection_published,
				latest.get().get(1).text);
		assertEquals(R.string.contact_connection_dialing,
				latest.get().get(2).text);

		reach.set(Reach.ONLINE);
		advance(ContactConnectionViewModel.REFRESH_MS);
		assertEquals(2, reads.get());
		assertEquals(R.string.contact_connection_online,
				latest.get().get(2).text);

		vm.getLines().removeObserver(observer);
		advance(10 * ContactConnectionViewModel.REFRESH_MS);
		assertEquals(2, reads.get());
	}

	@Test
	public void aFailedReadKeepsTheLastStateAndTriesAgain() {
		AtomicInteger reads = new AtomicInteger();
		ContactConnectionDiagnostics diagnostics = c -> {
			if (reads.incrementAndGet() == 2) throw new DbException();
			return new ContactConnectionSnapshot(Tor.CONNECTING,
					Reach.NEEDS_TOR, UNKNOWN, UNKNOWN, PeerAddress.CURRENT,
					UNKNOWN, OwnRotation.IDLE, Authorization.NOT_SUPPORTED, 0,
					0, 0);
		};
		ContactConnectionViewModel vm = new ContactConnectionViewModel(
				(Application) RuntimeEnvironment.getApplication(), Runnable::run,
				mock(LifecycleManager.class), mock(TransactionManager.class),
				SAME_THREAD, diagnostics, mock(TorPublishTracker.class));
		vm.setContactId(new ContactId(1));
		AtomicReference<List<Line>> latest = new AtomicReference<>();
		vm.getLines().observeForever(latest::set);
		List<Line> first = latest.get();
		advance(ContactConnectionViewModel.REFRESH_MS);
		assertEquals(2, reads.get());
		assertEquals(first, latest.get());
		advance(ContactConnectionViewModel.REFRESH_MS);
		assertEquals(3, reads.get());
	}
}

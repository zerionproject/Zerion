package com.professor.zerion.android;

import android.content.SharedPreferences;

import com.professor.zerion.R;

import com.professor.zerion.android.vault.net.TorSocksGate;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ApplicationTeardownTest {

	private static final long WAIT_MS = 5_000L;

	private static int threadsNamed(String name) {
		int n = 0;
		for (Thread t : Thread.getAllStackTraces().keySet()) {
			if (t.isAlive() && name.equals(t.getName())) n++;
		}
		return n;
	}

	private static int threadsNamedAfterWait(String name)
			throws InterruptedException {
		long until = System.currentTimeMillis() + WAIT_MS;
		int n = threadsNamed(name);
		while (n > 0 && System.currentTimeMillis() < until) {
			Thread.sleep(50);
			n = threadsNamed(name);
		}
		return n;
	}

	@Test
	public void anEarlierScreenLeavesNoListBehind() throws Exception {
		TestZerionApplicationImpl app = (TestZerionApplicationImpl)
				RuntimeEnvironment.getApplication();
		org.robolectric.android.controller.ActivityController<
				androidx.appcompat.app.AppCompatActivity> controller =
				org.robolectric.Robolectric.buildActivity(
						androidx.appcompat.app.AppCompatActivity.class);
		androidx.appcompat.app.AppCompatActivity activity = controller.get();
		activity.setTheme(R.style.ZerionTheme_NoActionBar);
		controller.create();
		androidx.recyclerview.widget.RecyclerView list =
				new androidx.recyclerview.widget.RecyclerView(activity);
		list.setLayoutManager(
				new androidx.recyclerview.widget.LinearLayoutManager(activity));
		activity.setContentView(list);
		controller.start().resume().visible();
		assertTrue("a shown list is remembered by the test thread",
				TestZerionApplicationImpl.listsOfEarlierScreens() > 0);

		app.shutDownForTests();

		assertEquals("a finished test's lists are forgotten", 0,
				TestZerionApplicationImpl.listsOfEarlierScreens());
	}

	@Test
	public void anEarlierApplicationStopsWaitingForItsDatabase()
			throws Exception {
		TestZerionApplicationImpl app = (TestZerionApplicationImpl)
				RuntimeEnvironment.getApplication();
		AndroidComponent component = app.getApplicationComponent();
		java.util.concurrent.CountDownLatch waiting =
				new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch done =
				new java.util.concurrent.CountDownLatch(1);
		component.databaseExecutor().execute(() -> {
			waiting.countDown();
			try {
				component.lifecycleManager().waitForDatabase();
			} catch (InterruptedException e) {
				done.countDown();
			}
		});
		assertTrue(waiting.await(WAIT_MS, java.util.concurrent.TimeUnit
				.MILLISECONDS));
		Thread.sleep(200);

		app.shutDownForTests();

		assertTrue("the database wait of a finished test ends",
				done.await(WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS));
	}

	@Test
	public void anEarlierApplicationStopsItsBackgroundWork() throws Exception {
		TestZerionApplicationImpl app = (TestZerionApplicationImpl)
				RuntimeEnvironment.getApplication();
		AndroidComponent component = app.getApplicationComponent();
		component.meshTextSender();
		component.meshPresenceTracker();
		component.meshAttachmentSender();
		component.meshGroupSender();
		String[] workers = {"MeshBackground", "MeshPresence", "MeshAttach",
				"MeshGroupBackground"};
		for (String worker : workers) {
			assertTrue(worker + " waits in the background",
					threadsNamed(worker) > 0);
		}

		app.shutDownForTests();

		for (String worker : workers) {
			assertEquals(worker + " of a finished test ends", 0,
					threadsNamedAfterWait(worker));
		}
	}

	@Test
	public void aFinishedTestForgetsWhatItsMocksSaw() throws Exception {
		TestZerionApplicationImpl app = (TestZerionApplicationImpl)
				RuntimeEnvironment.getApplication();
		java.util.function.Consumer<Object> seen =
				org.mockito.Mockito.mock(java.util.function.Consumer.class);
		Object[] witness = new Object[] {seen};
		seen.accept(witness);
		java.lang.ref.WeakReference<Object> ref =
				new java.lang.ref.WeakReference<>(witness);
		witness = null;
		seen = null;
		System.gc();
		assertTrue("a mock remembers what it was given", ref.get() != null);

		app.afterTest(ApplicationTeardownTest.class.getDeclaredMethod(
				"aFinishedTestForgetsWhatItsMocksSaw"));
		org.mockito.Mockito.mock(Runnable.class).run();
		for (int i = 0; i < 20 && ref.get() != null; i++) {
			System.gc();
			Thread.sleep(50);
		}

		assertEquals("after the test the mock lets it go", null, ref.get());
	}

	@Test
	public void anEarlierApplicationLeavesNoThreadsBehind() throws Exception {
		TestZerionApplicationImpl app = (TestZerionApplicationImpl)
				RuntimeEnvironment.getApplication();
		TorSocksGate gate = app.getApplicationComponent().torSocksGate();
		int port = gate.port();
		SharedPreferences prefs = app.getSharedPreferences("teardown", 0);
		prefs.edit().putInt("probe", 1).apply();
		assertTrue("the gate waits for wallet connections",
				threadsNamed("tor-socks-gate") > 0);
		assertTrue("saving settings started a worker thread",
				threadsNamed("queued-work-looper") > 0);

		app.shutDownForTests();

		assertEquals("the gate of a finished test stops waiting", 0,
				threadsNamedAfterWait("tor-socks-gate"));
		assertEquals("the settings worker of a finished test ends", 0,
				threadsNamedAfterWait("queued-work-looper"));
		try (Socket s = new Socket()) {
			s.connect(new InetSocketAddress(java.net.InetAddress.getByName(
					"127.0.0.1"), port), 500);
			fail("the gate still accepts connections");
		} catch (IOException expected) {
		}
	}
}

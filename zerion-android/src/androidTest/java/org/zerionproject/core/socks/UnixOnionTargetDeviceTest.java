package org.zerionproject.core.socks;

import android.content.Context;
import android.system.Os;
import android.system.StructStat;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.zerionproject.core.api.plugin.OnionTargetListener;
import org.zerionproject.core.api.plugin.OnionTargets;

import java.io.File;
import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class UnixOnionTargetDeviceTest {

	private final Context context = ApplicationProvider.getApplicationContext();
	private final File dir = new File(context.getFilesDir(), "zo-test");

	@After
	public void tearDown() {
		File[] left = dir.listFiles();
		if (left != null) for (File f : left) f.delete();
		dir.delete();
	}

	@Test(timeout = 30_000)
	public void aListenerIsAPrivateUnixSocketThatAConnectionReaches()
			throws Exception {
		UnixOnionTargetFactory factory = new UnixOnionTargetFactory(dir);
		OnionTargetListener listener = factory.open();
		String target = listener.getTorTarget();
		assertTrue(target, OnionTargets.isUnix(target));
		String path = target.substring("unix:".length());
		assertTrue(path.startsWith(dir.getAbsolutePath() + "/"));
		StructStat st = Os.stat(dir.getAbsolutePath());
		assertEquals("the directory is open to others", 0, st.st_mode & 077);

		AtomicReference<Socket> accepted = new AtomicReference<>();
		CountDownLatch done = new CountDownLatch(1);
		new Thread(() -> {
			try {
				accepted.set(listener.accept());
			} catch (IOException ignored) {
			}
			done.countDown();
		}).start();
		try (Socket client = LocalSockets.connect(path, 5_000)) {
			assertTrue(done.await(10, TimeUnit.SECONDS));
			client.getOutputStream().write(42);
			client.getOutputStream().flush();
			assertEquals(42, accepted.get().getInputStream().read());
			accepted.get().close();
		}
		listener.close();
		assertFalse("the socket file is left", new File(path).exists());
	}

	@Test(timeout = 30_000)
	public void closingEndsAWaitingAccept() throws Exception {
		OnionTargetListener listener =
				new UnixOnionTargetFactory(dir).open();
		CountDownLatch ended = new CountDownLatch(1);
		new Thread(() -> {
			try {
				listener.accept();
			} catch (IOException expected) {
			}
			ended.countDown();
		}).start();
		Thread.sleep(300);
		listener.close();
		assertTrue("a waiting accept outlived the close",
				ended.await(5, TimeUnit.SECONDS));
		assertTrue(listener.isClosed());
	}
}

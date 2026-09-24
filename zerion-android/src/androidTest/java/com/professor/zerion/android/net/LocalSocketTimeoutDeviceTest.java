package com.professor.zerion.android.net;

import android.content.Context;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Verifies on the device that a read on a Unix domain socket is bounded by
 * LocalSocket.setSoTimeout, which the Tor SOCKS client relies on for its
 * connect and read deadlines. Records which exception type the platform
 * raises, because Android's LocalSocketImpl reports the timeout as a plain
 * IOException("Try again") rather than a SocketTimeoutException.
 */
@RunWith(AndroidJUnit4.class)
public class LocalSocketTimeoutDeviceTest {

	private static final int TIMEOUT_MS = 1500;
	private static final int SLACK_MS = 1500;

	@Test
	public void readOnAbstractSocketIsBoundedByTimeout() throws Exception {
		LocalServerSocket server = new LocalServerSocket(
				"zerion-timeout-test-" + System.nanoTime());
		try {
			LocalSocket client = new LocalSocket(LocalSocket.SOCKET_STREAM);
			client.connect(new LocalSocketAddress(
					server.getLocalSocketAddress().getName()));
			LocalSocket accepted = server.accept();
			assertBounded(client);
			accepted.close();
			client.close();
		} finally {
			server.close();
		}
	}

	@Test
	public void readOnFilesystemSocketIsBoundedByTimeout() throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		File dir = new File(ctx.getFilesDir(), "zs-test");
		assertTrue(dir.isDirectory() || dir.mkdirs());
		File path = new File(dir, "t" + (System.nanoTime() % 100000));
		LocalSocket bound = new LocalSocket(LocalSocket.SOCKET_STREAM);
		bound.bind(new LocalSocketAddress(path.getAbsolutePath(),
				LocalSocketAddress.Namespace.FILESYSTEM));
		FileDescriptor fd = bound.getFileDescriptor();
		LocalServerSocket server = new LocalServerSocket(fd);
		try {
			LocalSocket client = new LocalSocket(LocalSocket.SOCKET_STREAM);
			client.connect(new LocalSocketAddress(path.getAbsolutePath(),
					LocalSocketAddress.Namespace.FILESYSTEM));
			LocalSocket accepted = server.accept();
			assertBounded(client);
			accepted.close();
			client.close();
		} finally {
			server.close();
			bound.close();
			path.delete();
			dir.delete();
		}
	}

	private static void assertBounded(LocalSocket client) throws Exception {
		client.setSoTimeout(TIMEOUT_MS);
		InputStream in = client.getInputStream();
		long start = System.currentTimeMillis();
		String kind;
		try {
			int r = in.read();
			fail("read returned " + r + " instead of timing out");
			return;
		} catch (SocketTimeoutException e) {
			kind = "SocketTimeoutException";
		} catch (IOException e) {
			kind = e.getClass().getSimpleName() + ": " + e.getMessage();
		}
		long elapsed = System.currentTimeMillis() - start;
		assertTrue("read ended after " + elapsed + " ms with " + kind
				+ ", expected about " + TIMEOUT_MS,
				elapsed >= TIMEOUT_MS - 200 && elapsed <= TIMEOUT_MS + SLACK_MS);
		System.out.println("LocalSocket timeout enforced after " + elapsed
				+ " ms, platform raised " + kind);
	}
}

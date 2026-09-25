package com.professor.zerion.android.net;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.zerionproject.tor.TorBinaryPins;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * SC-TOR-02 on the device: the Tor and lyrebird executables the package
 * manager installed from this APK are exactly the pinned builds, and a copy
 * with a single changed byte is refused by the same check the wrapper runs
 * before every start.
 */
@RunWith(AndroidJUnit4.class)
public class TorBinaryPinsDeviceTest {

	private static File nativeLib(String name) {
		Context ctx = ApplicationProvider.getApplicationContext();
		return new File(ctx.getApplicationInfo().nativeLibraryDir, name);
	}

	@Test
	public void theInstalledExecutablesMatchThePins() throws Exception {
		File tor = nativeLib("libtor.so");
		File lyrebird = nativeLib("liblyrebird.so");
		assertTrue(tor.getAbsolutePath(), tor.isFile());
		assertTrue(lyrebird.getAbsolutePath(), lyrebird.isFile());
		TorBinaryPins.shipped().verify(tor, lyrebird);
	}

	@Test
	public void aCopyWithOneChangedByteIsRefused() throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		File tor = nativeLib("libtor.so");
		File lyrebird = nativeLib("liblyrebird.so");
		File tampered = new File(ctx.getCacheDir(), "libtor-tampered.so");
		try {
			copyWithFlippedByte(tor, tampered, 4096);
			try {
				TorBinaryPins.shipped().verify(tampered, lyrebird);
				fail("a tampered Tor executable was accepted");
			} catch (IOException expected) {
				assertTrue(expected.getMessage(),
						expected.getMessage().contains("libtor.so"));
			}
		} finally {
			tampered.delete();
		}
	}

	@Test
	public void aTruncatedCopyIsRefused() throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		File tor = nativeLib("libtor.so");
		File lyrebird = nativeLib("liblyrebird.so");
		File truncated = new File(ctx.getCacheDir(), "liblyrebird-short.so");
		try {
			copyPrefix(lyrebird, truncated, lyrebird.length() - 1);
			try {
				TorBinaryPins.shipped().verify(tor, truncated);
				fail("a truncated lyrebird executable was accepted");
			} catch (IOException expected) {
				assertTrue(expected.getMessage(),
						expected.getMessage().contains("liblyrebird.so"));
			}
		} finally {
			truncated.delete();
		}
	}

	private static void copyWithFlippedByte(File from, File to, long at)
			throws IOException {
		try (InputStream in = new FileInputStream(from);
				OutputStream out = new FileOutputStream(to)) {
			byte[] buf = new byte[65536];
			long pos = 0;
			int n;
			while ((n = in.read(buf)) != -1) {
				if (at >= pos && at < pos + n) buf[(int) (at - pos)] ^= 0x01;
				out.write(buf, 0, n);
				pos += n;
			}
		}
	}

	private static void copyPrefix(File from, File to, long bytes)
			throws IOException {
		try (InputStream in = new FileInputStream(from);
				OutputStream out = new FileOutputStream(to)) {
			byte[] buf = new byte[65536];
			long remaining = bytes;
			int n;
			while (remaining > 0 && (n = in.read(buf, 0,
					(int) Math.min(buf.length, remaining))) != -1) {
				out.write(buf, 0, n);
				remaining -= n;
			}
		}
	}
}

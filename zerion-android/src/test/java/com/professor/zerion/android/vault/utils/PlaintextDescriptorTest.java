package com.professor.zerion.android.vault.utils;

import android.app.Application;
import android.os.ParcelFileDescriptor;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PlaintextDescriptorTest {

	@Test
	public void aDocumentIsReadFromMemoryNotFromTheCache() throws Exception {
		Application app = RuntimeEnvironment.getApplication();
		byte[] pdf = "%PDF-1.4 decrypted document".getBytes(
				StandardCharsets.US_ASCII);
		File cache = app.getCacheDir();
		File outside = File.createTempFile("memfd", null);
		outside.deleteOnExit();
		int before = count(cache);
		ParcelFileDescriptor pfd = PlaintextDescriptor.open(pdf.clone(),
				cache, 30, bytes -> {
					Files.write(outside.toPath(), bytes);
					return ParcelFileDescriptor.open(outside,
							ParcelFileDescriptor.MODE_READ_ONLY);
				});
		byte[] read = new byte[pdf.length];
		int n;
		try (FileInputStream in = new FileInputStream(
				pfd.getFileDescriptor())) {
			n = in.read(read);
		}
		assertEquals("files added to the cache 0, content intact",
				"files added to the cache " + (count(cache) - before)
						+ ", content " + (n == pdf.length
						&& Arrays.equals(pdf, read) ? "intact" : "differs"));
	}

	private static int count(File dir) {
		File[] files = dir.listFiles();
		return files == null ? 0 : files.length;
	}
}

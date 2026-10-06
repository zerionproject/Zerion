package com.professor.zerion.android.util;

import android.content.Intent;
import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SafeIntentsTest {

	private static final class HostileIntent extends Intent {
		boolean replaced;

		@Override
		public Bundle getExtras() {
			if (replaced) return null;
			throw new RuntimeException("android.os.BadParcelableException");
		}

		@Override
		public Intent replaceExtras(Bundle extras) {
			replaced = true;
			return this;
		}
	}

	@Test
	public void unreadableExtrasAreDropped() {
		HostileIntent i = new HostileIntent();
		assertFalse(SafeIntents.dropUnreadableExtras(i));
		assertTrue(i.replaced);
		assertNull(i.getExtras());
	}

	@Test
	public void readableExtrasAreKept() {
		Intent i = new Intent();
		i.putExtra(Intent.EXTRA_TEXT, "zerion://x");
		assertTrue(SafeIntents.dropUnreadableExtras(i));
		assertEquals("zerion://x", i.getStringExtra(Intent.EXTRA_TEXT));
		assertTrue(SafeIntents.dropUnreadableExtras(null));
	}

	@Test
	public void everyExportedActivityGuardsFirst() throws Exception {
		String[][] entries = {
				{"splash/SplashScreenActivity.java",
						"public void onCreate(@Nullable Bundle state) {"},
				{"channel/ChannelInviteHandlerActivity.java",
						"public void onCreate(@Nullable Bundle savedInstanceState) {"},
				{"panic/PanicResponderActivity.java",
						"public void onCreate(@Nullable Bundle savedInstanceState) {"},
				{"navdrawer/NavDrawerActivity.java",
						"public void onCreate(@Nullable Bundle state) {"},
				{"navdrawer/NavDrawerActivity.java",
						"protected void onNewIntent(Intent intent) {"}};
		for (String[] e : entries) {
			String src = new String(Files.readAllBytes(Paths.get(
					"src/main/java/com/professor/zerion/android/" + e[0])),
					StandardCharsets.UTF_8);
			int i = src.indexOf(e[1]);
			assertTrue(e[0], i >= 0);
			String next = src.substring(i + e[1].length(),
					src.indexOf(';', i + e[1].length()));
			assertTrue(e[0] + " " + e[1], next.contains(
					"SafeIntents\n\t\t\t\t.dropUnreadableExtras("));
		}
	}
}

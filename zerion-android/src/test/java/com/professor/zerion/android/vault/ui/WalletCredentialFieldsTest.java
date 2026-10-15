package com.professor.zerion.android.vault.ui;

import android.widget.EditText;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class WalletCredentialFieldsTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String SRC = "src/main/java/com/professor/zerion/"
			+ "android/vault/ui/VaultWalletFragment.java";

	@Test
	public void takingACredentialEmptiesItsField() throws Exception {
		EditText field = new EditText(ApplicationProvider.getApplicationContext());
		field.setText("4711");
		Method chars = VaultWalletFragment.class.getDeclaredMethod("chars",
				EditText.class);
		chars.setAccessible(true);
		char[] taken = (char[]) chars.invoke(null, field);
		assertArrayEquals("4711".toCharArray(), taken);
		assertEquals(0, field.getText().length());
	}

	@Test
	public void everySigningDialogTakesItsCredentialThroughTheClearingPath()
			throws Exception {
		String s = new String(Files.readAllBytes(Paths.get(SRC)),
				StandardCharsets.UTF_8);
		for (String dialog : new String[] {"private void showPayjoinReview(",
				"private void showAuthSendDialog(",
				"private void showSpSweepReviewDialog("}) {
			String body = body(s, dialog);
			assertTrue(dialog, body.contains("chars(pin)"));
			assertFalse(dialog, body.contains("pin.getText()"));
		}
	}

	private static String body(String s, String signature) {
		int i = s.indexOf(signature);
		assertTrue(signature, i >= 0);
		int open = s.indexOf('{', s.indexOf(')', i));
		int depth = 0;
		for (int k = open; k < s.length(); k++) {
			char c = s.charAt(k);
			if (c == '{') depth++;
			if (c == '}' && --depth == 0) return s.substring(open, k + 1);
		}
		throw new AssertionError("unbalanced");
	}
}

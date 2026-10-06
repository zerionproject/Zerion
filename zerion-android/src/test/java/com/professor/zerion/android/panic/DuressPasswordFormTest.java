package com.professor.zerion.android.panic;

import android.app.Application;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class DuressPasswordFormTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Application app = RuntimeEnvironment.getApplication();

	private static void freshDuressManager() throws Exception {
		java.lang.reflect.Field f = com.professor.zerion.android.panic
				.WipePasswordManager.class.getDeclaredField("instance");
		f.setAccessible(true);
		f.set(null, null);
	}

	private boolean[] run(String set, String typed) throws Exception {
		freshDuressManager();
		boolean[] out = new boolean[2];
		Thread t = new Thread(() -> {
			WipePasswordManager wpm = WipePasswordManager.getInstance(app);
			out[0] = wpm != null && wpm.setWipePassword(set.toCharArray());
			out[1] = wpm != null && wpm.verifyWipePassword(
					typed.toCharArray());
		});
		t.start();
		t.join();
		return out;
	}

	@Test
	public void aDuressPasswordSetOneWayIsRecognisedTypedAnother()
			throws Exception {
		boolean[] r = run("dur​ess pass 8&Gh", "duress pass 8&Gh");
		assertEquals("recognised", r[1] ? "recognised" : "not recognised");
	}
}

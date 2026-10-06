package com.professor.zerion.android.settings;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.core.account.ProfileManager;
import org.zerionproject.core.util.IoUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import androidx.fragment.app.FragmentActivity;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ProfilesScreenHiddenProfileTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String DECOY_NAME = "Dana Decoy";
	private static final String HIDDEN_NAME = "Hana Hidden";

	@After
	public void tearDown() {
		Context app = RuntimeEnvironment.getApplication();
		IoUtils.deleteFileOrDir(new File(app.getFilesDir(), "profiles"));
	}

	private static void collectText(View v, List<String> out) {
		if (v instanceof TextView) {
			CharSequence t = ((TextView) v).getText();
			if (t != null) out.add(t.toString());
		}
		if (v instanceof ViewGroup) {
			ViewGroup g = (ViewGroup) v;
			for (int i = 0; i < g.getChildCount(); i++) {
				collectText(g.getChildAt(i), out);
			}
		}
	}

	@Test
	public void theProfilesScreenNamesAndCountsNoOtherProfile() {
		Context app = RuntimeEnvironment.getApplication();
		com.professor.zerion.android.AppModule.getAndroidComponent(app)
				.accountManager();
		ProfileManager profiles = new ProfileManager(app);
		profiles.createProfileDir(ProfileManager.DEFAULT_PROFILE_ID);
		assertTrue(profiles.writeDisplayName(
				ProfileManager.DEFAULT_PROFILE_ID, DECOY_NAME));
		profiles.createProfileDir("b7c1");
		assertTrue(profiles.writeDisplayName("b7c1", HIDDEN_NAME));

		FragmentActivity host = Robolectric.buildActivity(
				FragmentActivity.class).setup().get();
		host.setTheme(R.style.ZerionTheme_NoActionBar);
		host.getSupportFragmentManager().beginTransaction()
				.add(android.R.id.content, new ProfilesFragment())
				.commitNow();
		shadowOf(Looper.getMainLooper()).idle();

		List<String> shown = new ArrayList<>();
		collectText(host.findViewById(android.R.id.content), shown);
		String all = String.join("\n", shown);
		assertTrue("the signed-in profile is shown", all.contains(DECOY_NAME));
		assertFalse("the hidden profile's name is not",
				all.contains(HIDDEN_NAME));
		assertFalse("nor a count of profiles", all.contains(
				host.getString(R.string.profiles_count_other, 2)));
		assertFalse(all.contains(host.getString(R.string.profiles_count_one)));
		assertFalse(all.contains(
				host.getString(R.string.profiles_row_tap_to_switch)));
	}
}

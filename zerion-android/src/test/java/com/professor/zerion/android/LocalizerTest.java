package com.professor.zerion.android;

import android.content.Context;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Locale;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class LocalizerTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String ARABIC_OFF = "إيقاف";

	private Locale before;

	@Before
	public void setUp() {
		before = Locale.getDefault();
	}

	@After
	public void tearDown() {
		Localizer.forceReinitialize("default");
		Localizer.getInstance().updateResources(
				RuntimeEnvironment.getApplication());
		Locale.setDefault(before);
	}

	private static Context apply(String tag) {
		Localizer.forceReinitialize(tag);
		return Localizer.getInstance().applyLocaleToContext(
				RuntimeEnvironment.getApplication());
	}

	private static Locale localeOf(Context ctx) {
		return ctx.getResources().getConfiguration().getLocales().get(0);
	}

	@Test
	public void systemDefaultAfterAnotherLanguageGoesBackToTheSystemLanguage() {
		Context system = apply("default");
		Locale systemLocale = localeOf(system);
		String off = system.getString(R.string.off);

		Context ar = apply("ar");
		assertEquals("ar", localeOf(ar).getLanguage());
		assertEquals("ar", Locale.getDefault().getLanguage());
		assertEquals(ARABIC_OFF, ar.getString(R.string.off));

		Context back = apply("default");
		assertEquals(systemLocale, localeOf(back));
		assertEquals(systemLocale, Locale.getDefault());
		assertEquals(off, back.getString(R.string.off));
	}

	@Test
	public void theAppWideTextsFollowALanguageChangeWithoutARestart() {
		Context app = RuntimeEnvironment.getApplication();
		Localizer.forceReinitialize("default");
		Localizer.getInstance().updateResources(app);
		String off = app.getString(R.string.off);

		Localizer.forceReinitialize("ar");
		Localizer.getInstance().updateResources(app);
		assertEquals(ARABIC_OFF, app.getString(R.string.off));

		Localizer.forceReinitialize("default");
		Localizer.getInstance().updateResources(app);
		assertEquals(off, app.getString(R.string.off));
	}

	@Test
	public void switchingBetweenTwoLanguagesUsesTheLastOne() {
		apply("de");
		Context nl = apply("nl");
		assertEquals("Uit", nl.getString(R.string.off));
		assertEquals("nl", Locale.getDefault().getLanguage());
	}
}

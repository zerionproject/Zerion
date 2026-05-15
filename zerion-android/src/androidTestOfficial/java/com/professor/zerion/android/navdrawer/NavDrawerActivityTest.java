package com.professor.zerion.android.navdrawer;

import com.professor.zerion.R;
import com.professor.zerion.android.BriarUiTestComponent;
import com.professor.zerion.android.UiTest;
import com.professor.zerion.android.settings.SettingsActivity;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intended;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

@RunWith(AndroidJUnit4.class)
public class NavDrawerActivityTest extends UiTest {

	@Rule
	public CleanAccountTestRule<NavDrawerActivity> testRule =
			new CleanAccountTestRule<>(NavDrawerActivity.class);

	@Override
	protected void inject(BriarUiTestComponent component) {
		component.inject(this);
	}

	@Test
	public void openSettings() {

		onView(withId(R.id.menuButton))
				.check(matches(isDisplayed()))
				.perform(click());

		onView(withText(R.string.settings_button))
				.check(matches(isDisplayed()))
				.perform(click());
		intended(hasComponent(SettingsActivity.class.getName()));
	}

}

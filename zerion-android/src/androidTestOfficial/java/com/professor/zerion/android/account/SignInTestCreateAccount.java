package com.professor.zerion.android.account;

import com.professor.zerion.R;
import com.professor.zerion.android.BriarUiTestComponent;
import com.professor.zerion.android.UiTest;
import com.professor.zerion.android.navdrawer.NavDrawerActivity;
import com.professor.zerion.android.splash.SplashScreenActivity;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static androidx.test.espresso.matcher.ViewMatchers.withClassName;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static com.professor.zerion.android.ViewActions.waitFor;
import static com.professor.zerion.android.ViewActions.waitUntilMatches;
import static org.hamcrest.Matchers.endsWith;

@RunWith(AndroidJUnit4.class)
public class SignInTestCreateAccount extends UiTest {

	@Override
	protected void inject(BriarUiTestComponent component) {
		component.inject(this);
	}

	@Test
	public void createAccount() throws Exception {
		accountManager.deleteAccount();
		accountManager.createAccount(USERNAME, PASSWORD);

		startActivity(SplashScreenActivity.class);
		lifecycleManager.waitForStartup();
		waitFor(NavDrawerActivity.class);

		onView(withId(R.id.menuButton))
				.check(matches(isDisplayed()))
				.perform(click());

		onView(isRoot()).perform(waitUntilMatches(hasDescendant(
				withClassName(endsWith("PromptView")))));
		onView(withClassName(endsWith("PromptView")))
				.perform(click());

		onView(withText(R.string.sign_out_button))
				.check(matches(isDisplayed()))
				.perform(click());
		lifecycleManager.waitForShutdown();
	}

}

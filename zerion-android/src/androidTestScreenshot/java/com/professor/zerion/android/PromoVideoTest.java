package com.professor.zerion.android;

import android.view.View;

import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactState;
import com.professor.zerion.R;
import com.professor.zerion.android.account.SetupActivity;
import com.professor.zerion.android.contact.add.remote.PendingContactListActivity;
import com.professor.zerion.android.navdrawer.NavDrawerActivity;
import com.professor.zerion.android.splash.SplashScreenActivity;
import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import javax.inject.Inject;

import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiSelector;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.action.ViewActions.closeSoftKeyboard;
import static androidx.test.espresso.action.ViewActions.replaceText;
import static androidx.test.espresso.action.ViewActions.scrollTo;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.contrib.RecyclerViewActions.actionOnItemAtPosition;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import static java.lang.Thread.sleep;
import static org.briarproject.android.dontkillmelib.DozeUtils.needsDozeWhitelisting;
import static org.zerionproject.core.api.plugin.LanTcpConstants.ID;
import static org.zerionproject.app.android.OverlayTapViewAction.visualClick;
import static org.zerionproject.app.android.ViewActions.waitFor;
import static org.zerionproject.app.android.ViewActions.waitUntilMatches;
import static org.hamcrest.CoreMatchers.allOf;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class PromoVideoTest extends ScreenshotTest {

	private static final boolean isFilming = false;

	private static final int DELAY_SMALL = isFilming ? 4_000 : 0;
	private static final int DELAY_MEDIUM = isFilming ? 7_500 : 0;
	private static final int DELAY_LONG = isFilming ? 10_000 : 0;

	@Rule
	public ActivityScenarioRule<SplashScreenActivity> testRule =
			new ActivityScenarioRule<>(SplashScreenActivity.class);

	@Inject
	protected ContactManager contactManager;

	private OverlayView overlayView;

	@Override
	protected void inject(BriarUiTestComponent component) {
		component.inject(this);
		accountManager.deleteAccount();
	}

	@Test
	public void createAccountAddContact() throws Throwable {
		if (isFilming) {

			overlayView = OverlayView.attach(getApplicationContext());
		}

		onView(withId(R.id.logoView))
				.perform(waitUntilMatches(isDisplayed()));

		if (!isFilming) waitFor(SetupActivity.class, 30_000);

		sleep(DELAY_LONG);

		onView(withText(R.string.setup_title))
				.perform(waitUntilMatches(isDisplayed()));
		sleep(DELAY_SMALL);
		onView(withId(R.id.nickname_entry))
				.check(matches(isDisplayed()))
				.perform(replaceText(USERNAME));
		closeKeyboard(withId(R.id.nickname_entry));

		sleep(DELAY_SMALL);

		doClick(withId(R.id.next));

		sleep(DELAY_MEDIUM);

		doClick(withId(R.id.password_entry), 1000);
		onView(withId(R.id.password_entry))
				.check(matches(isDisplayed()))
				.perform(replaceText(PASSWORD));
		sleep(DELAY_SMALL);
		doClick(withId(R.id.password_confirm), 1000);
		onView(withId(R.id.password_confirm))
				.check(matches(isDisplayed()))
				.perform(replaceText(PASSWORD));

		sleep(DELAY_SMALL);

		doClick(withId(R.id.next));

		sleep(DELAY_SMALL);

		if (needsDozeWhitelisting(getApplicationContext())) {
			doClick(withText(R.string.dnkm_doze_button));
			UiDevice device = UiDevice.getInstance(getInstrumentation());
			UiObject allowButton = device.findObject(
					new UiSelector().className("android.widget.Button")
							.index(1));
			allowButton.click();
			doClick(withId(R.id.next));
		}

		lifecycleManager.waitForStartup();
		assertTrue(accountManager.hasDatabaseKey());

		sleep(DELAY_SMALL);

		if (!isFilming) waitFor(NavDrawerActivity.class);

		onView(withId(R.id.speedDial))
				.check(matches(isDisplayed()))
				.perform(click());
		doClick(withId(R.id.fab_main));
		sleep(DELAY_MEDIUM);

		doClick(withText(R.string.add_contact_remotely_title));
		sleep(DELAY_LONG);

		String link =
				"briar://ab54fpik6sjyetzjhlwto2fv7tspibx2uhpdnei4tdidkvjpbphvy";
		doClick(withId(R.id.pasteButton));
		onView(withId(R.id.linkInput))
				.perform(waitUntilMatches(isDisplayed()))
				.perform(replaceText(link));
		sleep(DELAY_MEDIUM);

		doClick(withId(R.id.addButton));
		sleep(DELAY_MEDIUM);

		String contactName = getApplicationContext()
				.getString(R.string.screenshot_bob);
		doClick(withId(R.id.contactNameInput), 1000);
		onView(withId(R.id.contactNameInput))
				.perform(waitUntilMatches(isDisplayed()))
				.perform(replaceText(contactName));
		sleep(DELAY_SMALL);
		closeKeyboard(withId(R.id.contactNameInput));
		sleep(DELAY_SMALL);

		onView(withId(R.id.addButton)).perform(scrollTo());
		doClick(withId(R.id.addButton));
		sleep(DELAY_LONG);

		if (!isFilming) {
			waitFor(PendingContactListActivity.class);
			waitFor(allOf(withText(R.string.pending_contact_requests),
					isDisplayed()));
		}

		for (Pair<PendingContact, PendingContactState> p : contactManager
				.getPendingContacts()) {
			contactManager.removePendingContact(p.getFirst().getId());
		}

		Contact bob = testDataCreator.addContact(contactName, false, true);
		sleep(DELAY_SMALL);
		connectionRegistry.registerIncomingConnection(bob.getId(), ID, () -> {
		});

		sleep(DELAY_LONG);

		if (!isFilming) {
			waitFor(NavDrawerActivity.class);
			waitFor(allOf(withText(R.string.contact_list_button),
					isDisplayed()));
			waitFor(allOf(withId(R.id.recyclerView), isDisplayed()));
		}

		doItemClick(withId(R.id.recyclerView), 0);

		sleep(DELAY_MEDIUM);

		doClick(withId(R.id.input_text), DELAY_SMALL);

		String msg1 = getApplicationContext()
				.getString(R.string.screenshot_message_1);
		onView(withId(R.id.input_text))
				.perform(waitUntilMatches(isEnabled()))
				.perform(replaceText(msg1));

		sleep(DELAY_SMALL);

		doClick(withId(R.id.compositeSendButton));

		sleep(DELAY_SMALL);

		doClick(withId(R.id.emoji_toggle), DELAY_SMALL);
		onView(withId(R.id.input_text))
				.perform(replaceText("\uD83D\uDE0E"));
		sleep(DELAY_SMALL);
		doClick(withId(R.id.compositeSendButton));

		closeKeyboard(withId(R.id.compositeSendButton));

		sleep(DELAY_LONG);
	}

	private void doClick(final Matcher<View> viewMatcher, long sleepMs)
			throws InterruptedException {
		doClick(viewMatcher);
		if (isFilming) sleep(sleepMs);
	}

	private void doClick(final Matcher<View> viewMatcher)
			throws InterruptedException {
		if (isFilming) {
			onView(viewMatcher)
					.perform(waitUntilMatches(isDisplayed()))
					.perform(visualClick(overlayView));
			sleep(500);
		}
		onView(viewMatcher)
				.perform(waitUntilMatches(allOf(isDisplayed(), isEnabled())))
				.perform(click());
	}

	private void doItemClick(final Matcher<View> viewMatcher, int pos)
			throws InterruptedException {
		if (isFilming) {
			onView(viewMatcher).perform(
					actionOnItemAtPosition(pos, visualClick(overlayView)));
			sleep(500);
		}
		onView(viewMatcher).perform(
				actionOnItemAtPosition(pos, click()));
	}

	private void closeKeyboard(final Matcher<View> viewMatcher)
			throws InterruptedException {
		if (isFilming) sleep(750);
		onView(viewMatcher).perform(closeSoftKeyboard());
	}

}

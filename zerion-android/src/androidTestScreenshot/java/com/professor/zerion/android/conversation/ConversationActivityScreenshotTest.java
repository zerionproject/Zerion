package com.professor.zerion.android.conversation;

import android.content.Context;
import android.content.Intent;

import com.professor.zerion.android.BriarUiTestComponent;
import com.professor.zerion.android.ScreenshotTest;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.rule.ActivityTestRule;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;
import static org.zerionproject.app.android.conversation.ConversationActivity.CONTACT_ID;

@RunWith(AndroidJUnit4.class)
public class ConversationActivityScreenshotTest extends ScreenshotTest {

	@Rule
	public ActivityTestRule<ConversationActivity> testRule =
			new ActivityTestRule<>(ConversationActivity.class, false, false);

	@Override
	protected void inject(BriarUiTestComponent component) {
		component.inject(this);
	}

	@Test
	public void messaging() {
		Context targetContext = getApplicationContext();
		Intent intent = new Intent(targetContext, ConversationActivity.class);
		intent.putExtra(CONTACT_ID, 1);
		testRule.launchActivity(intent);

		screenshot("manual_messaging", testRule.getActivity());
	}

}

package com.professor.zerion.android.navdrawer;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 29)
public class ComposeButtonLabelTest {

	@Test
	public void theComposeButtonSaysWhatItDoesOnEveryTab() {
		assertEquals(R.string.add_contact_button,
				NavDrawerActivity.composeLabel(NavDrawerActivity.TAB_CONTACTS));
		assertEquals(R.string.grouptr_create_title,
				NavDrawerActivity.composeLabel(NavDrawerActivity.TAB_GROUPS));
		assertEquals(R.string.channels_action_create,
				NavDrawerActivity.composeLabel(NavDrawerActivity.TAB_CHANNELS));
	}
}

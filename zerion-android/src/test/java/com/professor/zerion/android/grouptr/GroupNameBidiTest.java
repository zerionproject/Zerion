package com.professor.zerion.android.grouptr;

import android.content.res.Resources;

import com.professor.zerion.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupNameBidiTest {

	private final Resources res =
			ApplicationProvider.getApplicationContext().getResources();

	@Test
	public void aGroupNameLosesItsDirectionControls() {
		assertEquals("Family",
				GroupTrListFragment.groupName(res, "\u2066Family\u202E"));
		assertEquals(res.getString(R.string.grouptr_unnamed_group),
				GroupTrListFragment.groupName(res, "\u202E"));
	}
}

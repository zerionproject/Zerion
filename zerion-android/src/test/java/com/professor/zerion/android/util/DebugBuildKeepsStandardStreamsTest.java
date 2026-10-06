package com.professor.zerion.android.util;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static com.professor.zerion.android.TestingConstants.IS_DEBUG_BUILD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class DebugBuildKeepsStandardStreamsTest {

	@Test
	public void aDebugBuildLeavesTheStandardStreamsToTheTestRunner() {
		assertTrue("this suite runs against the debug build", IS_DEBUG_BUILD);
		assertEquals("the application has started",
				"com.professor.zerion.debug",
				ApplicationProvider.getApplicationContext().getPackageName());
		assertFalse("the standard streams were silenced in a debug build",
				SilentStandardStreams.isInstalled());
	}
}

package com.professor.zerion.android.security;

import android.app.Activity;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

public class RecentsSnapshotsTest {

	@Test
	public void android13AndLaterKeepNoRecentsSnapshot() {
		Activity activity = mock(Activity.class);
		RecentsSnapshots.disable(activity, 33);
		verify(activity).setRecentsScreenshotEnabled(false);
		Activity later = mock(Activity.class);
		RecentsSnapshots.disable(later, 36);
		verify(later).setRecentsScreenshotEnabled(false);
	}

	@Test
	public void olderAndroidIsLeftAlone() {
		Activity activity = mock(Activity.class);
		RecentsSnapshots.disable(activity, 32);
		RecentsSnapshots.disable(activity, 29);
		verifyNoInteractions(activity);
	}

	@Test
	public void everyScreenBuiltOnTheBaseActivityAsksForIt() throws Exception {
		String src = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/activity/"
						+ "BaseActivity.java")), StandardCharsets.UTF_8);
		int i = src.indexOf("public void onCreate(@Nullable Bundle state)");
		assertTrue(i >= 0);
		int created = src.indexOf("super.onCreate(state);", i);
		int asked = src.indexOf("RecentsSnapshots.disable(this);", i);
		assertTrue(created > i && asked > created);
	}
}

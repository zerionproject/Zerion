package com.professor.zerion.android.settings;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ProfileCreationSignOutTest {

	@Test
	public void aProfileCreatedAfterTheScreenIsGoneStillSignsOut()
			throws Exception {
		String source = new String(Files.readAllBytes(Paths.get(
				"src/main/java/com/professor/zerion/android/settings/"
						+ "ProfilesFragment.java")), StandardCharsets.UTF_8);
		int start = source.indexOf("private void createProfile(");
		assertTrue(start > 0);
		int end = source.indexOf("\n\tprivate ", start + 1);
		String method = source.substring(start, end);

		int signOut = method.indexOf("ProfileSignOut.signOutAndRestart(");
		assertTrue("the application signs out when the fragment cannot",
				signOut > 0);
		assertFalse("creation does not stop at a missing activity",
				method.contains("if (activity == null) return;"));
		int guard = method.indexOf("if (!isAdded()) return;");
		assertTrue("the sign-out is not behind the attached-fragment guard",
				guard < 0 || signOut < guard);
	}
}

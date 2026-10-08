package com.professor.zerion.android.update;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class InstallSourceTest {

	@Test
	public void storesAreRecognisedAndEverythingElseCountsAsGitHub() {
		assertEquals("Google Play", InstallSource.labelFor("com.android.vending"));
		assertEquals("F-Droid", InstallSource.labelFor("org.fdroid.fdroid"));
		assertEquals("F-Droid", InstallSource.labelFor("com.looker.droidify"));
		assertEquals("Obtainium", InstallSource.labelFor("dev.imranr.obtainium"));
		assertNull("adb or file manager", InstallSource.labelFor(null));
		assertNull(InstallSource.labelFor("com.google.android.packageinstaller"));
		assertNull(InstallSource.labelFor("com.android.chrome"));
	}
}

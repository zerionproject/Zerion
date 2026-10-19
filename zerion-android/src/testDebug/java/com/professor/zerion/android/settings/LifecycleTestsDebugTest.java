package com.professor.zerion.android.settings;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.inject.Inject;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class LifecycleTestsDebugTest {

	private static final File RELEASE_MODULE = new File("src/release/java/"
			+ "com/professor/zerion/android/settings/LifecycleTestsModule.java");
	private static final File DEBUG_CARD = new File("src/debug/java/"
			+ "com/professor/zerion/android/settings/LifecycleTests.java");

	private static String read(File f) throws IOException {
		return new String(Files.readAllBytes(f.toPath()),
				StandardCharsets.UTF_8);
	}

	@Test
	public void theDebugCardInjectsWhatItUsesAndTheReleaseCardNothing()
			throws Exception {
		assertTrue(DEBUG_CARD.isFile());
		int injected = 0;
		for (Field f : LifecycleTests.class.getDeclaredFields()) {
			if (f.isAnnotationPresent(Inject.class)) injected++;
		}
		assertTrue("this unit test runs against the debug build",
				injected > 0);
		Method attach = LifecycleTests.class.getMethod("attach",
				androidx.fragment.app.Fragment.class,
				android.view.View.class);
		assertTrue(Modifier.isStatic(attach.getModifiers()));
		assertTrue(Modifier.isPublic(attach.getModifiers()));
		Method provide = LifecycleTestsModule.class.getDeclaredMethod(
				"provideOnionAuthDebugHooks",
				org.zerionproject.core.api.settings.SettingsManager.class);
		assertEquals(
				org.zerionproject.core.plugin.tor.auth.OnionAuthDebugHooks.class,
				provide.getReturnType());
		assertTrue(read(RELEASE_MODULE).contains(
				"OnionAuthDebugHooks provideOnionAuthDebugHooks() {"));
	}
}

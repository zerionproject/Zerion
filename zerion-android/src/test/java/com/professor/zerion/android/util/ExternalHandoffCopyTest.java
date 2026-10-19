package com.professor.zerion.android.util;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Looper;
import android.webkit.MimeTypeMap;

import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mockStatic;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ExternalHandoffCopyTest {

	static {
		TestAndroidKeyStore.register();
	}

	private Context ctx;
	private MockedStatic<FileProvider> provider;
	private Executor savedWiper;

	@Before
	public void setUp() {
		ctx = ApplicationProvider.getApplicationContext();
		savedWiper = ExternalHandoff.wiper;
		ExternalHandoff.wiper = Runnable::run;
		ExternalHandoff.end(ctx);
		shadowOf(MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("png", "image/png");
		provider = mockStatic(FileProvider.class);
		provider.when(() -> FileProvider.getUriForFile(any(), anyString(),
				any(File.class))).thenAnswer(inv -> {
					File f = inv.getArgument(2);
					return Uri.parse("content://" + inv.getArgument(1) + "/"
							+ f.getParentFile().getName() + "/" + f.getName());
				});
	}

	@After
	public void tearDown() {
		provider.close();
		ExternalHandoff.end(ctx);
		ExternalHandoff.wiper = savedWiper;
		ExternalHandoff.cleaner = ExternalHandoff.AS_IS;
	}

	private static byte[] content() {
		byte[] d = new byte[64];
		for (int i = 0; i < d.length; i++) d[i] = (byte) (i * 3 + 1);
		return d;
	}

	private static Activity screen() {
		return Robolectric.buildActivity(Activity.class).setup().get();
	}

	private static Intent nextView(Activity a) {
		Intent i;
		while ((i = shadowOf(a).getNextStartedActivity()) != null) {
			if (Intent.ACTION_VIEW.equals(i.getAction())) return i;
		}
		return null;
	}

	@Test
	public void aCopyHandedOverIsWipedOnceItIsStaged() throws Exception {
		Activity a = screen();
		byte[] copy = content();
		ExternalHandoff.openCopy(a, Runnable::run, copy, "text/plain",
				() -> {
				});
		shadowOf(Looper.getMainLooper()).idle();
		assertNotNull(nextView(a));
		File staged = ExternalHandoff.current();
		assertNotNull(staged);
		assertArrayEquals(content(), Files.readAllBytes(staged.toPath()));
		assertArrayEquals("the handed over copy is still in memory",
				new byte[copy.length], copy);
	}

	@Test
	public void theCallersOwnBytesAreLeftAlone() throws Exception {
		Activity a = screen();
		byte[] own = content();
		ExternalHandoff.open(a, Runnable::run, own, "text/plain", () -> {
		});
		shadowOf(Looper.getMainLooper()).idle();
		assertNotNull(nextView(a));
		assertArrayEquals(content(), own);
	}

	@Test
	public void aCleanedCopyIsWipedOnceItIsStaged() throws Exception {
		AtomicReference<byte[]> made = new AtomicReference<>();
		ExternalHandoff.cleaner = (c, data, type) -> {
			byte[] cleaned = data.clone();
			made.set(cleaned);
			return new ExternalHandoff.Prepared(cleaned, type);
		};
		Activity a = screen();
		byte[] own = content();
		ExternalHandoff.open(a, Runnable::run, own, "text/plain", () -> {
		});
		shadowOf(Looper.getMainLooper()).idle();
		assertNotNull(nextView(a));
		assertArrayEquals("the cleaned copy is still in memory",
				new byte[own.length], made.get());
		assertArrayEquals(content(), own);
	}

	@Test
	public void cancellingTheWarningWipesTheCopyAndStagesNothing() {
		Activity a = screen();
		byte[] copy = content();
		ExternalHandoff.openCopy(a, Runnable::run, copy, "application/pdf",
				() -> {
				});
		shadowOf(Looper.getMainLooper()).idle();
		Dialog dialog = ShadowDialog.getLatestDialog();
		assertNotNull(dialog);
		((AlertDialog) dialog).getButton(DialogInterface.BUTTON_NEGATIVE)
				.performClick();
		shadowOf(Looper.getMainLooper()).idle();
		assertNull(nextView(a));
		assertNull(ExternalHandoff.current());
		assertArrayEquals(new byte[copy.length], copy);
	}
}

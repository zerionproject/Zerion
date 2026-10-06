package com.professor.zerion.android.attachment;

import android.content.Context;
import android.net.Uri;

import com.professor.zerion.android.attachment.media.ImageCompressor;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.utils.MetadataStripper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.MessagingManager;
import org.zerionproject.app.api.messaging.PrivateMessageFormat;
import org.zerionproject.core.api.sync.GroupId;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PickedFilesTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Context ctx = ApplicationProvider.getApplicationContext();

	@Test
	public void aPrivateFileGivenAsAPathIsNotAttached() throws Exception {
		File secret = new File(ctx.getFilesDir(), "zt-private.jpg");
		Files.write(secret.toPath(), new byte[] {(byte) 0xFF, (byte) 0xD8,
				(byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F', 0});
		org.robolectric.Shadows.shadowOf(
				android.webkit.MimeTypeMap.getSingleton())
				.addExtensionMimeTypMapping("jpg", "image/jpeg");
		Uri uri = Uri.fromFile(secret);
		MessagingManager messaging = mock(MessagingManager.class);
		AttachmentCreator creator = mock(AttachmentCreator.class);

		new AttachmentCreationTask(messaging, ctx.getContentResolver(),
				creator, mock(ImageCompressor.class),
				new MetadataStripper(ctx), new GroupId(new byte[32]),
				Collections.singletonList(uri), false,
				PrivateMessageFormat.TEXT_IMAGES_CHUNKED).storeAttachments();

		verify(messaging, never()).addLocalAttachmentStreaming(any(),
				anyLong(), anyString(), any(), anyLong(), any());
		verify(messaging, never()).addLocalAttachment(any(), anyLong(),
				anyString(), any());
		verify(creator).onAttachmentError(eq(uri), any());
	}

	@Test
	public void everyPickerResultIsChecked() throws Exception {
		String[] files = {
				"account/WelcomeFragment.java",
				"channel/ChannelFeedActivity.java",
				"conversation/ConversationActivity.java",
				"grouptr/GroupTrConversationActivity.java",
				"settings/BackupFragment.java",
				"settings/SettingsFragment.java",
				"sticker/StickerPickerDialog.java",
				"vault/ui/VaultDocumentsFragment.java",
				"vault/ui/VaultGalleryFragment.java",
				"vault/ui/VaultListFragment.java"};
		for (String f : files) {
			String s = new String(Files.readAllBytes(Paths.get(
					"src/main/java/com/professor/zerion/android/" + f)),
					StandardCharsets.UTF_8);
			assertTrue(f + " reads a picker result unchecked",
					s.contains("PickedUris"));
		}
	}
}

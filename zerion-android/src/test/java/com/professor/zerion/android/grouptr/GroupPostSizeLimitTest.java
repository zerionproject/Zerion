package com.professor.zerion.android.grouptr;

import android.content.Intent;
import android.media.MediaRecorder;
import android.os.Looper;
import android.widget.EditText;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.attachment.JpegFit;
import com.professor.zerion.android.grouptr.voice.GroupTrVoiceRecorder;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.util.SnackbarProbe;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.grouptr.GroupTrAuthException;
import org.zerionproject.app.api.grouptr.GroupTrBody;
import org.zerionproject.app.api.grouptr.GroupTrManager;
import org.zerionproject.app.api.grouptr.GroupTrMember;
import org.zerionproject.app.api.grouptr.GroupTrSendResult;
import org.zerionproject.app.api.grouptr.GroupTrState;
import org.zerionproject.app.api.grouptr.MemberRole;
import org.zerionproject.core.api.crypto.SignaturePrivateKey;
import org.zerionproject.core.api.crypto.SignaturePublicKey;
import org.zerionproject.core.api.identity.AuthorId;
import org.zerionproject.core.api.identity.IdentityManager;
import org.zerionproject.core.api.identity.LocalAuthor;
import org.zerionproject.core.util.StringUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class GroupPostSizeLimitTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String GROUP_HEX =
			"00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";

	public static class ProbeConversation extends GroupTrConversationActivity {
		static GroupTrManager manager;
		static IdentityManager identity;
		static final List<Runnable> io = new ArrayList<>();

		@Override
		public void injectActivity(ActivityComponent component) {
			super.injectActivity(component);
			groupTrManager = manager;
			identityManager = identity;
			ioExecutor = io::add;
		}
	}

	private final byte[] creatorKey = key((byte) 1);
	private final byte[] localKey = key((byte) 2);
	private byte[] groupId;
	private GroupTrManager manager;
	private ProbeConversation activity;

	@Before
	public void setUp() throws Exception {
		groupId = StringUtils.fromHexString(GROUP_HEX);
		ProbeConversation.io.clear();
		manager = mock(GroupTrManager.class);
		IdentityManager identity = mock(IdentityManager.class);
		when(identity.getLocalAuthor()).thenReturn(new LocalAuthor(
				new AuthorId(key((byte) 9)), 1, "Local",
				new SignaturePublicKey(localKey),
				new SignaturePrivateKey(new byte[32])));
		ProbeConversation.identity = identity;
		List<GroupTrMember> members = new ArrayList<>();
		members.add(new GroupTrMember(creatorKey, "Creator", 0L, 0L,
				MemberRole.CREATOR));
		members.add(new GroupTrMember(localKey, "Local", 0L, 0L));
		when(manager.getGroup(any())).thenReturn(new GroupTrState(groupId,
				"Group", new byte[32], creatorKey, "Creator", 0L, 1L, false,
				members));
		when(manager.getMembersOutOfReach(any()))
				.thenReturn(Collections.emptyList());
		when(manager.getRecentPosts(any())).thenReturn(Collections.emptyList());
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenReturn(new GroupTrSendResult(key((byte) 12), 1, 1, 0, 0));
		ProbeConversation.manager = manager;
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeConversation.class);
		i.putExtra(GroupTrConversationActivity.EXTRA_GROUP_ID, GROUP_HEX);
		activity = Robolectric.buildActivity(ProbeConversation.class, i)
				.setup().get();
		runIo();
	}

	@Test
	public void theComposerLimitIsWhatOneSyncRecordCarries() {
		assertEquals(GroupTrManager.MAX_POST_BODY_LENGTH,
				GroupTrConversationActivity.MAX_POST_BODY_BYTES);
		assertTrue(GroupTrConversationActivity.MAX_POST_BODY_BYTES
				< 1024 * 1024);
	}

	@Test
	public void aTextTooLongForTheGroupIsRefusedAndKept() throws Exception {
		String text = ascii(GroupTrManager.MAX_POST_BODY_LENGTH + 1);
		input().setText(text);
		send();
		runIo();

		verify(manager, never()).sendGroupPostReporting(any(), any(),
				anyLong());
		assertEquals(activity.getString(R.string.grouptr_post_too_long),
				SnackbarProbe.text(activity));
		assertEquals(text.length(), input().getText().length());
	}

	@Test
	public void aTextAtTheGroupLimitIsSent() throws Exception {
		input().setText(ascii(GroupTrManager.MAX_POST_BODY_LENGTH));
		send();
		runIo();

		verify(manager).sendGroupPostReporting(eq(groupId), any(), eq(0L));
	}

	@Test
	public void mediaTooLargeForTheGroupIsRefusedWithTheLimit()
			throws Exception {
		byte[] body = GroupTrBody.encodeVideo(
				new byte[GroupTrManager.MAX_POST_BODY_LENGTH], "video/mp4",
				1000L);
		sendBody(body);
		runIo();

		verify(manager, never()).sendGroupPostReporting(any(), any(),
				anyLong());
		assertEquals(activity.getString(R.string.grouptr_post_too_large),
				SnackbarProbe.text(activity));
	}

	@Test
	public void mediaWithinTheGroupLimitIsSent() throws Exception {
		byte[] body = GroupTrBody.encodeImage(
				new byte[GroupTrConversationActivity.MAX_IMAGE_BYTES],
				"image/jpeg");
		sendBody(body);
		runIo();

		verify(manager).sendGroupPostReporting(eq(groupId), eq(body), eq(0L));
	}

	@Test
	public void aPostTheManagerFindsTooLargeSaysSo() throws Exception {
		when(manager.sendGroupPostReporting(any(), any(), anyLong()))
				.thenThrow(new GroupTrAuthException(
						GroupTrAuthException.Reason.POST_TOO_LARGE));
		input().setText("hello");
		send();
		runIo();

		assertEquals(activity.getString(R.string.grouptr_post_too_large),
				SnackbarProbe.text(activity));
	}

	@Test
	public void aVoiceRecordingStopsBeforeItOutgrowsTheGroupLimit() {
		assertTrue(GroupTrVoiceRecorder.MAX_FILE_BYTES + 7
				<= GroupTrManager.MAX_POST_BODY_LENGTH);
		assertTrue(GroupTrVoiceRecorder.isLimit(
				MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED));
		assertTrue(GroupTrVoiceRecorder.isLimit(
				MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED));
	}

	@Test
	public void anImageIsReencodedSmallerUntilItFitsTheGroupLimit()
			throws Exception {
		List<String> tried = new ArrayList<>();
		byte[] fitted = JpegFit.fit((quality, scale) -> {
			tried.add(quality + "@" + scale);
			return new byte[scale * quality * 100];
		}, 78, 50 * 50 * 100);

		assertEquals(50 * 50 * 100, fitted.length);
		assertEquals(Arrays.asList("78@100", "65@100", "50@100", "35@100",
				"78@75", "65@75", "50@75", "35@75", "78@50", "65@50",
				"50@50"), tried);
		assertNull(JpegFit.fit((quality, scale) -> new byte[2], 78, 1));
	}

	@Test
	public void anImageTheCodecCannotWriteIsAReadFailureNotTooLarge() {
		try {
			JpegFit.fit((quality, scale) -> null, 78, 1);
			org.junit.Assert.fail();
		} catch (java.io.IOException expected) {
		}
	}

	private void sendBody(byte[] body) throws Exception {
		Method m = GroupTrConversationActivity.class.getDeclaredMethod(
				"sendBodyAsync", byte[].class);
		m.setAccessible(true);
		m.invoke(activity, (Object) body);
	}

	private EditText input() {
		return activity.findViewById(R.id.messageInput);
	}

	private void send() {
		activity.findViewById(R.id.sendButton).performClick();
		idle();
	}

	private static String ascii(int length) {
		char[] c = new char[length];
		Arrays.fill(c, 'a');
		return new String(c);
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static void runIo() {
		idle();
		while (!ProbeConversation.io.isEmpty()) {
			Runnable r = ProbeConversation.io.remove(0);
			r.run();
			idle();
		}
		idle();
	}

	private static byte[] key(byte b) {
		byte[] k = new byte[32];
		Arrays.fill(k, b);
		return k;
	}
}

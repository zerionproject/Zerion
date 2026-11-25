package com.professor.zerion.android.conversation;

import android.content.Context;
import android.content.Intent;
import android.media.MediaDataSource;
import android.media.MediaPlayer;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.AttachmentItemsForTests;
import com.professor.zerion.android.conversation.ViewOnceChatTest.ProbeChat;
import com.professor.zerion.android.conversation.voice.VoiceMemoKeys;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.view.ZerionRecyclerView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.RealObject;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.lifecycle.MutableLiveData;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29, shadows = ReceivedVoiceAttachmentTest.CountingPlayer.class)
public class ReceivedVoiceAttachmentTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Implements(MediaPlayer.class)
	public static class CountingPlayer {

		static int sources;
		static int prepared;
		static int started;
		static MediaPlayer last;
		static MediaPlayer.OnPreparedListener ready;

		@RealObject
		private MediaPlayer player;

		@Implementation
		protected void __constructor__() {
		}

		@Implementation
		protected void setDataSource(MediaDataSource source) {
			sources++;
			last = player;
		}

		@Implementation
		protected void prepareAsync() {
			prepared++;
		}

		@Implementation
		protected void setOnPreparedListener(
				MediaPlayer.OnPreparedListener listener) {
			ready = listener;
		}

		@Implementation
		protected void setOnCompletionListener(
				MediaPlayer.OnCompletionListener listener) {
		}

		@Implementation
		protected void setOnErrorListener(
				MediaPlayer.OnErrorListener listener) {
		}

		@Implementation
		protected void start() {
			started++;
		}

		@Implementation
		protected void pause() {
		}

		@Implementation
		protected void stop() {
		}

		@Implementation
		protected void release() {
		}

		@Implementation
		protected boolean isPlaying() {
			return false;
		}

		@Implementation
		protected int getDuration() {
			return 3000;
		}

		@Implementation
		protected int getCurrentPosition() {
			return 0;
		}
	}

	private final List<Runnable> db = new ArrayList<>();
	private AttachmentReader reader;

	@Before
	public void setUp() throws Exception {
		CountingPlayer.sources = 0;
		CountingPlayer.prepared = 0;
		CountingPlayer.started = 0;
		CountingPlayer.last = null;
		CountingPlayer.ready = null;
		reader = mock(AttachmentReader.class);
		when(reader.getAttachment(any(AttachmentHeader.class))).thenAnswer(
				inv -> new Attachment(inv.getArgument(0),
						new ByteArrayInputStream(new byte[2048])));
	}

	private static AttachmentItem voice() {
		return AttachmentItemsForTests.available(new AttachmentHeader(
				new GroupId(getRandomId()), new MessageId(getRandomId()),
				"audio/3gpp"), "");
	}

	private View voiceRow() {
		Context ctx = new ContextThemeWrapper(
				RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
		return LayoutInflater.from(ctx).inflate(R.layout.voice_message_view,
				new FrameLayout(ctx), false);
	}

	private void work() {
		for (int round = 0; round < 3; round++) {
			List<Runnable> pending = new ArrayList<>(db);
			db.clear();
			for (Runnable r : pending) r.run();
			shadowOf(Looper.getMainLooper()).idle();
		}
	}

	@Test
	public void aReceivedVoiceAttachmentIsNotReadUntilPlayIsTapped()
			throws Exception {
		View row = voiceRow();
		VoiceMessageViewHolder h = new VoiceMessageViewHolder(row, reader,
				db::add, mock(VoiceMemoKeys.class));
		h.bindReceived(voice());
		work();

		verify(reader, never()).getAttachment(any(AttachmentHeader.class));
		assertEquals("the audio was handed to the system player", 0,
				CountingPlayer.sources);
		View play = row.findViewById(R.id.playPauseButton);
		assertTrue("play cannot be tapped", play.isEnabled());
		assertEquals("", ((TextView) row.findViewById(R.id.voiceDuration))
				.getText().toString());

		play.performClick();
		work();
		verify(reader, times(1)).getAttachment(any(AttachmentHeader.class));
		assertEquals(1, CountingPlayer.sources);
		assertEquals(1, CountingPlayer.prepared);
		assertEquals(0, CountingPlayer.started);

		CountingPlayer.ready.onPrepared(CountingPlayer.last);
		assertEquals("the tap did not start the playback", 1,
				CountingPlayer.started);
		assertEquals("0:03", ((TextView) row.findViewById(
				R.id.voiceDuration)).getText().toString());
	}

	@Test
	public void anOwnVoiceAttachmentIsReadyAtOnceWhileAReceivedOneWaits()
			throws Exception {
		VoiceMessageViewHolder mine = new VoiceMessageViewHolder(voiceRow(),
				reader, db::add, mock(VoiceMemoKeys.class));
		mine.bind(voice());
		work();
		assertEquals("an own recording is no longer prepared", 1,
				CountingPlayer.sources);
		CountingPlayer.ready.onPrepared(CountingPlayer.last);
		assertEquals("an own recording started by itself", 0,
				CountingPlayer.started);

		VoiceMessageViewHolder theirs = new VoiceMessageViewHolder(
				voiceRow(), reader, db::add, mock(VoiceMemoKeys.class));
		theirs.bindReceived(voice());
		work();
		assertEquals("a received recording was parsed", 1,
				CountingPlayer.sources);
	}

	private static ConversationMessageItem voiceMessage(boolean local) {
		AttachmentItem voice = voice();
		PrivateMessageHeader h = new PrivateMessageHeader(
				new MessageId(getRandomId()), new GroupId(getRandomId()),
				local ? 2_000L : 1_000L, local, true, true, false, false,
				Collections.singletonList(voice.getHeader()), -1L);
		return new ConversationMessageItem(local
				? R.layout.list_item_conversation_msg_out
				: R.layout.list_item_conversation_msg_in, h,
				new MutableLiveData<>("Bob"),
				Collections.singletonList(voice));
	}

	private static Object field(Object o, String name) throws Exception {
		Field f = ConversationActivity.class.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	@Test
	public void theChatLeavesAReceivedVoiceAttachmentWaitingForItsTap()
			throws Exception {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeChat.class);
		i.putExtra(ConversationActivity.CONTACT_ID, 5);
		ProbeChat activity = Robolectric.buildActivity(ProbeChat.class, i)
				.setup().get();
		shadowOf(Looper.getMainLooper()).idle();
		ConversationAdapter adapter =
				(ConversationAdapter) field(activity, "adapter");
		adapter.add(voiceMessage(false));
		adapter.add(voiceMessage(true));
		shadowOf(Looper.getMainLooper()).idle();
		RecyclerView list = ((ZerionRecyclerView) field(activity, "list"))
				.getRecyclerView();
		list.measure(View.MeasureSpec.makeMeasureSpec(1080,
				View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(
				1920, View.MeasureSpec.EXACTLY));
		list.layout(0, 0, 1080, 1920);

		RecyclerView.ViewHolder theirs =
				list.findViewHolderForAdapterPosition(0);
		RecyclerView.ViewHolder mine =
				list.findViewHolderForAdapterPosition(1);
		assertNotNull(theirs);
		assertNotNull(mine);
		assertTrue("a received recording does not wait for its tap",
				theirs.itemView.findViewById(R.id.playPauseButton)
						.isEnabled());
		assertFalse("an own recording is not being prepared",
				mine.itemView.findViewById(R.id.playPauseButton)
						.isEnabled());
	}
}

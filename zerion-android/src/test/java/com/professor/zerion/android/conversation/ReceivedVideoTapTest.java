package com.professor.zerion.android.conversation;

import android.content.Intent;
import android.os.Looper;
import android.view.View;

import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.AttachmentItemsForTests;
import com.professor.zerion.android.conversation.ViewOnceChatTest.ProbeChat;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

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
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ReceivedVideoTapTest {

	static {
		TestAndroidKeyStore.register();
	}

	private ProbeChat activity;
	private ConversationAdapter adapter;

	@Before
	public void setUp() throws Exception {
		OpenedVideos.clear();
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				ProbeChat.class);
		i.putExtra(ConversationActivity.CONTACT_ID, 5);
		activity = Robolectric.buildActivity(ProbeChat.class, i).setup()
				.get();
		idle();
		Field f = ConversationActivity.class.getDeclaredField("adapter");
		f.setAccessible(true);
		adapter = (ConversationAdapter) f.get(activity);
	}

	@After
	public void tearDown() {
		OpenedVideos.clear();
		MediaViewer.clearAll();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private ConversationMessageItem videoMessage(boolean local,
			AttachmentItem video) {
		PrivateMessageHeader h = new PrivateMessageHeader(
				new MessageId(getRandomId()), new GroupId(getRandomId()),
				1_000L, local, true, true, false, false,
				Collections.singletonList(video.getHeader()), -1L);
		ConversationMessageItem item = new ConversationMessageItem(local
				? R.layout.list_item_conversation_msg_out
				: R.layout.list_item_conversation_msg_in, h,
				new MutableLiveData<>("Bob"),
				Collections.singletonList(video));
		adapter.add(item);
		idle();
		return item;
	}

	private static AttachmentItem video() {
		return AttachmentItemsForTests.available(new AttachmentHeader(
				new GroupId(getRandomId()), new MessageId(getRandomId()),
				"video/mp4"), "mp4");
	}

	private boolean playerStarted() {
		Intent i;
		while ((i = shadowOf(activity).getNextStartedActivity()) != null) {
			if (i.getComponent() != null && VideoPlayerActivity.class
					.getName().equals(i.getComponent().getClassName())) {
				return true;
			}
		}
		return false;
	}

	@Test
	public void theTapThatStartsThePlayerIsWhatOpensAReceivedVideo() {
		AttachmentItem video = video();
		ConversationMessageItem message = videoMessage(false, video);
		List<Integer> redrawn = new ArrayList<>();
		adapter.registerAdapterDataObserver(
				new RecyclerView.AdapterDataObserver() {
					@Override
					public void onItemRangeChanged(int start, int count) {
						redrawn.add(start);
					}
				});

		activity.onAttachmentClicked(new View(activity), message, video);
		idle();

		assertTrue("the player did not start", playerStarted());
		assertFalse("the tap was not remembered", ImageViewHolder
				.videoOpened(message.getId(), video.getMessageId()));
		assertEquals("the bubble was not drawn again",
				Collections.singletonList(
						adapter.getPositionOfMessage(message.getId())),
				redrawn);
		assertNotNull(adapter.getItemAt(redrawn.get(0)));

		activity.onAttachmentClicked(new View(activity), message, video);
		idle();
		assertEquals("a second tap drew the bubble again", 1,
				redrawn.size());
	}
}

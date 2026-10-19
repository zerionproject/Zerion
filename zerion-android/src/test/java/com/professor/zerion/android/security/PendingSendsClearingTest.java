package com.professor.zerion.android.security;

import com.professor.zerion.android.conversation.DeletedContentEvictor;
import com.professor.zerion.android.conversation.voice.VoiceChunkAssembler;
import com.professor.zerion.android.message.PendingSends;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.channel.event.ChannelStateChangedEvent;
import org.zerionproject.app.api.messaging.event.GroupTrLocalStateChangedEvent;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class PendingSendsClearingTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final byte[] group = new byte[] {1, 2};
	private final byte[] channel = new byte[] {3, 4};

	private static PendingSends store() {
		return PendingSends.get();
	}

	private void keepSomething() {
		PendingSends.Attempt never = () -> PendingSends.Result.failed(null);
		store().addNotSent(PendingSends.groupScope(group), "secret", null, 6,
				true, never);
		store().addNotSent(PendingSends.channelPostScope(channel), "post",
				null, 4, true, never);
		store().addNotSent(PendingSends.channelCommentScope(channel, 9L),
				"comment", null, 7, true, never);
	}

	private int kept() {
		return store().entries(PendingSends.groupScope(group)).size()
				+ store().entries(PendingSends.channelPostScope(channel))
				.size()
				+ store().entries(PendingSends.channelCommentScope(channel,
				9L)).size();
	}

	@Test
	public void aUsbPanicLeavesNothingToTellAfterwards() {
		keepSomething();
		store().clear();
		keepSomething();
		new UsbPanic.AppSteps(RuntimeEnvironment.getApplication())
				.clearMemory();
		assertEquals(0, kept());
		assertEquals("no lock count carries over", 0,
				store().takeRemovedOnLock());
		assertTrue(store().takeNotices(PendingSends.groupScope(group))
				.isEmpty());
	}

	@Test
	public void aUsbPanicRemovesUnsentRows() {
		keepSomething();
		assertEquals(3, kept());
		new UsbPanic.AppSteps(RuntimeEnvironment.getApplication())
				.clearMemory();
		assertEquals(0, kept());
	}

	@Test
	public void removingAGroupOrLeavingAChannelRemovesItsUnsentRows() {
		keepSomething();
		DeletedContentEvictor evictor = new DeletedContentEvictor(
				new VoiceChunkAssembler(), () -> {
				});
		evictor.eventOccurred(new GroupTrLocalStateChangedEvent(group,
				GroupTrLocalStateChangedEvent.Kind.REMOVED));
		assertEquals(0, store().entries(PendingSends.groupScope(group))
				.size());
		assertEquals(2, kept());
		evictor.eventOccurred(new ChannelStateChangedEvent(channel,
				ChannelStateChangedEvent.Kind.LEFT));
		assertEquals(0, kept());
	}
}

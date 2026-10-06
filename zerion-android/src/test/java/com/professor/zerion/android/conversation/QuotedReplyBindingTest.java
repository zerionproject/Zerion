package com.professor.zerion.android.conversation;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Method;
import java.util.Collections;

import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class QuotedReplyBindingTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Test
	public void aContactQuotingItsOwnMessageIsNotShownAsYou()
			throws Exception {
		Context ctx = new ContextThemeWrapper(
				ApplicationProvider.getApplicationContext(),
				R.style.ZerionTheme);
		View v = LayoutInflater.from(ctx).inflate(
				R.layout.list_item_conversation_msg_in, null, false);
		ConversationItemViewHolder holder = new ConversationItemViewHolder(v,
				mock(ConversationListener.class), true) {
		};
		MessageId quoted = new MessageId(getRandomId());
		PrivateMessageHeader h = new PrivateMessageHeader(
				new MessageId(getRandomId()), new GroupId(getRandomId()),
				1_000L, false, true, false, false, true,
				Collections.emptyList(), -1L, quoted);
		ConversationMessageItem item = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_in, h,
				new MutableLiveData<>("Bob"));
		item.setReplyToMessageId(quoted);
		item.setReplyToText("I never said that");
		markQuoteAsTheContacts(item);

		holder.bindReplyContext(item);

		TextView reply = v.findViewById(R.id.replyText);
		assertEquals("Bob: I never said that", reply.getText().toString());
	}

	private static void markQuoteAsTheContacts(ConversationItem item)
			throws Exception {
		try {
			Method m = ConversationItem.class.getMethod("setReplyToLocal",
					Boolean.class);
			m.invoke(item, Boolean.FALSE);
		} catch (NoSuchMethodException unknown) {
		}
	}
}

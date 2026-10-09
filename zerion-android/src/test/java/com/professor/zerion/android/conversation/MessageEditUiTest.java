package com.professor.zerion.android.conversation;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.view.TextInputView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.util.Collections;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class MessageEditUiTest {

	static {
		TestAndroidKeyStore.register();
	}

	private final Context ctx = new ContextThemeWrapper(
			ApplicationProvider.getApplicationContext(), R.style.ZerionTheme);

	private ConversationMessageItem item(boolean edited) {
		PrivateMessageHeader h = new PrivateMessageHeader(
				new MessageId(getRandomId()), new GroupId(getRandomId()),
				System.currentTimeMillis(), true, true, true, false, true,
				Collections.emptyList(), -1L, null, false, edited);
		ConversationMessageItem item = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_out, h,
				new MutableLiveData<>("Bob"));
		item.setText("Hey all good");
		return item;
	}

	private static TextInputView inputView() {
		AppCompatActivity a = Robolectric.buildActivity(
				AppCompatActivity.class).setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		return new TextInputView(a);
	}

	private String shownTime(ConversationMessageItem item) {
		View v = LayoutInflater.from(ctx).inflate(
				R.layout.list_item_conversation_msg_out, null, false);
		ConversationItemViewHolder holder = new ConversationItemViewHolder(v,
				mock(ConversationListener.class), false) {
		};
		holder.bind(item, false);
		return ((TextView) v.findViewById(R.id.time)).getText().toString();
	}

	@Test
	public void anEditedMessageSaysSoNextToItsTime() {
		String edited = ctx.getString(R.string.message_edited);
		assertTrue(shownTime(item(true)).endsWith(edited));
		assertFalse(shownTime(item(false)).contains(edited));

		ConversationMessageItem later = item(false);
		later.markEdited();
		assertTrue(shownTime(later).endsWith(edited));
	}

	@Test
	public void refreshingTheTimeKeepsTheEditedLabel() {
		View v = LayoutInflater.from(ctx).inflate(
				R.layout.list_item_conversation_msg_out, null, false);
		ConversationItemViewHolder holder = new ConversationItemViewHolder(v,
				mock(ConversationListener.class), false) {
		};
		ConversationMessageItem item = item(true);
		holder.bind(item, false);

		holder.bindTimeOnly(item);

		assertTrue(((TextView) v.findViewById(R.id.time)).getText()
				.toString().endsWith(ctx.getString(R.string.message_edited)));
	}

	@Test
	public void editingFillsTheInputAndCancellingEmptiesIt() {
		TextInputView input = inputView();
		ConversationMessageItem item = item(false);

		input.showEditPreview(item, "Hey Allfs good");

		assertSame(item, input.getEditingItem());
		assertNull(input.getReplyingToItem());
		EditText field = input.findViewById(R.id.input_text);
		assertEquals("Hey Allfs good", field.getText().toString());
		assertEquals(ctx.getString(R.string.editing_message),
				((TextView) input.findViewById(R.id.reply_author)).getText()
						.toString());

		((ImageButton) input.findViewById(R.id.cancel_reply)).performClick();

		assertNull(input.getEditingItem());
		assertEquals("", field.getText().toString());
	}

	@Test
	public void replyingInsteadEndsTheEdit() {
		TextInputView input = inputView();
		ConversationMessageItem item = item(false);
		input.showEditPreview(item, "text");

		input.showReplyPreview(item);

		assertNull(input.getEditingItem());
		assertSame(item, input.getReplyingToItem());
	}
}

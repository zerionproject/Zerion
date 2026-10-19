package com.professor.zerion.android.conversation;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.view.TextInputView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.messaging.PrivateMessageHeader;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.util.Collections;

import androidx.appcompat.app.AppCompatActivity;
import androidx.lifecycle.MutableLiveData;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class SecretNoteQuoteTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final String SECRET = "SECRET:10:the hidden words";

	private final Context ctx = new ContextThemeWrapper(
			ApplicationProvider.getApplicationContext(), R.style.ZerionTheme);
	private final MessageId quoted = new MessageId(getRandomId());

	private PrivateMessageHeader reply() {
		return new PrivateMessageHeader(new MessageId(getRandomId()),
				new GroupId(getRandomId()), 2_000L, false, true, false, false,
				false, Collections.emptyList(), -1L, quoted);
	}

	private ConversationVisitor visitor(ConversationViewModel vm,
			ConversationVisitor.TextCache texts) {
		return new ConversationVisitor(ctx, texts, h -> Collections.emptyList(),
				new MutableLiveData<>("Bob"), vm);
	}

	@Test
	public void aQuotedSecretNoteShowsOnlyThatItIsASecretNote() {
		ConversationViewModel vm = mock(ConversationViewModel.class);
		when(vm.quotedDirection(any())).thenReturn(Boolean.FALSE);
		ConversationItem item = reply().accept(visitor(vm,
				m -> m.equals(quoted) ? SECRET : null));

		assertEquals(ctx.getString(R.string.secret_note_label),
				item.getReplyToText());
	}

	@Test
	public void aStoredQuoteOfASecretNoteShowsOnlyThatItIsASecretNote() {
		ConversationViewModel vm = mock(ConversationViewModel.class);
		when(vm.getReplyContext(any())).thenReturn(new Pair<>(quoted, SECRET));
		ConversationItem item = reply().accept(visitor(vm, m -> null));

		assertEquals(ctx.getString(R.string.secret_note_label),
				item.getReplyToText());
	}

	@Test
	public void anOrdinaryQuoteStaysAsWritten() {
		ConversationViewModel vm = mock(ConversationViewModel.class);
		when(vm.quotedDirection(any())).thenReturn(Boolean.FALSE);
		ConversationItem item = reply().accept(visitor(vm,
				m -> m.equals(quoted) ? "see you at ten" : null));

		assertEquals("see you at ten", item.getReplyToText());
	}

	@Test
	public void theReplyPreviewNeverShowsSecretText() {
		AppCompatActivity a = Robolectric.buildActivity(
				AppCompatActivity.class).setup().get();
		a.setTheme(R.style.ZerionTheme_NoActionBar);
		TextInputView input = new TextInputView(a);
		ConversationMessageItem note = new ConversationMessageItem(
				R.layout.list_item_conversation_msg_in, reply(),
				new MutableLiveData<>("Bob"));
		note.setText(SECRET);

		input.showReplyPreview(note);

		TextView shown = input.findViewById(R.id.reply_text);
		assertEquals(ctx.getString(R.string.secret_note_label),
				shown.getText().toString());
		assertTrue(SecretNotes.isSecret(SECRET));
	}
}

package com.professor.zerion.android.conversation;

import android.content.Context;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;

import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.CountingSecurePrefs;
import com.professor.zerion.android.conversation.voice.VoiceMemoKeys;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.settings.ChatPreferences;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.app.api.conversation.ConversationMessageHeader;
import org.zerionproject.app.api.conversation.ConversationMessageVisitor;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.util.ArrayList;
import java.util.List;

import androidx.lifecycle.MutableLiveData;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ConversationMessageStyleTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int ROWS = 20;

	@After
	public void tearDown() {
		AppModule.getAndroidComponent(RuntimeEnvironment.getApplication())
				.securePreferences().edit()
				.remove(ChatPreferences.PREF_TEXT_SIZE)
				.remove(ChatPreferences.PREF_BUBBLE_COLOR).commit();
	}

	private static Context ctx() {
		return new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
	}

	private static ConversationMessageHeader header(int i) {
		byte[] id = new byte[32];
		id[0] = (byte) i;
		return new ConversationMessageHeader(new MessageId(id),
				new GroupId(new byte[32]), 1000L + i, true, true, true, true,
				-1L) {
			@Override
			public <T> T accept(ConversationMessageVisitor<T> v) {
				throw new UnsupportedOperationException();
			}
		};
	}

	private static ConversationAdapter adapter() {
		ConversationAdapter adapter = new ConversationAdapter(ctx(),
				mock(ConversationListener.class), mock(AttachmentReader.class),
				Runnable::run, mock(VoiceMemoKeys.class));
		List<ConversationItem> items = new ArrayList<>();
		for (int i = 0; i < ROWS; i++) {
			items.add(new ConversationNoticeItem(
					R.layout.list_item_conversation_notice_out, "notice " + i,
					new MutableLiveData<>("Bob"), header(i)));
		}
		adapter.setItems(items);
		return adapter;
	}

	private static List<ConversationItemViewHolder> bindAll(
			ConversationAdapter adapter) {
		FrameLayout parent = new FrameLayout(ctx());
		List<ConversationItemViewHolder> out = new ArrayList<>();
		for (int i = 0; i < adapter.getItemCount(); i++) {
			ConversationItemViewHolder h = adapter.onCreateViewHolder(parent,
					adapter.getItemViewType(i));
			adapter.onBindViewHolder(h, i);
			out.add(h);
		}
		return out;
	}

	@Test
	public void rowsUseTheChosenTextSize() {
		AppModule.getAndroidComponent(RuntimeEnvironment.getApplication())
				.securePreferences().edit()
				.putInt(ChatPreferences.PREF_TEXT_SIZE,
						ChatPreferences.TEXT_SIZE_EXTRA_LARGE).commit();
		Context ctx = ctx();
		float size = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
				ChatPreferences.getMessageTextSizeSp(ctx),
				ctx.getResources().getDisplayMetrics());
		for (ConversationItemViewHolder h : bindAll(adapter())) {
			assertEquals(size, h.text.getTextSize(), 0.01f);
		}
	}

	@Test
	public void theOutgoingVoicePlayIconTakesTheChosenBubbleColor() {
		AppModule.getAndroidComponent(RuntimeEnvironment.getApplication())
				.securePreferences().edit()
				.putInt(ChatPreferences.PREF_BUBBLE_COLOR,
						ChatPreferences.BUBBLE_PINK).commit();
		Context ctx = ctx();
		android.view.View v = android.view.LayoutInflater.from(ctx).inflate(
				R.layout.list_item_conversation_msg_out, new FrameLayout(ctx),
				false);
		ConversationMessageViewHolder h = new ConversationMessageViewHolder(v,
				mock(ConversationListener.class),
				false, new androidx.recyclerview.widget.RecyclerView
				.RecycledViewPool(), new ImageItemDecoration(ctx),
				mock(AttachmentReader.class), Runnable::run,
				mock(VoiceMemoKeys.class));
		h.setMessageStyle(com.professor.zerion.android.settings.MessageStyle
				.read(ctx));
		h.applyDefaultTextStyle();
		com.google.android.material.button.MaterialButton play =
				v.findViewById(R.id.playPauseButton);
		assertEquals(ctx.getColor(R.color.bubble_pink),
				play.getIconTint().getDefaultColor());
	}

	@Test
	public void bindingManyRowsReadsTheSettingsOnce() throws Exception {
		ConversationAdapter adapter = adapter();
		try (CountingSecurePrefs counter = CountingSecurePrefs.install(
				RuntimeEnvironment.getApplication())) {
			bindAll(adapter);
			assertTrue("binding " + ROWS + " rows read the settings "
					+ counter.reads() + " times", counter.reads() <= 2);
		}
	}
}

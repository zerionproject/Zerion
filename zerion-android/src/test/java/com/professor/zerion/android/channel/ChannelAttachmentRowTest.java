package com.professor.zerion.android.channel;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.channel.ChannelPost;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelAttachmentRowTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static Context ctx() {
		return new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
	}

	private static Method method(Object target, String name) {
		for (Method m : target.getClass().getDeclaredMethods()) {
			if (m.getName().equals(name)) {
				m.setAccessible(true);
				return m;
			}
		}
		throw new AssertionError(name);
	}

	private static ChannelPost.ChannelAttachment attachment(String mime,
			long size) {
		return new ChannelPost.ChannelAttachment(new byte[32], size, mime,
				new byte[32], null);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static List<View> rows(ChannelPost.ChannelAttachment... atts)
			throws Exception {
		Constructor<?> c = Class.forName(ChannelFeedActivity.class.getName()
				+ "$PostAdapter").getDeclaredConstructors()[0];
		c.setAccessible(true);
		Object adapter = c.newInstance(new Object[c.getParameterCount()]);
		ChannelPost post = new ChannelPost(new byte[32], 1L, new byte[32],
				3_600_000L, "post", Arrays.asList(atts), 0L, new byte[64],
				true);
		method(adapter, "setPosts").invoke(adapter,
				Collections.singletonList(post), Collections.emptyMap(),
				Collections.emptyMap(), Collections.emptyMap(), true,
				Collections.emptySet());
		RecyclerView.Adapter a = (RecyclerView.Adapter) adapter;
		FrameLayout parent = new FrameLayout(ctx());
		RecyclerView.ViewHolder h = a.onCreateViewHolder(parent,
				a.getItemViewType(0));
		a.onBindViewHolder(h, 0);
		ViewGroup box = h.itemView.findViewById(R.id.channelPostAttachments);
		assertNotNull(box);
		List<View> out = new ArrayList<>();
		for (int i = 0; i < box.getChildCount(); i++) {
			out.add(box.getChildAt(i));
		}
		return out;
	}

	@Test
	public void aRowNamesTheKindInPlainWordsAndNeverTheRawType()
			throws Exception {
		List<View> rows = rows(attachment("application/pdf", 1_200_000L),
				attachment("video/mp4", 5_000_000L),
				attachment("application/zip", 2048L));
		assertEquals(3, rows.size());
		String[] kinds = {"PDF document", "Video", "Archive"};
		for (int i = 0; i < rows.size(); i++) {
			TextView kind = rows.get(i).findViewById(
					R.id.channelAttachmentMime);
			String shown = kind.getText().toString();
			assertEquals(kinds[i], shown);
			assertFalse("a raw type is shown: " + shown, shown.contains("/"));
		}
	}

	@Test
	public void aRowReadsAsOneItemWithKindAndSize() throws Exception {
		View row = rows(attachment("application/pdf", 1_200_000L)).get(0);
		CharSequence said = row.getContentDescription();
		assertNotNull("the row says nothing to a screen reader", said);
		assertTrue(said.toString(), said.toString().startsWith(
				"PDF document, "));
		assertTrue(said.toString(), said.toString().contains("MB"));
		assertFalse(said.toString(), said.toString().contains("Attach"));
		float density = row.getResources().getDisplayMetrics().density;
		assertTrue("the row is smaller than a finger",
				row.getMinimumHeight() >= Math.round(48 * density));
		assertTrue(row.isClickable());
	}
}

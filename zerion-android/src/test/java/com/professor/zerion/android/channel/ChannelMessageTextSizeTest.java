package com.professor.zerion.android.channel;

import android.content.Context;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.CountingSecurePrefs;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.settings.ChatPreferences;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelPost;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class ChannelMessageTextSizeTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static final int ROWS = 20;

	@After
	public void tearDown() {
		AppModule.getAndroidComponent(RuntimeEnvironment.getApplication())
				.securePreferences().edit()
				.remove(ChatPreferences.PREF_TEXT_SIZE).commit();
	}

	private static Context ctx() {
		return new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
	}

	private static Object create(String nested) throws Exception {
		Constructor<?> c = Class.forName(nested).getDeclaredConstructors()[0];
		c.setAccessible(true);
		return c.newInstance(new Object[c.getParameterCount()]);
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

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static List<TextView> bindAll(RecyclerView.Adapter adapter,
			int bodyId) {
		FrameLayout parent = new FrameLayout(ctx());
		List<TextView> bodies = new ArrayList<>();
		for (int i = 0; i < adapter.getItemCount(); i++) {
			RecyclerView.ViewHolder h = adapter.onCreateViewHolder(parent,
					adapter.getItemViewType(i));
			adapter.onBindViewHolder(h, i);
			bodies.add(h.itemView.findViewById(bodyId));
		}
		return bodies;
	}

	private static RecyclerView.Adapter<?> posts() throws Exception {
		Object adapter = create(ChannelFeedActivity.class.getName()
				+ "$PostAdapter");
		List<ChannelPost> posts = new ArrayList<>();
		for (int i = 0; i < ROWS; i++) {
			posts.add(new ChannelPost(new byte[32], i, new byte[32],
					3_600_000L * i, "post " + i, Collections.emptyList(), 0L,
					new byte[64], true));
		}
		method(adapter, "setPosts").invoke(adapter, posts,
				Collections.emptyMap(), Collections.emptyMap(),
				Collections.emptyMap(), true, Collections.emptySet());
		return (RecyclerView.Adapter<?>) adapter;
	}

	private static RecyclerView.Adapter<?> comments() throws Exception {
		Object adapter = create(ChannelCommentsActivity.class.getName()
				+ "$CommentsAdapter");
		Constructor<?> row = Class.forName(ChannelCommentsActivity.class
				.getName() + "$Row").getDeclaredConstructors()[0];
		row.setAccessible(true);
		List<Object> rows = new ArrayList<>();
		for (int i = 0; i < ROWS; i++) {
			rows.add(row.newInstance(new ChannelComment(1L, i, "comment " + i,
					"Alice", new byte[32], new byte[0], 3_600_000L), null));
		}
		method(adapter, "setItems").invoke(adapter, rows);
		return (RecyclerView.Adapter<?>) adapter;
	}

	@Test
	public void channelPostsAndCommentsFollowTheMessageTextSize()
			throws Exception {
		Context app = RuntimeEnvironment.getApplication();
		for (int size : new int[] {ChatPreferences.TEXT_SIZE_SMALL,
				ChatPreferences.TEXT_SIZE_EXTRA_LARGE}) {
			AppModule.getAndroidComponent(app).securePreferences().edit()
					.putInt(ChatPreferences.PREF_TEXT_SIZE, size).commit();
			float expected = TypedValue.applyDimension(
					TypedValue.COMPLEX_UNIT_SP,
					ChatPreferences.getMessageTextSizeSp(app),
					app.getResources().getDisplayMetrics());
			for (TextView body : bindAll(posts(), R.id.channelPostBodyView)) {
				assertEquals("post at size " + size, expected,
						body.getTextSize(), 0.01f);
			}
			for (TextView body : bindAll(comments(), R.id.commentBody)) {
				assertEquals("comment at size " + size, expected,
						body.getTextSize(), 0.01f);
			}
		}
	}

	@Test
	public void bindingManyRowsReadsTheSettingsOnce() throws Exception {
		Context app = RuntimeEnvironment.getApplication();
		RecyclerView.Adapter<?> posts = posts();
		RecyclerView.Adapter<?> comments = comments();
		try (CountingSecurePrefs counter = CountingSecurePrefs.install(app)) {
			bindAll(posts, R.id.channelPostBodyView);
			assertTrue("binding " + ROWS + " posts read the settings "
					+ counter.reads() + " times", counter.reads() <= 2);
		}
		try (CountingSecurePrefs counter = CountingSecurePrefs.install(app)) {
			bindAll(comments, R.id.commentBody);
			assertTrue("binding " + ROWS + " comments read the settings "
					+ counter.reads() + " times", counter.reads() <= 2);
		}
	}
}

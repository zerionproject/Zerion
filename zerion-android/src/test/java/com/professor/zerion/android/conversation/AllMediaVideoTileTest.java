package com.professor.zerion.android.conversation;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AllMediaVideoTileTest {

	static {
		TestAndroidKeyStore.register();
	}

	@After
	public void tearDown() {
		Glide.tearDown();
	}

	private static Context ctx() {
		return new ContextThemeWrapper(RuntimeEnvironment.getApplication(),
				R.style.ZerionTheme_NoActionBar);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static ImageView tile(String type) throws Exception {
		AllMediaActivity screen = new AllMediaActivity();
		List<Runnable> work = new ArrayList<>();
		screen.dbExecutor = work::add;
		Constructor<?> made = Class.forName(AllMediaActivity.class.getName()
				+ "$MediaItem").getDeclaredConstructors()[0];
		made.setAccessible(true);
		AttachmentHeader h = new AttachmentHeader(new GroupId(getRandomId()),
				new MessageId(getRandomId()), type);
		List<Object> items = new ArrayList<>();
		items.add(made.newInstance(h, type.startsWith("image/"),
				type.startsWith("video/"), false, 1000L,
				new MessageId(getRandomId())));
		Constructor<?> c = Class.forName(AllMediaActivity.class.getName()
				+ "$MediaAdapter").getDeclaredConstructors()[0];
		c.setAccessible(true);
		RecyclerView.Adapter adapter =
				(RecyclerView.Adapter) c.newInstance(screen);
		Method set = adapter.getClass().getDeclaredMethod("setItems",
				List.class);
		set.setAccessible(true);
		set.invoke(adapter, items);
		RecyclerView.ViewHolder holder = adapter.onCreateViewHolder(
				new FrameLayout(ctx()), 0);
		adapter.onBindViewHolder(holder, 0);
		return holder.itemView.findViewById(R.id.thumbnail);
	}

	private static Object pictureRequest(ImageView tile) {
		return tile.getTag(
				com.bumptech.glide.R.id.glide_custom_view_target_tag);
	}

	@Test
	public void aVideoTileShowsTheVideoSignAndAsksForNoPicture()
			throws Exception {
		ImageView video = tile("video/mp4");
		assertNull("the video was sent through the picture loader",
				pictureRequest(video));
		assertNotNull(video.getDrawable());
		assertEquals(R.drawable.ic_video,
				shadowOf(video.getDrawable()).getCreatedFromResId());

		assertNotNull("a picture is no longer loaded",
				pictureRequest(tile("image/jpeg")));
	}
}

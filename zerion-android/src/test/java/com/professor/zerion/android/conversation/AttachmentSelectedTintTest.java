package com.professor.zerion.android.conversation;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.PorterDuff;
import android.os.Looper;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestListener;
import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.AttachmentItemsForTests;
import com.professor.zerion.android.conversation.glide.Radii;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.selection.SelectedLook;
import com.professor.zerion.android.selection.SelectionBarTest;
import com.professor.zerion.android.util.UiUtils;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentReader;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.MessageId;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import androidx.core.widget.ImageViewCompat;
import androidx.recyclerview.widget.StaggeredGridLayoutManager;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.zerionproject.core.test.TestUtils.getRandomId;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class AttachmentSelectedTintTest {

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

	private static final class Bubble extends FrameLayout {

		Bubble(Context ctx) {
			super(ctx);
		}

		@Override
		protected void onMeasure(int width, int height) {
			setMeasuredDimension(100, 100);
		}

		@Override
		protected void onLayout(boolean changed, int l, int t, int r, int b) {
		}
	}

	private static ImageViewHolder holder(@Nullable AttachmentReader reader,
			@Nullable Executor db) {
		SelectionBarTest.Probe ctx = Robolectric.buildActivity(
				SelectionBarTest.Probe.class).setup().get();
		FrameLayout bubble = new Bubble(ctx);
		ctx.setContentView(bubble);
		View v = LayoutInflater.from(ctx).inflate(R.layout.list_item_image,
				bubble, false);
		bubble.addView(v);
		v.setLayoutParams(new StaggeredGridLayoutManager.LayoutParams(100,
				100));
		return new ImageViewHolder(v, 100, new MessageId(getRandomId()),
				reader, db);
	}

	private static ImageView shown(AttachmentItem item, boolean incoming) {
		ImageViewHolder h = holder(null, null);
		h.inBubble(incoming);
		h.bind(item, new Radii(0, 0, 0, 0), true, false);
		return h.itemView.findViewById(R.id.imageView);
	}

	private static AttachmentItem video() {
		return AttachmentItemsForTests.available(new AttachmentHeader(
				new GroupId(getRandomId()), new MessageId(getRandomId()),
				"video/mp4"), "mp4");
	}

	private static int color(View v, int attr) {
		return UiUtils.resolveColorAttribute(v.getContext(), attr);
	}

	@SuppressWarnings({"rawtypes", "unchecked"})
	private static void failLoads(ImageView image) throws Exception {
		Object request = image.getTag(
				com.bumptech.glide.R.id.glide_custom_view_target_tag);
		assertNotNull("a picture is being loaded", request);
		Field f = request.getClass().getDeclaredField("requestListeners");
		f.setAccessible(true);
		List<?> listeners = (List<?>) f.get(request);
		if (listeners == null) return;
		for (Object l : listeners) {
			assertFalse("Glide still shows its error picture",
					((RequestListener) l).onLoadFailed(null, null, null, false));
		}
	}

	@Test
	public void aPhotoThatFailsToLoadShowsTheBrokenSignInTheWordsColour()
			throws Exception {
		for (boolean incoming : new boolean[] {true, false}) {
			ImageView image = shown(photo(), incoming);
			failLoads(image);
			assertEquals("incoming " + incoming, PorterDuff.Mode.SRC_IN,
					ImageViewCompat.getImageTintMode(image));
			ColorStateList tint = ImageViewCompat.getImageTintList(image);
			assertNotNull(tint);
			int plain = color(image, incoming
					? R.attr.zerionColorOnBubbleInMuted
					: R.attr.zerionColorOnBubbleOut);
			int picked = color(image, incoming
					? R.attr.zerionColorSelectionForeground
					: R.attr.zerionColorOnBubbleOut);
			assertEquals(plain, tint.getColorForState(
					SelectedLook.state(image, false), 0));
			assertEquals(picked, tint.getColorForState(
					SelectedLook.state(image, true), 0));
		}
	}

	private static int shownResource(ImageView image) {
		return image.getDrawable() == null ? 0
				: shadowOf(image.getDrawable()).getCreatedFromResId();
	}

	@Test
	public void aVideoThatCannotBeReadShowsTheVideoSign() throws Exception {
		List<Runnable> db = new ArrayList<>();
		AttachmentReader reader = mock(AttachmentReader.class);
		when(reader.getAttachment(any(AttachmentHeader.class)))
				.thenThrow(new IllegalStateException());
		ImageViewHolder h = holder(reader, db::add);
		h.inBubble(true);
		h.bind(video(), new Radii(0, 0, 0, 0), true, false);
		ImageView image = h.itemView.findViewById(R.id.imageView);

		for (Runnable r : new ArrayList<>(db)) r.run();
		shadowOf(Looper.getMainLooper()).idle();

		assertEquals(R.drawable.ic_video, shownResource(image));
		assertEquals(PorterDuff.Mode.SRC_IN,
				ImageViewCompat.getImageTintMode(image));
	}

	@Test
	public void aLateVideoFallbackLeavesAReboundPhotoAlone() throws Exception {
		List<Runnable> db = new ArrayList<>();
		AttachmentReader reader = mock(AttachmentReader.class);
		when(reader.getAttachment(any(AttachmentHeader.class)))
				.thenThrow(new IllegalStateException());
		ImageViewHolder h = holder(reader, db::add);
		h.inBubble(true);
		h.bind(video(), new Radii(0, 0, 0, 0), true, false);
		h.bind(photo(), new Radii(0, 0, 0, 0), true, false);
		ImageView image = h.itemView.findViewById(R.id.imageView);

		for (Runnable r : new ArrayList<>(db)) r.run();
		shadowOf(Looper.getMainLooper()).idle();

		assertNotEquals("the old video does not paint over the photo",
				R.drawable.ic_video, shownResource(image));
		assertEquals("the photo keeps the picture tint",
				PorterDuff.Mode.SRC_ATOP,
				ImageViewCompat.getImageTintMode(image));
	}

	private static AttachmentItem photo() {
		return AttachmentItemsForTests.available(new AttachmentHeader(
				new GroupId(getRandomId()), new MessageId(getRandomId()),
				"image/jpeg"), "jpg");
	}

	@Test
	public void aPhotoDarkensALittleWhenItsMessageIsSelected() {
		for (boolean incoming : new boolean[] {true, false}) {
			ImageView image = shown(photo(), incoming);
			ColorStateList tint = ImageViewCompat.getImageTintList(image);
			assertNotNull(tint);
			assertEquals(PorterDuff.Mode.SRC_ATOP,
					ImageViewCompat.getImageTintMode(image));
			int dim = UiUtils.resolveColorAttribute(image.getContext(),
					R.attr.zerionColorMessageSelectedOwn);
			assertEquals("incoming " + incoming, dim, tint.getColorForState(
					SelectedLook.state(image, true), 0));
			assertEquals("an unselected photo is left alone", 0,
					tint.getColorForState(SelectedLook.state(image, false), 0)
							>>> 24);
			((View) image.getParent().getParent()).setActivated(true);
			assertEquals("the photo follows its bubble", dim,
					tint.getColorForState(image.getDrawableState(), 0));
		}
	}
}

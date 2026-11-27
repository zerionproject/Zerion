package com.professor.zerion.android.vault.ui.adapters;

import android.graphics.Bitmap;
import android.os.Looper;
import android.widget.FrameLayout;

import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.ui.VaultViewModel;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultGalleryAdapterLockTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Before
	public void emptyCache() {
		VaultGalleryAdapter.clearThumbnailCache();
	}

	private static Bitmap bitmap() {
		return Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888);
	}

	@Test
	public void aThumbnailDecodedWhileLockedIsNotCached() {
		VaultViewModel vm = mock(VaultViewModel.class);
		when(vm.isVaultUnlocked()).thenReturn(false);
		Bitmap b = bitmap();
		boolean kept = VaultGalleryAdapter.keepDecoded(vm, "a", b);
		assertEquals("dropped, recycled, cache 0",
				(kept ? "kept" : "dropped") + ", "
						+ (b.isRecycled() ? "recycled" : "live") + ", cache "
						+ VaultGalleryAdapter.cachedThumbnails());
	}

	@Test
	public void aLockDuringThePutRemovesTheThumbnailAgain() {
		VaultViewModel vm = mock(VaultViewModel.class);
		when(vm.isVaultUnlocked()).thenReturn(true, false);
		Bitmap b = bitmap();
		boolean kept = VaultGalleryAdapter.keepDecoded(vm, "a", b);
		assertEquals("dropped, not recycled, cache 0",
				(kept ? "kept" : "dropped") + ", "
						+ (b.isRecycled() ? "recycled" : "not recycled")
						+ ", cache " + VaultGalleryAdapter.cachedThumbnails());
	}

	@Test
	public void anUnlockedThumbnailIsCached() {
		VaultViewModel vm = mock(VaultViewModel.class);
		when(vm.isVaultUnlocked()).thenReturn(true);
		assertEquals("kept, cache 1",
				(VaultGalleryAdapter.keepDecoded(vm, "a", bitmap())
						? "kept" : "dropped") + ", cache "
						+ VaultGalleryAdapter.cachedThumbnails());
	}

	@Test
	public void theRecyclePathWipesTheDecryptedBytes() {
		VaultViewModel vm = mock(VaultViewModel.class);
		when(vm.isVaultUnlocked()).thenReturn(false);
		AtomicReference<VaultViewModel.ThumbnailCallback> callback =
				new AtomicReference<>();
		doAnswer(a -> {
			callback.set(a.getArgument(1));
			return null;
		}).when(vm).getThumbnail(anyString(), any());
		VaultItem item = VaultItem.createNew(VaultItem.ItemType.IMAGE,
				"photo.png", 10, new byte[48], new byte[12]);
		VaultGalleryAdapter adapter = new VaultGalleryAdapter(vm,
				new VaultGalleryAdapter.OnImageClickListener() {
					@Override
					public void onImageClick(VaultItem i) {
					}

					@Override
					public void onImageLongClick(VaultItem i) {
					}
				});
		adapter.setItems(Collections.singletonList(item));
		android.content.Context themed = new android.view.ContextThemeWrapper(
				RuntimeEnvironment.getApplication(),
				com.google.android.material.R.style
						.Theme_MaterialComponents_Light);
		RecyclerView rv = new RecyclerView(themed);
		rv.setLayoutManager(new GridLayoutManager(themed, 3));
		rv.setAdapter(adapter);
		FrameLayout parent = new FrameLayout(themed);
		parent.addView(rv, new FrameLayout.LayoutParams(900, 900));
		parent.measure(900, 900);
		rv.measure(
				android.view.View.MeasureSpec.makeMeasureSpec(900,
						android.view.View.MeasureSpec.EXACTLY),
				android.view.View.MeasureSpec.makeMeasureSpec(900,
						android.view.View.MeasureSpec.EXACTLY));
		rv.layout(0, 0, 900, 900);
		shadowOf(Looper.getMainLooper()).idle();
		ByteArrayOutputStream png = new ByteArrayOutputStream();
		bitmap().compress(Bitmap.CompressFormat.PNG, 100, png);
		byte[] content = png.toByteArray();
		AtomicBoolean bound = new AtomicBoolean(callback.get() != null);
		callback.get().onThumbnailRetrieved(content);
		boolean wiped = true;
		for (byte b : content) wiped &= b == 0;
		verify(vm, atLeastOnce()).getThumbnail(anyString(), any());
		verify(vm, atLeastOnce()).isVaultUnlocked();
		assertEquals("bound, wiped, cache 0",
				(bound.get() ? "bound" : "not bound") + ", "
						+ (wiped ? "wiped" : "kept") + ", cache "
						+ VaultGalleryAdapter.cachedThumbnails());
	}
}

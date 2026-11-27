package com.professor.zerion.android.vault.ui;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Looper;
import android.util.LruCache;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;
import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.model.VaultItem;
import com.professor.zerion.android.vault.ui.adapters.VaultGalleryAdapter;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import androidx.arch.core.executor.testing.InstantTaskExecutorRule;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultLockAftermathTest {

	static {
		TestAndroidKeyStore.register();
	}

	@Rule
	public final InstantTaskExecutorRule instant =
			new InstantTaskExecutorRule();

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	@Test
	public void itemsListedWhileTheVaultLocksAreNotKept() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		rig.vault.manager.addItem(VaultItem.ItemType.NOTE, "secret name",
				"x".getBytes());
		List<VaultItem> real = rig.vault.list();
		when(rig.vault.manager.listItems()).thenAnswer(a -> {
			rig.vault.unlocked = false;
			return real;
		});
		VaultViewModel vm = rig.newModel();
		vm.loadVaultItems();
		VaultViewModelRig.settle();
		assertEquals(0, vm.getVaultItems().getValue().size());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void theLockListenerClearsItemsAndThumbnails() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		rig.vault.manager.addItem(VaultItem.ItemType.NOTE, "secret name",
				"x".getBytes());
		VaultViewModel vm = rig.newModel();
		vm.loadVaultItems();
		VaultViewModelRig.settle();
		int before = vm.getVaultItems().getValue().size();
		Field f = VaultGalleryAdapter.class.getDeclaredField("thumbnailCache");
		f.setAccessible(true);
		LruCache<String, Bitmap> cache = (LruCache<String, Bitmap>) f.get(null);
		cache.put("thumb", Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888));
		rig.vault.unlocked = false;
		rig.vault.runLockListeners();
		VaultViewModelRig.settle();
		assertEquals("1 item before, 0 after, cache 0, LOCKED",
				before + " item before, " + vm.getVaultItems().getValue().size()
						+ " after, cache " + cache.size() + ", "
						+ vm.getVaultState().getValue());
	}

	@Test
	public void aThumbnailReadAcrossALockIsDropped() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		byte[] plain = "thumbnail bytes".getBytes();
		when(rig.vault.manager.getThumbnail(anyString())).thenAnswer(a -> {
			rig.vault.lockGeneration++;
			return plain;
		});
		VaultViewModel vm = rig.newModel();
		AtomicReference<String> outcome = new AtomicReference<>("none");
		vm.getThumbnail("item", new VaultViewModel.ThumbnailCallback() {
			@Override
			public void onThumbnailRetrieved(byte[] thumbnail) {
				outcome.set("delivered");
			}

			@Override
			public void onError(String error) {
				outcome.set("error: " + error);
			}
		});
		VaultViewModelRig.settle();
		idle();
		byte[] zero = new byte[plain.length];
		assertEquals("error: " + rig.app.getString(R.string.vault_locked)
						+ ", wiped",
				outcome.get() + ", "
						+ (Arrays.equals(zero, plain) ? "wiped" : "kept"));
	}

	@Test
	public void aLockDuringAPasswordChangeSaysUnlockFirst() throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		doThrow(new SecurityException(VaultManager.UNLOCK_FIRST))
				.when(rig.vault.manager).changePassword(any(), any());
		VaultViewModel vm = rig.newModel();
		vm.changePassword("old pass 1!Aa".toCharArray(),
				"new pass 2@Bb".toCharArray());
		VaultViewModelRig.settle();
		assertEquals(rig.app.getString(R.string.vault_msg_unlock_first),
				vm.getErrorMessage().getValue());
	}

	@Test
	public void aThumbnailReadBeforeALockIsDroppedBeforeTheDecode()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		byte[] plain = "thumbnail bytes".getBytes();
		when(rig.vault.manager.getThumbnail(anyString())).thenReturn(plain);
		VaultViewModel vm = rig.newModel();
		Field f = VaultViewModel.class.getDeclaredField("THUMB_DECODE_EXECUTOR");
		f.setAccessible(true);
		java.util.concurrent.Executor decode =
				(java.util.concurrent.Executor) f.get(null);
		java.util.concurrent.CountDownLatch busy =
				new java.util.concurrent.CountDownLatch(1);
		java.util.concurrent.CountDownLatch release =
				new java.util.concurrent.CountDownLatch(1);
		decode.execute(() -> {
			busy.countDown();
			try {
				release.await();
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		assertTrue(busy.await(10, java.util.concurrent.TimeUnit.SECONDS));
		AtomicReference<String> outcome = new AtomicReference<>("none");
		vm.getThumbnail("item", new VaultViewModel.ThumbnailCallback() {
			@Override
			public void onThumbnailRetrieved(byte[] thumbnail) {
				outcome.set("delivered");
			}

			@Override
			public void onError(String error) {
				outcome.set("error");
			}
		});
		rig.vault.lockGeneration++;
		release.countDown();
		java.util.concurrent.CountDownLatch drained =
				new java.util.concurrent.CountDownLatch(1);
		decode.execute(drained::countDown);
		assertTrue(drained.await(10, java.util.concurrent.TimeUnit.SECONDS));
		idle();
		assertEquals("none, wiped", outcome.get() + ", "
				+ (Arrays.equals(new byte[plain.length], plain)
				? "wiped" : "kept"));
	}

	@Test
	public void aSkippedLockClosesTheBitcoinWalletButKeepsTheVaultOpen()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel vm = rig.newModel();
		rig.unlockWalletSection(vm, "section-pass");
		String id = rig.importWallet(vm, "known",
				"abandon abandon abandon abandon abandon abandon abandon"
						+ " abandon abandon abandon abandon about");
		rig.open(vm, id);
		vm.loadVaultItems();
		VaultViewModelRig.settle();
		boolean openBefore = VaultViewModelRig.field(vm, "openBtc") != null;
		VaultViewModel.VaultState stateBefore = vm.getVaultState().getValue();
		rig.vault.runLockListeners();
		VaultViewModelRig.settle();
		assertEquals("open before, closed after, session invalid, state"
						+ " unchanged",
				(openBefore ? "open" : "closed") + " before, "
						+ (VaultViewModelRig.field(vm, "openBtc") == null
						? "closed" : "open") + " after, session "
						+ (vm.walletSessionValid() ? "valid" : "invalid")
						+ ", state "
						+ (stateBefore == vm.getVaultState().getValue()
						? "unchanged" : "changed"));
	}

	@Test
	public void aClearedViewModelNoLongerListensAndTheNextOneStillDoes()
			throws Exception {
		VaultViewModelRig rig = new VaultViewModelRig();
		VaultViewModel first = rig.newModel();
		VaultViewModel second = rig.newModel();
		int registered = rig.vault.lockListeners.size();
		Method cleared = androidx.lifecycle.ViewModel.class
				.getDeclaredMethod("onCleared");
		cleared.setAccessible(true);
		cleared.invoke(first);
		int afterClear = rig.vault.lockListeners.size();
		rig.vault.unlocked = false;
		rig.vault.runLockListeners();
		VaultViewModelRig.settle();
		assertEquals("2 registered, 1 after clearing, second LOCKED",
				registered + " registered, " + afterClear
						+ " after clearing, second "
						+ second.getVaultState().getValue());
	}

	private static VaultActivity galleryPicker() {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				VaultActivity.class);
		i.putExtra(VaultActivity.EXTRA_PICKER_MODE, true);
		i.putExtra(VaultActivity.EXTRA_PICKER_TYPE,
				VaultActivity.PICKER_TYPE_GALLERY);
		VaultActivity a = Robolectric.buildActivity(VaultActivity.class, i)
				.setup().get();
		idle();
		a.onVaultCreated();
		idle();
		return a;
	}

	private static VaultGalleryFragment gallery(VaultActivity a) {
		Fragment f = a.getSupportFragmentManager()
				.findFragmentById(R.id.vault_container);
		assertTrue(f instanceof VaultGalleryFragment);
		return (VaultGalleryFragment) f;
	}

	private static android.app.Dialog openViewer(VaultGalleryFragment g)
			throws Exception {
		Method open = VaultGalleryFragment.class.getDeclaredMethod(
				"openMediaViewer", VaultItem.class);
		open.setAccessible(true);
		open.invoke(g, VaultItem.createNew(VaultItem.ItemType.IMAGE,
				"photo.jpg", 10, new byte[48], new byte[12]));
		android.app.Dialog d = g.mediaDialog();
		assertTrue(d != null && d.isShowing());
		return d;
	}

	@Test
	@SuppressWarnings("unchecked")
	public void theImageViewerClosesWhenTheVaultLocks() throws Exception {
		VaultActivity a = galleryPicker();
		VaultGalleryFragment g = gallery(a);
		android.app.Dialog d = openViewer(g);
		VaultViewModel vm = new ViewModelProvider(a).get(VaultViewModel.class);
		Field f = VaultViewModel.class.getDeclaredField("vaultState");
		f.setAccessible(true);
		((MutableLiveData<VaultViewModel.VaultState>) f.get(vm)).setValue(
				VaultViewModel.VaultState.LOCKED);
		assertEquals("closed, field cleared",
				(d.isShowing() ? "showing" : "closed") + ", field "
						+ (g.mediaDialog() == null ? "cleared" : "kept"));
	}

	@Test
	public void theImageViewerClosesWithTheGalleryView() throws Exception {
		VaultActivity a = galleryPicker();
		VaultGalleryFragment g = gallery(a);
		android.app.Dialog d = openViewer(g);
		a.getSupportFragmentManager().beginTransaction().remove(g).commitNow();
		assertEquals("closed", d.isShowing() ? "showing" : "closed");
	}
}

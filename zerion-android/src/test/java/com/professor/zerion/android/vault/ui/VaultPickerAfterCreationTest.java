package com.professor.zerion.android.vault.ui;

import android.content.Intent;
import android.os.Looper;

import com.professor.zerion.R;
import com.professor.zerion.android.security.TestAndroidKeyStore;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;

import androidx.fragment.app.Fragment;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VaultPickerAfterCreationTest {

	static {
		TestAndroidKeyStore.register();
	}

	private static void idle() {
		shadowOf(Looper.getMainLooper()).idle();
	}

	private static VaultActivity picker(String type) {
		Intent i = new Intent(ApplicationProvider.getApplicationContext(),
				VaultActivity.class);
		i.putExtra(VaultActivity.EXTRA_PICKER_MODE, true);
		i.putExtra(VaultActivity.EXTRA_PICKER_TYPE, type);
		ActivityController<VaultActivity> c =
				Robolectric.buildActivity(VaultActivity.class, i).setup();
		idle();
		return c.get();
	}

	private static Fragment shown(VaultActivity a) {
		return a.getSupportFragmentManager()
				.findFragmentById(R.id.vault_container);
	}

	@SuppressWarnings("unchecked")
	private static void vaultBecomesUnlocked(VaultActivity a)
			throws Exception {
		VaultViewModel vm = new ViewModelProvider(a).get(
				VaultViewModel.class);
		Field f = VaultViewModel.class.getDeclaredField("vaultState");
		f.setAccessible(true);
		((MutableLiveData<VaultViewModel.VaultState>) f.get(vm)).setValue(
				VaultViewModel.VaultState.UNLOCKED);
		idle();
	}

	@Test
	public void aNewVaultOpensThePickerForDocuments() {
		VaultActivity a = picker(VaultActivity.PICKER_TYPE_DOCUMENTS);
		a.onVaultCreated();
		idle();
		Fragment f = shown(a);
		assertFalse("the dashboard was shown in picker mode",
				f instanceof VaultDashboardFragment);
		assertTrue(f instanceof VaultDocumentsFragment);
	}

	@Test
	public void aNewVaultOpensThePickerForImages() {
		VaultActivity a = picker(VaultActivity.PICKER_TYPE_GALLERY);
		a.onVaultCreated();
		idle();
		assertTrue(shown(a) instanceof VaultGalleryFragment);
	}

	@Test
	public void creatingTheVaultInThePickerEndsInThePicker()
			throws Exception {
		VaultActivity a = picker(VaultActivity.PICKER_TYPE_DOCUMENTS);
		vaultBecomesUnlocked(a);
		Fragment f = shown(a);
		assertFalse("the dashboard was shown in picker mode",
				f instanceof VaultDashboardFragment);
	}
}

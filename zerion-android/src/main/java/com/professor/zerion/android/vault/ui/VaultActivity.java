package com.professor.zerion.android.vault.ui;

import com.professor.zerion.android.vault.share.VaultShareRegistry;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.vault.model.VaultItem;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import java.io.File;
import java.util.ArrayList;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultActivity extends ZerionActivity implements BaseFragment.BaseFragmentListener {

	public static final String EXTRA_PICKER_MODE = "picker_mode";
	public static final String EXTRA_PICKER_TYPE = "picker_type";
	public static final String PICKER_TYPE_GALLERY = "gallery";
	public static final String PICKER_TYPE_DOCUMENTS = "documents";
	public static final String RESULT_SELECTED_URIS = "selected_uris";

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	@com.professor.zerion.android.AppModule.ProfilePrefs
	android.content.SharedPreferences profilePrefs;

	private VaultViewModel viewModel;
	private VaultViewModel.VaultState currentState = null;
	private boolean isPickerMode = false;
	private volatile boolean pickerResultDelivered = false;
	@Nullable
	private volatile Uri pickerSharedUri = null;
	private String pickerType = null;

	@Override
	protected boolean forceScreenshotProtection() {
		return profilePrefs == null
				|| profilePrefs.getBoolean("hide_content_enabled", true)
				|| VaultScreenProtection.required(
						getSupportFragmentManager(), profilePrefs);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);
		VaultScreenProtection.install(this, profilePrefs, () ->
				securityManager.applyScreenshotProtection(this,
						forceScreenshotProtection()));

		if (android.os.Build.VERSION.SDK_INT
				>= android.os.Build.VERSION_CODES.S) {
			getWindow().setHideOverlayWindows(true);
		}

		setContentView(R.layout.activity_vault);
		Intent intent = getIntent();
		isPickerMode = intent.getBooleanExtra(EXTRA_PICKER_MODE, false);
		pickerType = intent.getStringExtra(EXTRA_PICKER_TYPE);

		androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
			if (isPickerMode) {
				if (PICKER_TYPE_GALLERY.equals(pickerType)) {
					actionBar.setTitle(R.string.vault_select_image);
				} else {
					actionBar.setTitle(R.string.vault_select_document);
				}
			} else {
				actionBar.setTitle(R.string.vault_name);
			}
		}

		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(VaultViewModel.class);

		if (savedInstanceState == null) {
			checkAndShowFragment();
		} else {
		}

		viewModel.getVaultState().observe(this, state -> {

			if (state == currentState) {
				return;
			}

			if (currentState == null && savedInstanceState == null) {
				currentState = state;
				invalidateOptionsMenu();
				return;
			}

			currentState = state;

			invalidateOptionsMenu();

			switch (state) {
				case NOT_CREATED:
					showSetupFragment();
					break;
				case LOCKED:
					showUnlockFragment();
					break;
				case UNLOCKED:
					if (isPickerMode) {
						showPickerFragment();
					} else {
						showVaultDashboard();
					}
					break;
			}
		});

	}

	private boolean expectingChildResult = false;
	private static final long CHILD_RESULT_GRACE_MS = 60_000L;
	private final android.os.Handler lockHandler =
			new android.os.Handler(android.os.Looper.getMainLooper());
	private final Runnable childResultLockWatchdog = () -> {
		expectingChildResult = false;
		viewModel.lockIfUnlocked();
	};
	private final Runnable autolockRunnable =
			() -> viewModel.lockIfUnlocked();

	private void scheduleAutolock() {
		int timeoutSeconds = profilePrefs == null ? 60
				: profilePrefs.getInt("autolock_timeout", 60);
		if (timeoutSeconds < 0) return;
		if (timeoutSeconds == 0) {
			viewModel.lockIfUnlocked();
			return;
		}
		lockHandler.postDelayed(autolockRunnable, timeoutSeconds * 1000L);
	}

	public void setExpectingChildResult() {
		expectingChildResult = true;
	}

	@Override
	public void onResume() {
		super.onResume();
		lockHandler.removeCallbacks(childResultLockWatchdog);
		lockHandler.removeCallbacks(autolockRunnable);
		expectingChildResult = false;
		viewModel.refreshVaultState();
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (expectingChildResult) {
			lockHandler.removeCallbacks(childResultLockWatchdog);
			lockHandler.postDelayed(childResultLockWatchdog,
					CHILD_RESULT_GRACE_MS);
		} else if (isFinishing()) {
			viewModel.lockIfUnlocked();
		} else {
			scheduleAutolock();
		}
	}

	@Override
	protected void onDestroy() {
		lockHandler.removeCallbacks(childResultLockWatchdog);
		if (isPickerMode && !pickerResultDelivered) {
			Uri shared = pickerSharedUri;
			if (shared != null) VaultShareRegistry.release(shared);
			com.professor.zerion.android.util.CacheSweeper
					.sweepDirAsync(this, "vault_share");
		}
		super.onDestroy();
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		VaultViewModel.VaultState state = viewModel.getVaultState().getValue();
		if (state == VaultViewModel.VaultState.UNLOCKED) {
			Fragment currentFragment = getSupportFragmentManager().findFragmentById(R.id.vault_container);
			if (currentFragment instanceof VaultDashboardFragment) {
				getMenuInflater().inflate(R.menu.vault_menu, menu);
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		} else if (item.getItemId() == R.id.action_vault_settings) {
			VaultViewModel.VaultState state = viewModel.getVaultState().getValue();
			if (state == VaultViewModel.VaultState.UNLOCKED) {
				VaultSettingsFragment fragment = VaultSettingsFragment.newInstance();
				showFragment(fragment, "settings", true);
			} else {
				android.widget.Toast.makeText(this,
					R.string.vault_unlock_first_settings,
					android.widget.Toast.LENGTH_SHORT).show();
			}
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	private void checkAndShowFragment() {
		VaultViewModel.VaultState state = viewModel.getVaultState().getValue();

		currentState = state;

		if (state == null || state == VaultViewModel.VaultState.NOT_CREATED) {
			showSetupFragment();
		} else if (state == VaultViewModel.VaultState.LOCKED) {
			showUnlockFragment();
		} else {
			if (isPickerMode) {
				showPickerFragment();
			} else {
				showVaultDashboard();
			}
		}
	}

	private void showPickerFragment() {
		if (PICKER_TYPE_GALLERY.equals(pickerType)) {
			VaultGalleryFragment fragment = VaultGalleryFragment.newInstance();
			fragment.setPickerMode(true);
			showFragment(fragment, "gallery_picker");
		} else {
			VaultDocumentsFragment fragment = VaultDocumentsFragment.newInstance();
			fragment.setPickerMode(true);
			showFragment(fragment, "documents_picker");
		}
	}

	public boolean isPickerMode() {
		return isPickerMode;
	}

	public void onItemSelected(VaultItem item) {
		viewModel.getMediaContent(item.id, new VaultViewModel.MediaContentCallback() {
			@Override
			public void onContentRetrieved(byte[] content) {
				new Thread(() -> {
					try {
						String safeName = new File(item.name).getName();
						if (safeName.isEmpty() || safeName.equals(".")
								|| safeName.equals("..")) {
							safeName = "attachment";
						}
						Uri uri = VaultShareRegistry.register(
								VaultActivity.this, content, safeName);
						pickerSharedUri = uri;

						runOnUiThread(() -> {
							ArrayList<Uri> uris = new ArrayList<>();
							uris.add(uri);
							Intent resultIntent = new Intent();
							resultIntent.putParcelableArrayListExtra(RESULT_SELECTED_URIS, uris);
							pickerResultDelivered = true;
							setResult(RESULT_OK, resultIntent);
							finish();
						});

					} catch (Exception e) {
						runOnUiThread(() -> {
							Toast.makeText(VaultActivity.this,
									R.string.vault_export_error,
									Toast.LENGTH_SHORT).show();
						});
					}
				}).start();
			}

			@Override
			public void onError(String error) {
				Toast.makeText(VaultActivity.this,
						error,
						Toast.LENGTH_SHORT).show();
			}
		});
	}


	private void showSetupFragment() {
		VaultOnboardingFragment fragment = VaultOnboardingFragment.newInstance();
		showFragment(fragment, "onboarding");
	}

	private void showUnlockFragment() {
		showFragment(new VaultUnlockFragment(), "unlock");
	}

	private void showVaultDashboard() {
		showFragment(VaultDashboardFragment.newInstance(), "dashboard");
	}

	public void showNoteFragment(@Nullable String noteId) {
		SecureNoteFragment fragment = SecureNoteFragment.newInstance(noteId);
		showFragment(fragment, "note", true);
	}

	public void showFragment(Fragment fragment, String tag) {
		showFragment(fragment, tag, false);
	}

	public void showFragment(Fragment fragment, String tag, boolean addToBackStack) {

		FragmentTransaction transaction = getSupportFragmentManager()
				.beginTransaction()
				.replace(R.id.vault_container, fragment, tag);

		if (addToBackStack) {
			transaction.addToBackStack(tag);
		}

		if (getSupportFragmentManager().isStateSaved()) {
			transaction.commitAllowingStateLoss();
		} else {
			transaction.commit();
		}

		invalidateOptionsMenu();
	}

	public void onVaultUnlocked() {
		com.professor.zerion.android.profile.ProfileStorage storage =
				com.professor.zerion.android.AppModule.getAndroidComponent(this)
						.profileStorage();
		if (storage.offersSharedVaultClaim()) {
			storage.sharedVaultClaimOffered();
			showSharedVaultClaimDialog(storage);
		}
		showUnlockedHome();
	}

	private void showSharedVaultClaimDialog(
			com.professor.zerion.android.profile.ProfileStorage storage) {
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this)
				.setTitle(R.string.vault_claim_title)
				.setMessage(R.string.vault_claim_message)
				.setCancelable(false)
				.setPositiveButton(R.string.vault_claim_move, (d, w) -> {
					boolean claimed = storage.claimSharedVault();
					Toast.makeText(this, claimed
							? R.string.vault_claim_scheduled
							: R.string.vault_claim_failed,
							Toast.LENGTH_LONG).show();
				})
				.setNegativeButton(R.string.vault_claim_keep_shared, null)
				.show();
	}

	public void onVaultCreated() {
		showUnlockedHome();
	}

	private void showUnlockedHome() {
		if (isPickerMode) {
			showPickerFragment();
		} else {
			showVaultDashboard();
		}
	}

	@Override
	@Deprecated
	public void runOnDbThread(Runnable runnable) {
	}

	@Override
	public void onBackPressed() {
		try {
			androidx.fragment.app.FragmentManager fm = getSupportFragmentManager();
			if (fm.getBackStackEntryCount() > 0) {
				fm.popBackStack();
			} else {
				if (viewModel != null) {
					viewModel.lockIfUnlocked();
				}
				super.onBackPressed();
			}
		} catch (Exception e) {
			super.onBackPressed();
		}
	}

	@Override
	public ActivityComponent getActivityComponent() {
		return activityComponent;
	}

	@Override
	public void showNextFragment(BaseFragment f) {
		showFragment(f, f.getUniqueTag(), true);
	}
}
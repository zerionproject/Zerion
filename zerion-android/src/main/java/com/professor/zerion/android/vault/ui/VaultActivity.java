package com.professor.zerion.android.vault.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
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
import java.io.FileOutputStream;
import java.util.ArrayList;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.core.content.FileProvider;
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

	private VaultViewModel viewModel;
	private VaultViewModel.VaultState currentState = null;
	private boolean isPickerMode = false;
	private String pickerType = null;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);

		getWindow().setFlags(
				WindowManager.LayoutParams.FLAG_SECURE,
				WindowManager.LayoutParams.FLAG_SECURE
		);
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

	public void setExpectingChildResult() {
		expectingChildResult = true;
	}

	@Override
	public void onResume() {
		super.onResume();
		expectingChildResult = false;
		viewModel.refreshVaultState();
	}

	@Override
	protected void onStop() {
		super.onStop();
		if (!expectingChildResult) {
			viewModel.lockIfUnlocked();
		}
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
					"Please unlock the vault first to access settings",
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
						File cacheDir = new File(getCacheDir(), "vault_share");
						if (!cacheDir.exists()) {
							cacheDir.mkdirs();
						}
						File[] oldFiles = cacheDir.listFiles();
						if (oldFiles != null) {
							for (File f : oldFiles) {
								secureDeleteFile(f);
							}
						}

						String safeName = new File(item.name).getName();
						if (safeName.isEmpty() || safeName.equals(".")
								|| safeName.equals("..")) {
							safeName = "attachment";
						}
						File tempFile = new File(cacheDir, safeName);
						FileOutputStream fos = new FileOutputStream(tempFile);
						fos.write(content);
						fos.close();
						java.util.Arrays.fill(content, (byte) 0);

						Uri uri = FileProvider.getUriForFile(
								VaultActivity.this,
								getPackageName() + ".fileprovider",
								tempFile
						);

						runOnUiThread(() -> {
							ArrayList<Uri> uris = new ArrayList<>();
							uris.add(uri);
							Intent resultIntent = new Intent();
							resultIntent.putParcelableArrayListExtra(RESULT_SELECTED_URIS, uris);
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

	private static void secureDeleteFile(File f) {
		if (f == null || !f.isFile()) return;
		try {
			long len = f.length();
			if (len > 0 && len < 512L * 1024 * 1024) {
				try (java.io.RandomAccessFile raf =
						new java.io.RandomAccessFile(f, "rw")) {
					byte[] zeros = new byte[8192];
					long written = 0;
					while (written < len) {
						int chunk = (int) Math.min(zeros.length, len - written);
						raf.write(zeros, 0, chunk);
						written += chunk;
					}
					raf.getFD().sync();
				}
			}
		} catch (java.io.IOException ignored) {
		}
		if (!f.delete()) f.deleteOnExit();
	}

	private void showSetupFragment() {
		VaultOnboardingFragment fragment = VaultOnboardingFragment.newInstance();
		showFragment(fragment, "onboarding");
	}

	private void showUnlockFragment() {
		showFragment(new VaultUnlockFragment(), "unlock");
	}

	private void showVaultListFragment() {
		showFragment(new VaultListFragment(), "list");
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

		transaction.commit();

		invalidateOptionsMenu();
	}

	public void onVaultUnlocked() {
		showVaultDashboard();
	}

	public void onVaultCreated() {
		showVaultDashboard();
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
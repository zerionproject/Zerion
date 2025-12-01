package com.professor.zerion.android.vault.ui;

import android.content.Intent;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.vault.VaultManager;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultActivity extends ZerionActivity implements BaseFragment.BaseFragmentListener {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private VaultViewModel viewModel;
	private VaultViewModel.VaultState currentState = null;

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {

		super.onCreate(savedInstanceState);

		getWindow().setFlags(
				WindowManager.LayoutParams.FLAG_SECURE,
				WindowManager.LayoutParams.FLAG_SECURE
		);

		setContentView(R.layout.activity_vault);

		androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
		setSupportActionBar(toolbar);
		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setDisplayHomeAsUpEnabled(true);
			actionBar.setTitle("Zvault");
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
					showVaultDashboard();
					break;
			}
		});

	}

	@Override
	public void onResume() {
		super.onResume();
		viewModel.refreshVaultState();
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (isFinishing()) {
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
			showVaultDashboard();
		}
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
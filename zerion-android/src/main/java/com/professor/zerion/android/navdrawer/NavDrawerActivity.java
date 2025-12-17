package com.professor.zerion.android.navdrawer;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import com.professor.zerion.R;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.StartupFailureActivity;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.contact.ContactListFragment;
import com.professor.zerion.android.contact.add.remote.AddContactActivity;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.fragment.BaseFragment.BaseFragmentListener;
import com.professor.zerion.android.logout.SignOutFragment;
import com.professor.zerion.android.privategroup.creation.CreateGroupActivity;
import com.professor.zerion.android.privategroup.list.GroupListFragment;
import com.professor.zerion.android.settings.SettingsActivity;
import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.ui.VaultDashboardFragment;
import com.professor.zerion.android.view.AuthorView;
import de.hdodenhof.circleimageview.CircleImageView;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.ViewModelProvider;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static androidx.lifecycle.Lifecycle.State.STARTED;
import static org.briarproject.bramble.api.lifecycle.LifecycleManager.LifecycleState.RUNNING;
import static com.professor.zerion.android.ZerionService.EXTRA_STARTUP_FAILED;
import static com.professor.zerion.android.ZerionService.EXTRA_START_RESULT;
import static com.professor.zerion.android.TestingConstants.IS_DEBUG_BUILD;
import static com.professor.zerion.android.activity.RequestCodes.REQUEST_PASSWORD;
import static com.professor.zerion.android.navdrawer.IntentRouter.handleExternalIntent;
import static com.professor.zerion.android.util.UiUtils.getDaysUntilExpiry;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class NavDrawerActivity extends ZerionActivity implements
		BaseFragmentListener {

	public static Uri CONTACT_URI =
			Uri.parse("briar-content://contacts");
	public static Uri GROUP_URI =
			Uri.parse("briar-content://groups");
	public static Uri CONTACT_ADDED_URI =
			Uri.parse("briar-content://contact-added");
	public static Uri SIGN_OUT_URI =
			Uri.parse("briar-content://sign-out");

	private static final int TAB_CONTACTS = 0;
	private static final int TAB_GROUPS = 1;
	private static final int TAB_VAULT = 2;

	private NavDrawerViewModel navDrawerViewModel;

	// Track network status view state
	private boolean isShowingNetworkStatus = false;
	private int previousTab = TAB_CONTACTS;
	private String previousTitle = null;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	LifecycleManager lifecycleManager;

	@Inject
	VaultManager vaultManager;

	private MaterialCardView profileIcon;
	private CircleImageView profileAvatar;
	private TextView toolbarTitle;
	private ImageButton searchButton;
	private ImageButton menuButton;
	private TextView tabContacts;
	private TextView tabGroupChats;
	private TextView tabVault;
	private FloatingActionButton fabCompose;

	private int currentTab = TAB_CONTACTS;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		ViewModelProvider provider =
				new ViewModelProvider(this, viewModelFactory);
		navDrawerViewModel = provider.get(NavDrawerViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);
		exitIfStartupFailed(getIntent());
		setContentView(R.layout.activity_nav_drawer);

		ZerionApplication app = (ZerionApplication) getApplication();
		if (IS_DEBUG_BUILD && !app.isInstrumentationTest()) {
			navDrawerViewModel.showExpiryWarning()
					.observe(this, this::showExpiryWarning);
		}
		navDrawerViewModel.shouldAskForDozeWhitelisting().observe(this, ask -> {
			if (ask) showDozeDialog(R.string.dnkm_doze_intro);
		});
		navDrawerViewModel.getOwnIdentityInfo().observe(this, identityInfo -> {
			if (identityInfo != null) {
				AuthorView.setAvatar(profileAvatar,
						identityInfo.getLocalAuthor().getId(),
						identityInfo.getAuthorInfo());
			}
		});

		initializeViews();
		setupClickListeners();

		lockManager.isLockable().observe(this, this::setLockMenuItemVisible);

		if (lifecycleManager.getLifecycleState().isAfter(RUNNING)) {
			showSignOutFragment();
		}
		if (state == null) {
			onNewIntent(getIntent());
		}
	}

	private void initializeViews() {
		profileIcon = findViewById(R.id.profileIcon);
		profileAvatar = findViewById(R.id.profileAvatar);
		toolbarTitle = findViewById(R.id.toolbarTitle);
		searchButton = findViewById(R.id.searchButton);
		menuButton = findViewById(R.id.menuButton);
		tabContacts = findViewById(R.id.tabContacts);
		tabGroupChats = findViewById(R.id.tabGroupChats);
		tabVault = findViewById(R.id.tabVault);
		fabCompose = findViewById(R.id.fabCompose);
	}

	private void setupClickListeners() {
		profileIcon.setOnClickListener(v -> openSettings());
		searchButton.setOnClickListener(v -> toggleNetworkStatus());
		menuButton.setOnClickListener(v -> showOverflowMenu());

		tabContacts.setOnClickListener(v -> onTabClicked(TAB_CONTACTS));
		tabGroupChats.setOnClickListener(v -> onTabClicked(TAB_GROUPS));
		tabVault.setOnClickListener(v -> onTabClicked(TAB_VAULT));

		fabCompose.setOnClickListener(v -> handleComposeFab());
	}

	private void onTabClicked(int tab) {
		// If showing network status, exit it first
		if (isShowingNetworkStatus) {
			isShowingNetworkStatus = false;
			findViewById(R.id.bottomNavigation).setVisibility(VISIBLE);
			// Force switch since we're coming from network status
			switchTab(tab, true);
		} else {
			switchTab(tab);
		}
	}

	private void switchTab(int tab) {
		switchTab(tab, false);
	}

	private void switchTab(int tab, boolean forceSwitch) {
		// Only skip if same tab AND not forcing (force is used when returning from network status)
		if (currentTab == tab && !forceSwitch) return;

		currentTab = tab;
		updateTabUI();

		BaseFragment fragment;
		switch (tab) {
			case TAB_CONTACTS:
				toolbarTitle.setText(R.string.contact_list_button);
				fragment = ContactListFragment.newInstance();
				break;
			case TAB_GROUPS:
				toolbarTitle.setText(R.string.groups_button);
				fragment = GroupListFragment.newInstance();
				break;
			case TAB_VAULT:
				toolbarTitle.setText(R.string.vault_button);
				fragment = VaultDashboardFragment.newInstance();
				break;
			default:
				return;
		}

		// Replace fragment without adding to backstack for tab switches
		showTabFragment(fragment);
	}

	private void showTabFragment(BaseFragment f) {
		getSupportFragmentManager()
				.beginTransaction()
				.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
				.replace(R.id.fragmentContainer, f, f.getUniqueTag())
				.commit();

		updateFabVisibilityForFragment(f);
	}

	private void updateTabUI() {
		tabContacts.setTextColor(currentTab == TAB_CONTACTS ?
				0xFFFFFFFF : 0x80FFFFFF);
		tabContacts.setTypeface(null, currentTab == TAB_CONTACTS ?
				Typeface.BOLD : Typeface.NORMAL);

		tabGroupChats.setTextColor(currentTab == TAB_GROUPS ?
				0xFFFFFFFF : 0x80FFFFFF);
		tabGroupChats.setTypeface(null, currentTab == TAB_GROUPS ?
				Typeface.BOLD : Typeface.NORMAL);

		tabVault.setTextColor(currentTab == TAB_VAULT ?
				0xFFFFFFFF : 0x80FFFFFF);
		tabVault.setTypeface(null, currentTab == TAB_VAULT ?
				Typeface.BOLD : Typeface.NORMAL);
	}

	private void openSettings() {
		startActivity(new Intent(this, SettingsActivity.class));
	}

	private void toggleNetworkStatus() {
		if (isShowingNetworkStatus) {
			// Return to previous state
			isShowingNetworkStatus = false;

			// Show bottom navigation first
			findViewById(R.id.bottomNavigation).setVisibility(VISIBLE);

			// Force switch back to the previous tab (even if currentTab equals previousTab)
			switchTab(previousTab, true);

			// Update FAB visibility based on tab
			if (previousTab == TAB_CONTACTS || previousTab == TAB_GROUPS) {
				fabCompose.setVisibility(VISIBLE);
			}
		} else {
			// Save current state before showing network status
			previousTab = currentTab;
			previousTitle = toolbarTitle.getText().toString();
			isShowingNetworkStatus = true;

			// Update UI for network status view
			toolbarTitle.setText(R.string.network_status_title);

			// Hide bottom navigation when showing network status
			findViewById(R.id.bottomNavigation).setVisibility(GONE);

			// Hide FAB when showing network status
			fabCompose.setVisibility(GONE);

			// Show network status fragment (don't add to backstack)
			showNetworkStatusFragment();
		}
	}

	private void showNetworkStatusFragment() {
		TorStatusFragment fragment = new TorStatusFragment();
		getSupportFragmentManager()
				.beginTransaction()
				.setCustomAnimations(R.anim.fade_in, R.anim.fade_out)
				.replace(R.id.fragmentContainer, fragment, TorStatusFragment.TAG)
				.commit();
	}

	private void showOverflowMenu() {
		PopupMenu popup = new PopupMenu(this, menuButton);
		popup.getMenuInflater().inflate(R.menu.nav_drawer_menu, popup.getMenu());

		android.view.MenuItem vaultSettings = popup.getMenu().findItem(R.id.action_vault_settings);
		if (vaultSettings != null) {
			vaultSettings.setVisible(currentTab == TAB_VAULT && vaultManager.isUnlocked());
		}

		if (lockManager.isLockable().getValue() != null &&
				lockManager.isLockable().getValue()) {
			popup.getMenu().findItem(R.id.action_lock).setVisible(true);
		}

		popup.setOnMenuItemClickListener(item -> {
			int id = item.getItemId();
			if (id == R.id.action_vault_settings) {
				openVaultSettings();
				return true;
			} else if (id == R.id.action_lock) {
				lockApp();
				return true;
			} else if (id == R.id.action_sign_out) {
				signOut();
				return true;
			}
			return false;
		});
		popup.show();
	}

	private void openVaultSettings() {
		startFragment(new com.professor.zerion.android.vault.ui.VaultSettingsFragment());
	}

	private void handleComposeFab() {
		if (currentTab == TAB_CONTACTS) {
			Intent intent = new Intent(this, AddContactActivity.class);
			startActivity(intent);
		} else if (currentTab == TAB_GROUPS) {
			Intent intent = new Intent(this, CreateGroupActivity.class);
			startActivity(intent);
		}
	}

	private void lockApp() {
		lockManager.setLocked(true);
		ActivityCompat.finishAfterTransition(this);
	}

	private void signOut() {
		signOut(false, false);
		finish();
	}

	@Override
	public void onStart() {
		super.onStart();
		lockManager.checkIfLockable();
		if (IS_DEBUG_BUILD) {
			navDrawerViewModel.checkExpiryWarning();
		}
	}

	@Override
	protected void onActivityResult(int request, int result,
			@Nullable Intent data) {
		super.onActivityResult(request, result, data);
		if (request == REQUEST_PASSWORD && result == RESULT_OK) {
			navDrawerViewModel.checkDozeWhitelisting();
		}
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		exitIfStartupFailed(intent);

		if ("briar-content".equals(intent.getScheme())) {
			handleContentIntent(intent);
		} else {
			handleExternalIntent(this, intent);
		}
	}

	private void handleContentIntent(Intent intent) {
		Uri uri = intent.getData();
		if (CONTACT_URI.equals(uri) || CONTACT_ADDED_URI.equals(uri)) {
			switchTab(TAB_CONTACTS);
		} else if (GROUP_URI.equals(uri)) {
			switchTab(TAB_GROUPS);
		} else if (SIGN_OUT_URI.equals(uri)) {
			signOut(false, false);
		}
	}

	private void exitIfStartupFailed(Intent intent) {
		if (intent.getBooleanExtra(EXTRA_STARTUP_FAILED, false)) {
			Intent i = new Intent(this, StartupFailureActivity.class);
			i.putExtra(EXTRA_START_RESULT,
					intent.getSerializableExtra(EXTRA_START_RESULT));
			startActivity(i);
			finish();
			System.exit(0);
		}
	}

	@Override
	public void onBackPressed() {
		FragmentManager fm = getSupportFragmentManager();

		// Handle network status view - go back to previous state
		if (isShowingNetworkStatus) {
			toggleNetworkStatus();
			return;
		}

		if (fm.findFragmentByTag(SignOutFragment.TAG) != null) {
			finish();
		} else if (fm.getBackStackEntryCount() == 0 &&
				fm.findFragmentByTag(ContactListFragment.TAG) == null) {
			if (!getLifecycle().getCurrentState().isAtLeast(STARTED)) {
				return;
			}
			switchTab(TAB_CONTACTS);
		} else {
			super.onBackPressed();
		}
	}

	private void showSignOutFragment() {
		startFragment(new SignOutFragment());
	}

	/**
	 * Used for non-tab fragments like VaultSettings, SignOut, etc.
	 * These are added to backstack so user can navigate back.
	 */
	private void startFragment(BaseFragment f) {
		getSupportFragmentManager()
				.beginTransaction()
				.setCustomAnimations(R.anim.fade_in, R.anim.fade_out,
						R.anim.fade_in, R.anim.fade_out)
				.replace(R.id.fragmentContainer, f, f.getUniqueTag())
				.addToBackStack(f.getUniqueTag())
				.commit();

		updateFabVisibilityForFragment(f);
	}

	private void updateFabVisibilityForFragment(BaseFragment f) {
		boolean isMainContacts = f instanceof ContactListFragment;
		boolean isMainGroups = f instanceof GroupListFragment;

		if (isMainContacts || isMainGroups) {
			fabCompose.setVisibility(VISIBLE);
		} else {
			fabCompose.setVisibility(GONE);
		}
	}

	@Override
	public void handleException(Exception e) {
	}

	private void setLockMenuItemVisible(boolean visible) {
	}

	private void showExpiryWarning(boolean show) {
		long daysUntilExpiry = getDaysUntilExpiry();
		if (daysUntilExpiry < 0) {
			signOut();
			return;
		}

		View expiryWarning = findViewById(R.id.expiryWarning);
		if (show) {
			TextView expiryWarningText =
					expiryWarning.findViewById(R.id.expiryWarningText);
			String text = getResources().getQuantityString(
					R.plurals.expiry_warning, (int) daysUntilExpiry,
					(int) daysUntilExpiry);
			expiryWarningText.setText(text);

			ImageView expiryWarningClose =
					expiryWarning.findViewById(R.id.expiryWarningClose);
			expiryWarningClose.setOnClickListener(v ->
					navDrawerViewModel.expiryWarningDismissed());
			expiryWarning.setVisibility(VISIBLE);
		} else {
			expiryWarning.setVisibility(GONE);
		}
	}
}

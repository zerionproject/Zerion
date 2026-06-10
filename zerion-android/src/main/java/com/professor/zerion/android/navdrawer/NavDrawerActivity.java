package com.professor.zerion.android.navdrawer;

import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.AppModule;
import com.professor.zerion.android.ZerionApplication;
import com.professor.zerion.android.StartupFailureActivity;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.contact.ContactListFragment;
import com.professor.zerion.android.contact.add.remote.AddContactActivity;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.fragment.BaseFragment.BaseFragmentListener;
import com.professor.zerion.android.logout.SignOutFragment;
import com.professor.zerion.android.grouptr.GroupTrCreateActivity;
import com.professor.zerion.android.grouptr.GroupTrListFragment;
import com.professor.zerion.android.grouptr.GroupTrListFragment;
import com.professor.zerion.android.settings.SettingsActivity;
import com.professor.zerion.android.donation.DonationManager;
import com.professor.zerion.android.vault.VaultManager;
import com.professor.zerion.android.vault.ui.VaultDashboardFragment;
import com.professor.zerion.android.view.AuthorView;
import com.professor.zerion.android.widget.DonationDialogFragment;
import com.google.android.material.imageview.ShapeableImageView;
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

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class NavDrawerActivity extends ZerionActivity implements
		BaseFragmentListener {

	public static Uri CONTACT_URI =
			Uri.parse("zerion-content://contacts");
	public static Uri GROUP_URI =
			Uri.parse("zerion-content://groups");
	public static Uri CONTACT_ADDED_URI =
			Uri.parse("zerion-content://contact-added");
	public static Uri SIGN_OUT_URI =
			Uri.parse("zerion-content://sign-out");

	private static final int TAB_CONTACTS = 0;
	private static final int TAB_GROUPS = 1;
	private static final int TAB_CHANNELS = 2;
	private static final int TAB_VAULT = 3;

	private NavDrawerViewModel navDrawerViewModel;
	private boolean isShowingNetworkStatus = false;
	private int previousTab = TAB_CONTACTS;
	private String previousTitle = null;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	LifecycleManager lifecycleManager;

	@Inject
	VaultManager vaultManager;

	@Inject
	DonationManager donationManager;

	@Inject
	@AppModule.UiPrefs
	android.content.SharedPreferences uiPrefs;

	@Inject
	@org.briarproject.bramble.api.lifecycle.IoExecutor
	java.util.concurrent.Executor ioExecutor;

	private MaterialCardView profileIcon;
	private ShapeableImageView profileAvatar;
	private TextView toolbarTitle;
	private ImageButton searchButton;
	private ImageButton menuButton;
	private ImageButton vaultShortcutButton;
	private TextView tabContacts;
	private TextView tabGroupChats;
	private TextView tabChannels;
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
		getWindow().setFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE,
				android.view.WindowManager.LayoutParams.FLAG_SECURE);
		exitIfStartupFailed(getIntent());
		setContentView(R.layout.activity_nav_drawer);

		ZerionApplication app = (ZerionApplication) getApplication();
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
		maybeShowPostUpdateNotice();
	}

	private void maybeShowPostUpdateNotice() {
		if (!uiPrefs.getBoolean(
				AppModule.PREF_POST_UPDATE_NOTICE_PENDING, false)) {
			return;
		}
		getWindow().getDecorView().postDelayed(() -> {
			if (isFinishing() || isDestroyed()) return;
			if (!getLifecycle().getCurrentState().isAtLeast(
					androidx.lifecycle.Lifecycle.State.RESUMED)) return;
			new MaterialAlertDialogBuilder(this)
					.setTitle(R.string.post_update_notice_title)
					.setMessage(R.string.post_update_notice_message)
					.setPositiveButton(R.string.got_it, (d, w) -> {
					})
					.setOnDismissListener(d -> uiPrefs.edit().putBoolean(
							AppModule.PREF_POST_UPDATE_NOTICE_PENDING, false)
							.apply())
					.show();
		}, 900);
	}

	private void initializeViews() {
		profileIcon = findViewById(R.id.profileIcon);
		profileAvatar = findViewById(R.id.profileAvatar);
		toolbarTitle = findViewById(R.id.toolbarTitle);
		searchButton = findViewById(R.id.searchButton);
		menuButton = findViewById(R.id.menuButton);
		vaultShortcutButton = findViewById(R.id.vaultShortcutButton);
		tabContacts = findViewById(R.id.tabContacts);
		tabGroupChats = findViewById(R.id.tabGroupChats);
		tabChannels = findViewById(R.id.tabChannels);
		fabCompose = findViewById(R.id.fabCompose);

		View bottomNav = findViewById(R.id.bottomNavigation);
		if (bottomNav != null) {
			int heightDp = com.professor.zerion.android.settings.ChatPreferences
					.getNavBarHeightDp(this);
			float density = getResources().getDisplayMetrics().density;
			ViewGroup.LayoutParams lp = bottomNav.getLayoutParams();
			lp.height = (int) (heightDp * density);
			bottomNav.setLayoutParams(lp);

			float textSizeSp = com.professor.zerion.android.settings.ChatPreferences
					.getNavBarTextSizeSp(this);
			tabContacts.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
					textSizeSp);
			tabGroupChats.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
					textSizeSp);
			tabChannels.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,
					textSizeSp);
		}
	}

	private void setupClickListeners() {
		profileIcon.setOnClickListener(v -> {
			com.professor.zerion.android.util.Haptics.tap(v);
			openSettings();
		});
		searchButton.setOnClickListener(v -> {
			com.professor.zerion.android.util.Haptics.tap(v);
			toggleNetworkStatus();
		});
		menuButton.setOnClickListener(v -> {
			com.professor.zerion.android.util.Haptics.tap(v);
			showOverflowMenu();
		});
		vaultShortcutButton.setOnClickListener(v -> {
			com.professor.zerion.android.util.Haptics.tap(v);
			switchTab(TAB_VAULT);
		});

		tabContacts.setOnClickListener(v -> {
			com.professor.zerion.android.util.Haptics.tap(v);
			onTabClicked(TAB_CONTACTS);
		});
		tabGroupChats.setOnClickListener(v -> {
			com.professor.zerion.android.util.Haptics.tap(v);
			onTabClicked(TAB_GROUPS);
		});
		tabChannels.setOnClickListener(v -> {
			com.professor.zerion.android.util.Haptics.tap(v);
			onTabClicked(TAB_CHANNELS);
		});

		fabCompose.setOnClickListener(v -> {
			com.professor.zerion.android.util.Haptics.confirm(v);
			handleComposeFab();
		});
	}

	private void onTabClicked(int tab) {
		if (isShowingNetworkStatus) {
			isShowingNetworkStatus = false;
			findViewById(R.id.bottomNavigation).setVisibility(VISIBLE);
			switchTab(tab, true);
		} else {
			switchTab(tab);
		}
	}

	private void switchTab(int tab) {
		switchTab(tab, false);
	}

	private void switchTab(int tab, boolean forceSwitch) {
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
				fragment = GroupTrListFragment.newInstance();
				break;
			case TAB_CHANNELS:
				toolbarTitle.setText(R.string.channels_button);
				fragment = com.professor.zerion.android.channel
						.ChannelListFragment.newInstance();
				break;
			case TAB_VAULT:
				toolbarTitle.setText(R.string.vault_button);
				fragment = VaultDashboardFragment.newInstance();
				break;
			default:
				return;
		}
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

		tabChannels.setTextColor(currentTab == TAB_CHANNELS ?
				0xFFFFFFFF : 0x80FFFFFF);
		tabChannels.setTypeface(null, currentTab == TAB_CHANNELS ?
				Typeface.BOLD : Typeface.NORMAL);
	}

	private void openSettings() {
		startActivity(new Intent(this, SettingsActivity.class));
	}

	private void toggleNetworkStatus() {
		if (isShowingNetworkStatus) {
			isShowingNetworkStatus = false;
			findViewById(R.id.bottomNavigation).setVisibility(VISIBLE);
			switchTab(previousTab, true);
			if (previousTab == TAB_CONTACTS || previousTab == TAB_GROUPS) {
				fabCompose.setVisibility(VISIBLE);
			}
		} else {
			previousTab = currentTab;
			previousTitle = toolbarTitle.getText().toString();
			isShowingNetworkStatus = true;
			toolbarTitle.setText(R.string.network_status_title);
			findViewById(R.id.bottomNavigation).setVisibility(GONE);
			fabCompose.setVisibility(GONE);
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
			startActivity(GroupTrCreateActivity.intent(this));
		} else if (currentTab == TAB_CHANNELS) {
			showChannelsFabMenu();
		}
	}

	private void showChannelsFabMenu() {
		PopupMenu popup = new PopupMenu(this, fabCompose);
		popup.getMenu().add(0, 1, 0, R.string.channels_action_create);
		popup.getMenu().add(0, 2, 1, R.string.channels_action_join);
		popup.setOnMenuItemClickListener(item -> {
			androidx.fragment.app.Fragment current =
					getSupportFragmentManager().findFragmentById(
							R.id.fragmentContainer);
			if (!(current instanceof
					com.professor.zerion.android.channel.ChannelListFragment)) {
				return false;
			}
			com.professor.zerion.android.channel.ChannelListFragment f =
					(com.professor.zerion.android.channel.ChannelListFragment)
							current;
			if (item.getItemId() == 1) {
				f.showCreateDialog();
				return true;
			} else if (item.getItemId() == 2) {
				f.showJoinDialog();
				return true;
			}
			return false;
		});
		popup.show();
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
		checkAndShowDonationDialog();
	}

	private void checkAndShowDonationDialog() {
		ioExecutor.execute(() -> {
			boolean shouldShow = donationManager.shouldShowDonationDialog();
			if (!shouldShow) return;
			runOnUiThread(() -> getWindow().getDecorView().postDelayed(() -> {
				if (!isFinishing() && !isDestroyed()) {
					showDonationDialog();
				}
			}, 1500));
		});
	}

	private void showDonationDialog() {
		if (getLifecycle().getCurrentState().isAtLeast(
				androidx.lifecycle.Lifecycle.State.RESUMED)) {
			DonationDialogFragment dialog = DonationDialogFragment.newInstance();
			dialog.show(getSupportFragmentManager(), DonationDialogFragment.TAG);
			donationManager.onDialogShown();
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

		if ("zerion-content".equals(intent.getScheme())) {
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

		if (intent.getComponent() != null
				&& getPackageName().equals(
						intent.getComponent().getPackageName())
				&& intent.getBooleanExtra(EXTRA_STARTUP_FAILED, false)) {
			Intent i = new Intent(this, StartupFailureActivity.class);
			String resultName = intent.getStringExtra(EXTRA_START_RESULT);
			if (resultName != null) {
				i.putExtra(EXTRA_START_RESULT, resultName);
			}
			startActivity(i);
			finish();
			System.exit(0);
		}
	}

	@Override
	public void onBackPressed() {
		FragmentManager fm = getSupportFragmentManager();
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
		boolean isMainGroups = f instanceof GroupTrListFragment;
		boolean isMainChannels = f instanceof
				com.professor.zerion.android.channel.ChannelListFragment;

		if (isMainContacts || isMainGroups || isMainChannels) {
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

}

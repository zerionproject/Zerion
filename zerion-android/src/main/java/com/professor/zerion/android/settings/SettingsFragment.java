package com.professor.zerion.android.settings;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.test.TestDataActivity;
import com.professor.zerion.android.util.ActivityLaunchers.GetImageAdvanced;
import com.professor.zerion.android.util.ActivityLaunchers.OpenImageDocumentAdvanced;
import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.signature.ObjectKey;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import com.google.android.material.imageview.ShapeableImageView;

import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static com.professor.zerion.android.TestingConstants.IS_DEBUG_BUILD;
import static com.professor.zerion.android.util.UiUtils.launchActivityToOpenFile;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SettingsFragment extends Fragment {

	public static final String SETTINGS_NAMESPACE = "android-ui";

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private SettingsViewModel viewModel;

	private View avatarCard;
	private ShapeableImageView avatarImage;
	private TextView usernameText;
	private View displayCard;
	private View networkCard;
	private View securityCard;
	private View profilesCard;
	private View notificationsCard;
	private View aboutCard;
	private View supportCard;
	private View inviteFriendsCard;
	private TextView devSectionHeader;
	private View testDataCard;
	private View testDataDivider;
	private View crashCard;
	private View devSectionDivider;

	private final ActivityResultLauncher<String[]> docLauncher =
			registerForActivityResult(new OpenImageDocumentAdvanced(),
					this::onImageSelected);
	private final ActivityResultLauncher<String> contentLauncher =
			registerForActivityResult(new GetImageAdvanced(),
					this::onImageSelected);

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(SettingsViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_settings_main, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view,
			@Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		avatarCard = view.findViewById(R.id.avatar_card);
		avatarImage = view.findViewById(R.id.avatar_image);
		usernameText = view.findViewById(R.id.username_text);
		displayCard = view.findViewById(R.id.display_card);
		networkCard = view.findViewById(R.id.network_card);
		securityCard = view.findViewById(R.id.security_card);
		profilesCard = view.findViewById(R.id.profiles_card);
		notificationsCard = view.findViewById(R.id.notifications_card);
		aboutCard = view.findViewById(R.id.about_card);
		supportCard = view.findViewById(R.id.support_card);
		inviteFriendsCard = view.findViewById(R.id.invite_friends_card);
		View myIdentityCard = view.findViewById(R.id.my_identity_card);
		devSectionHeader = view.findViewById(R.id.dev_section_header);
		testDataCard = view.findViewById(R.id.test_data_card);
		testDataDivider = view.findViewById(R.id.test_data_divider);
		crashCard = view.findViewById(R.id.crash_card);
		devSectionDivider = view.findViewById(R.id.dev_section_divider);

		if (viewModel.shouldEnableProfilePictures()) {
			View changeAvatarButton = view.findViewById(R.id.change_avatar_button);
			View.OnClickListener pickAvatar = v ->
					launchActivityToOpenFile(requireContext(),
							docLauncher, contentLauncher, "image/*");
			changeAvatarButton.setOnClickListener(pickAvatar);
			avatarCard.setOnClickListener(pickAvatar);
		} else {
			avatarCard.setClickable(false);
			avatarCard.setFocusable(false);
		}

		viewModel.getOwnIdentityInfo().observe(getViewLifecycleOwner(),
				this::displayOwnIdentityInfo);

		displayCard.setOnClickListener(v -> showDisplaySettings());
		networkCard.setOnClickListener(v -> showNetworkSettings());
		securityCard.setOnClickListener(v -> showSecuritySettings());
		View backupCard = view.findViewById(R.id.backup_card);
		if (backupCard != null) {
			backupCard.setOnClickListener(v -> showBackupSettings());
		}
		if (profilesCard != null) {
			profilesCard.setOnClickListener(v -> showProfilesSettings());
		}
		notificationsCard.setOnClickListener(v -> showNotificationsSettings());
		aboutCard.setOnClickListener(v -> showAboutSettings());
		if (supportCard != null) {
			supportCard.setOnClickListener(v -> showDonationDialog());
		}
		if (inviteFriendsCard != null) {
			inviteFriendsCard.setOnClickListener(v -> shareInvite());
		}
		if (myIdentityCard != null) {
			myIdentityCard.setOnClickListener(v -> showMyIdentityDialog());
			viewModel.getMyFingerprint().observeEvent(getViewLifecycleOwner(),
					this::showMyFingerprintInDialog);
		}

		if (IS_DEBUG_BUILD) {
			testDataCard.setOnClickListener(v -> {
				Intent i = new Intent(requireContext(), TestDataActivity.class);
				requireContext().startActivity(i);
			});
			crashCard.setOnClickListener(v -> {
				throw new RuntimeException("Test crash");
			});
		} else {
			devSectionHeader.setVisibility(View.GONE);
			testDataCard.setVisibility(View.GONE);
			testDataDivider.setVisibility(View.GONE);
			crashCard.setVisibility(View.GONE);
			devSectionDivider.setVisibility(View.GONE);
		}
	}


	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.settings_button);
	}

	private void displayOwnIdentityInfo(OwnIdentityInfo info) {
		usernameText.setText(info.getLocalAuthor().getName());
		if (info.getAuthorInfo().getAvatarHeader() != null) {
			Glide.with(this)
					.load(info.getAuthorInfo().getAvatarHeader())
					.diskCacheStrategy(DiskCacheStrategy.NONE)
					.skipMemoryCache(true)
					.signature(new ObjectKey(System.currentTimeMillis()))
					.placeholder(R.drawable.ic_person)
					.error(R.drawable.ic_person)
					.into(avatarImage);
		} else {
			avatarImage.setImageResource(R.drawable.ic_person);
		}
	}

	private void onImageSelected(@Nullable Uri uri) {
		if (uri != null) viewModel.setAvatar(uri);
	}

	private void showDisplaySettings() {
		requireActivity().getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, new DisplayFragment())
				.addToBackStack(null)
				.commit();
	}

	private void showNetworkSettings() {
		requireActivity().getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, new ConnectionsFragment())
				.addToBackStack(null)
				.commit();
	}

	private void showSecuritySettings() {
		requireActivity().getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, new SecurityFragment())
				.addToBackStack(null)
				.commit();
	}

	private void showBackupSettings() {
		requireActivity().getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, new BackupFragment())
				.addToBackStack(null)
				.commit();
	}

	private void showProfilesSettings() {
		requireActivity().getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, new ProfilesFragment())
				.addToBackStack(null)
				.commit();
	}

	private void showNotificationsSettings() {
		requireActivity().getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, new NotificationsFragment())
				.addToBackStack(null)
				.commit();
	}

	private void shareInvite() {
		Intent shareIntent = new Intent(Intent.ACTION_SEND);
		shareIntent.setType("text/plain");
		shareIntent.putExtra(Intent.EXTRA_TEXT,
				getString(R.string.invite_friends_message));
		startActivity(Intent.createChooser(shareIntent,
				getString(R.string.invite_friends_chooser)));
	}

	private void showAboutSettings() {
		requireActivity().getSupportFragmentManager().beginTransaction()
				.replace(R.id.fragmentContainer, new AboutFragment())
				.addToBackStack(null)
				.commit();
	}

	private void showDonationDialog() {
		com.professor.zerion.android.widget.DonationDialogFragment dialog =
				com.professor.zerion.android.widget.DonationDialogFragment
						.newInstance();
		dialog.show(getParentFragmentManager(),
				com.professor.zerion.android.widget.DonationDialogFragment.TAG);
	}

	@Nullable
	private androidx.appcompat.app.AlertDialog myIdentityDialog;

	private void showMyIdentityDialog() {
		android.content.Context ctx = requireContext();
		android.view.View dlgView = android.view.LayoutInflater.from(ctx)
				.inflate(R.layout.dialog_my_identity, null);
		myIdentityDialog =
				new com.google.android.material.dialog.MaterialAlertDialogBuilder(
						ctx, R.style.ZerionDialogTheme)
						.setView(dlgView)
						.setTitle(R.string.settings_my_identity_title)
						.setPositiveButton(android.R.string.ok, null)
						.create();
		myIdentityDialog.show();
		viewModel.loadMyFingerprint();
	}

	private void showMyFingerprintInDialog(@Nullable String fp) {
		if (myIdentityDialog == null || fp == null) return;
		TextView tv = myIdentityDialog.findViewById(R.id.my_fingerprint_value);
		if (tv != null) tv.setText(fp);
	}

}
package com.professor.zerion.android.vault.ui;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.widget.TextView;


import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class VaultDashboardFragment extends BaseFragment {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private VaultViewModel viewModel;
	@Nullable
	private androidx.appcompat.app.AlertDialog gateProgress;

	private TextView notesCount;
	private TextView imagesCount;
	private TextView docsCount;
	private View notesCard;
	private View galleryCard;
	private View documentsCard;
	private View passwordsCard;
	private View walletCard;

	public static VaultDashboardFragment newInstance() {
		return new VaultDashboardFragment();
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View view = inflater.inflate(R.layout.fragment_vault_dashboard, container, false);

		notesCount = view.findViewById(R.id.notes_count);
		imagesCount = view.findViewById(R.id.images_count);
		docsCount = view.findViewById(R.id.docs_count);
		notesCard = view.findViewById(R.id.notes_card);
		galleryCard = view.findViewById(R.id.gallery_card);
		documentsCard = view.findViewById(R.id.documents_card);
		passwordsCard = view.findViewById(R.id.passwords_card);
		walletCard = view.findViewById(R.id.wallet_card);

		return view;
	}

	@Override
	public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(VaultViewModel.class);

		checkVaultState();

		setupClickListeners();
		observeViewModel();

		animateCardsEntrance();
	}

	private void checkVaultState() {
		viewModel.refreshVaultState();
		VaultViewModel.VaultState currentState = viewModel.getVaultState().getValue();

		if (currentState == VaultViewModel.VaultState.NOT_CREATED) {
			showNextFragment(VaultOnboardingFragment.newInstance());
		} else if (currentState == VaultViewModel.VaultState.LOCKED) {
			showNextFragment(VaultUnlockFragment.newInstance());
		}
	}

	private void setupClickListeners() {
		notesCard.setOnClickListener(v -> {
			showNextFragment(new VaultListFragment());
		});

		galleryCard.setOnClickListener(v -> {
			showNextFragment(VaultGalleryFragment.newInstance());
		});

		documentsCard.setOnClickListener(v -> {
			showNextFragment(VaultDocumentsFragment.newInstance());
		});

		passwordsCard.setOnClickListener(v -> {
			showNextFragment(VaultPasswordsFragment.newInstance());
		});

		if (walletCard != null) {
			walletCard.setOnClickListener(v -> viewModel.beginWalletAccess());
		}
	}

	/**
	 * Shared coin selector shown after the wallet gate. Bitcoin dispatches into
	 * the existing (frozen) BTC wallet flow unchanged; Monero dispatches into the
	 * reviewed XMR wallet flow. XMR is reached only through this selector, never a
	 * hidden gesture.
	 */
	private void showCoinSelector() {
		if (!isAdded()) {
			return;
		}
		android.view.View view = getLayoutInflater().inflate(
				R.layout.dialog_coin_selector, null);
		androidx.appcompat.app.AlertDialog dialog =
				new com.google.android.material.dialog.MaterialAlertDialogBuilder(
						requireContext())
						.setTitle(R.string.wallet_choose_coin)
						.setView(view)
						.create();
		view.findViewById(R.id.coin_bitcoin).setOnClickListener(v -> {
			dialog.dismiss();
			showNextFragment(VaultWalletFragment.newInstance());
		});
		view.findViewById(R.id.coin_monero).setOnClickListener(v -> {
			dialog.dismiss();
			showNextFragment(XmrWalletFragment.newInstance());
		});
		dialog.show();
	}

	private int dp(int value) {
		return Math.round(value * getResources().getDisplayMetrics().density);
	}

	private com.google.android.material.textfield.TextInputEditText authField(
			android.widget.LinearLayout box, int hint) {
		com.google.android.material.textfield.TextInputLayout til =
				new com.google.android.material.textfield.TextInputLayout(
						requireContext());
		com.google.android.material.textfield.TextInputEditText e =
				new com.google.android.material.textfield.TextInputEditText(
						til.getContext());
		e.setHint(hint);
		til.addView(e);
		box.addView(til);
		return e;
	}

	private static char[] authChars(
			com.google.android.material.textfield.TextInputEditText e) {
		return e.getText() == null ? new char[0]
				: e.getText().toString().toCharArray();
	}

	private void showWalletSetupDialog() {
		android.content.Context ctx = requireContext();
		android.widget.LinearLayout box = new android.widget.LinearLayout(ctx);
		box.setOrientation(android.widget.LinearLayout.VERTICAL);
		int p = dp(20);
		box.setPadding(p, dp(8), p, 0);

		android.widget.RadioGroup types = new android.widget.RadioGroup(ctx);
		types.setOrientation(android.widget.RadioGroup.HORIZONTAL);
		android.widget.RadioButton pin = new android.widget.RadioButton(ctx);
		pin.setId(1);
		pin.setText(R.string.wallet_auth_pin);
		android.widget.RadioButton pwd = new android.widget.RadioButton(ctx);
		pwd.setId(2);
		pwd.setText(R.string.wallet_auth_password);
		pwd.setPadding(dp(16), 0, 0, 0);
		types.addView(pin);
		types.addView(pwd);
		types.check(1);
		box.addView(types);

		com.google.android.material.textfield.TextInputEditText cred =
				authField(box, R.string.wallet_auth_enter);
		com.google.android.material.textfield.TextInputEditText confirm =
				authField(box, R.string.wallet_auth_confirm);
		Runnable applyType = () -> {
			int it = types.getCheckedRadioButtonId() == 1
					? (android.text.InputType.TYPE_CLASS_NUMBER
							| android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD)
					: (android.text.InputType.TYPE_CLASS_TEXT
							| android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
			cred.setInputType(it);
			confirm.setInputType(it);
		};
		types.setOnCheckedChangeListener((g, id) -> applyType.run());
		applyType.run();

		androidx.appcompat.app.AlertDialog dlg =
				new com.google.android.material.dialog.MaterialAlertDialogBuilder(
						ctx)
						.setTitle(R.string.wallet_auth_setup_title)
						.setMessage(R.string.wallet_auth_setup_message)
						.setView(box)
						.setPositiveButton(R.string.wallet_auth_set, null)
						.setNegativeButton(android.R.string.cancel, null)
						.create();
		dlg.setOnShowListener(dd -> dlg.getButton(
				androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
				.setOnClickListener(x -> {
					boolean isPin = types.getCheckedRadioButtonId() == 1;
					char[] c1 = authChars(cred);
					char[] c2 = authChars(confirm);
					int min = isPin ? 4 : 6;
					if (c1.length < min) {
						showToast(getString(R.string.wallet_auth_too_short));
						return;
					}
					if (!java.util.Arrays.equals(c1, c2)) {
						showToast(getString(R.string.wallet_auth_mismatch));
						return;
					}
					viewModel.setupWalletAuth(isPin ? "PIN" : "PASSWORD", c1);
					dlg.dismiss();
				}));
		dlg.show();
	}

	private void showWalletVerifyDialog() {
		android.content.Context ctx = requireContext();
		com.google.android.material.textfield.TextInputLayout til =
				new com.google.android.material.textfield.TextInputLayout(ctx);
		com.google.android.material.textfield.TextInputEditText input =
				new com.google.android.material.textfield.TextInputEditText(
						til.getContext());
		input.setHint(R.string.wallet_auth_verify_hint);
		input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
				| android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
		til.addView(input);
		int pad = dp(20);
		til.setPadding(pad, 0, pad, 0);
		new com.google.android.material.dialog.MaterialAlertDialogBuilder(ctx)
				.setTitle(R.string.wallet_auth_verify_title)
				.setView(til)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					char[] c = authChars(input);
					if (c.length > 0) {
						viewModel.verifyWalletAuth(c);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void showGateProgress() {
		if (gateProgress != null && gateProgress.isShowing()) {
			return;
		}
		android.widget.ProgressBar bar =
				new android.widget.ProgressBar(requireContext());
		int pad = Math.round(24 * getResources().getDisplayMetrics().density);
		bar.setPadding(pad, pad, pad, pad);
		gateProgress = new com.google.android.material.dialog
				.MaterialAlertDialogBuilder(requireContext())
				.setMessage(R.string.wallet_verifying)
				.setView(bar)
				.setCancelable(false)
				.create();
		gateProgress.show();
	}

	private void hideGateProgress() {
		if (gateProgress != null) {
			gateProgress.dismiss();
			gateProgress = null;
		}
	}

	private void observeViewModel() {
		viewModel.getVaultState().observe(getViewLifecycleOwner(), state -> {
			if (state == VaultViewModel.VaultState.UNLOCKED) {
				viewModel.loadVaultItems();
			}
		});

		viewModel.getVaultItems().observe(getViewLifecycleOwner(), items -> {
			if (items != null) {
				updateCounts(items);
			}
		});

		viewModel.getErrorMessage().observe(getViewLifecycleOwner(), error -> {
			if (error != null && !error.isEmpty()) {
				showToast(error);
			}
		});

		viewModel.getWalletGateBusy().observe(getViewLifecycleOwner(), busy -> {
			if (Boolean.TRUE.equals(busy)) {
				showGateProgress();
			} else {
				hideGateProgress();
			}
		});

		viewModel.getWalletGateGranted().observe(getViewLifecycleOwner(),
				granted -> {
					if (granted == null) {
						return;
					}
					viewModel.clearWalletGate();
					if (granted) {
						showCoinSelector();
					} else {
						showToast(getString(R.string.wallet_wrong_password));
					}
				});

		viewModel.getWalletAuthState().observe(getViewLifecycleOwner(),
				state -> {
					if (state == null) {
						return;
					}
					viewModel.clearWalletAuthState();
					if ("setup".equals(state)) {
						showWalletSetupDialog();
					} else {
						showWalletVerifyDialog();
					}
				});

		viewModel.getWalletError().observe(getViewLifecycleOwner(), ev -> {
			String err = ev == null ? null : ev.getIfNotHandled();
			if (err != null && !err.isEmpty()) {
				showToast(err);
			}
		});
	}

	private void updateCounts(java.util.List<com.professor.zerion.android.vault.model.VaultItem> items) {
		int notes = 0, media = 0, docs = 0;

		for (com.professor.zerion.android.vault.model.VaultItem item : items) {
			switch (item.type) {
				case NOTE:
					notes++;
					break;
				case IMAGE:
				case VIDEO:
					media++;
					break;
				case DOCUMENT:
					docs++;
					break;
			}
		}

		notesCount.setText(String.valueOf(notes));
		imagesCount.setText(String.valueOf(media));
		docsCount.setText(String.valueOf(docs));
	}

	private void showToast(String message) {
		android.widget.Toast.makeText(requireContext(), message,
				android.widget.Toast.LENGTH_SHORT).show();
	}

	private void animateCardsEntrance() {
		Handler handler = new Handler(Looper.getMainLooper());

		notesCard.setAlpha(0f);
		galleryCard.setAlpha(0f);
		documentsCard.setAlpha(0f);
		passwordsCard.setAlpha(0f);

		View statsCard = getView().findViewById(R.id.stats_card);
		if (statsCard != null) {
			Animation slideDown = AnimationUtils.loadAnimation(getContext(), R.anim.slide_down_fade_in);
			statsCard.startAnimation(slideDown);
		}

		int delay = 100;
		animateCard(notesCard, delay);
		animateCard(galleryCard, delay * 2);
		animateCard(documentsCard, delay * 3);
		animateCard(passwordsCard, delay * 4);
	}

	private void animateCard(View card, int delay) {
		card.postDelayed(() -> {
			card.animate()
				.alpha(1f)
				.scaleX(1f)
				.scaleY(1f)
				.setDuration(300)
				.setInterpolator(new android.view.animation.DecelerateInterpolator())
				.start();
		}, delay);

		card.setScaleX(0.9f);
		card.setScaleY(0.9f);
	}

	@Override
	public String getUniqueTag() {
		return "VaultDashboardFragment";
	}
}
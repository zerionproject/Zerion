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

import com.google.android.material.card.MaterialCardView;

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

	private TextView notesCount;
	private TextView imagesCount;
	private TextView docsCount;
	private View notesCard;
	private View galleryCard;
	private View documentsCard;
	private View passwordsCard;

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
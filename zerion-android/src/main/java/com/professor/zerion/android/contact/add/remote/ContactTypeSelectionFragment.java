package com.professor.zerion.android.contact.add.remote;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;
import com.professor.zerion.android.view.InfoView;

import org.briarproject.bramble.api.contact.ContactType;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.cardview.widget.CardView;
import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ContactTypeSelectionFragment extends BaseFragment {

	private static final String TAG = ContactTypeSelectionFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private AddContactViewModel viewModel;

	@Override
	public String getUniqueTag() {
		return TAG;
	}

	@Override
	public void injectFragment(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(AddContactViewModel.class);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		if (getActivity() == null || getContext() == null) return null;

		View v = inflater.inflate(R.layout.fragment_contact_type_selection,
				container, false);

		CardView zerionCard = v.findViewById(R.id.zerionCard);
		CardView briarCard = v.findViewById(R.id.briarCard);
		InfoView infoView = v.findViewById(R.id.infoView);

		infoView.setText(R.string.contact_type_info);

		zerionCard.setOnClickListener(view -> {
			viewModel.setContactType(ContactType.ZERION);
		});

		briarCard.setOnClickListener(view -> {
			viewModel.setContactType(ContactType.BRIAR);
		});

		return v;
	}
}

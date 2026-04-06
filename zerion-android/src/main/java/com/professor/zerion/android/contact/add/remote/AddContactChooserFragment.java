package com.professor.zerion.android.contact.add.remote;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.cardview.widget.CardView;
import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class AddContactChooserFragment extends BaseFragment {

	private static final String TAG = AddContactChooserFragment.class.getName();

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
		if (getActivity() == null) return null;

		View v = inflater.inflate(R.layout.fragment_add_contact_chooser,
				container, false);

		CardView qrCard = v.findViewById(R.id.qrExchangeCard);
		CardView linkCard = v.findViewById(R.id.linkExchangeCard);

		qrCard.setOnClickListener(view -> viewModel.onQrExchangeChosen());
		linkCard.setOnClickListener(view -> viewModel.onLinkExchangeChosen());

		return v;
	}

}

package com.professor.zerion.android.contact.add.remote;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.contact.request.InviteLinksActivity;
import com.professor.zerion.android.contact.request.RequestsActivity;
import com.professor.zerion.android.contact.request.SendInviteController;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.db.DatabaseExecutor;
import org.zerionproject.core.api.identity.IdentityManager;

import java.util.concurrent.Executor;

import javax.annotation.Nullable;
import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.lifecycle.ViewModelProvider;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class SendInviteFragment extends BaseFragment
		implements SendInviteController.Listener {

	private static final String TAG = SendInviteFragment.class.getName();

	@Inject
	ViewModelProvider.Factory viewModelFactory;
	@Inject
	ContactRequestManager contactRequestManager;
	@Inject
	IdentityManager identityManager;
	@Inject
	@DatabaseExecutor
	Executor dbExecutor;

	private AddContactViewModel viewModel;
	@Nullable
	private SendInviteController controller;

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
		return inflater.inflate(R.layout.fragment_send_invite, container,
				false);
	}

	@Override
	public void onViewCreated(@NonNull View view,
			@Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);
		SendInviteController c = new SendInviteController(requireActivity(),
				view, contactRequestManager, identityManager, dbExecutor, this);
		controller = c;
		c.setReAdd(viewModel.isReAddExisting());
		String prefill = viewModel.takeInvitePrefill();
		if (prefill != null) c.setLink(prefill);
	}

	@Override
	public void onStart() {
		super.onStart();
		SendInviteController c = controller;
		if (c != null) c.start();
	}

	@Override
	public void onDestroyView() {
		controller = null;
		super.onDestroyView();
	}

	@Override
	public void onRequestSaved() {
		openRequests();
	}

	@Override
	public void onMutualAdded() {
		openRequests();
	}

	private void openRequests() {
		if (!isAdded()) return;
		startActivity(RequestsActivity.intent(requireContext(),
				RequestsActivity.TAB_SENT));
		finish();
	}

	@Override
	public void onScanChosen() {
		if (!isAdded()) return;
		viewModel.onQrExchangeChosen();
	}

	@Override
	public void onInviteLinksChosen() {
		if (!isAdded()) return;
		startActivity(new Intent(requireContext(),
				InviteLinksActivity.class));
	}
}

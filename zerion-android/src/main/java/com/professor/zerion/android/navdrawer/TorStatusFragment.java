package com.professor.zerion.android.navdrawer;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.professor.zerion.R;
import com.professor.zerion.android.fragment.BaseFragment;

import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;


import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;

import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static java.util.logging.Level.INFO;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class TorStatusFragment extends BaseFragment {

	private static final TransportId TOR_ID = TorConstants.ID;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private PluginViewModel viewModel;

	private ImageView torStatusIcon;
	private TextView torStatusText;
	private TextView torConnectionStatus;
	private TextView torOnionAddress;

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
	}

	@Nullable
	@Override
	public View onCreateView(LayoutInflater inflater,
			@Nullable ViewGroup container,
			@Nullable Bundle savedInstanceState) {
		View v = inflater.inflate(R.layout.fragment_tor_status, container, false);

		torStatusIcon = v.findViewById(R.id.torStatusIcon);
		torStatusText = v.findViewById(R.id.torStatusText);
		torConnectionStatus = v.findViewById(R.id.torConnectionStatus);
		torOnionAddress = v.findViewById(R.id.torOnionAddress);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(PluginViewModel.class);

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.network_status_title);

		viewModel.getPluginState(TOR_ID).observe(getViewLifecycleOwner(),
				state -> {
					if (state != null) {
						updateTorStatus(state);
					}
				});
	}

	private void updateTorStatus(org.briarproject.bramble.api.plugin.Plugin.State state) {
		if (state == null) {
			torStatusText.setText(R.string.disabled);
			torStatusText.setTextColor(0xFFFF5252);
			torConnectionStatus.setText(R.string.disabled);
			torOnionAddress.setText(R.string.not_available);
		} else if (state == org.briarproject.bramble.api.plugin.Plugin.State.DISABLED) {
			torStatusText.setText(R.string.disabled);
			torStatusText.setTextColor(0xFFFF5252);
			torConnectionStatus.setText(R.string.disabled);
			torOnionAddress.setText(R.string.not_available);
		} else if (state == org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE) {
			torStatusText.setText(R.string.connected);
			torStatusText.setTextColor(0xFF4CAF50);
			torConnectionStatus.setText(R.string.active);

			torOnionAddress.setText(R.string.tor_hidden_services_active);
		} else {
			torStatusText.setText(R.string.connecting);
			torStatusText.setTextColor(0xFFFFA726);
			torConnectionStatus.setText(R.string.connecting);
			torOnionAddress.setText(R.string.tor_hidden_services_connecting);
		}
	}


	@Override
	public String getUniqueTag() {
		return "TorStatusFragment";
	}
}

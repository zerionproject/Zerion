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

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class TorStatusFragment extends BaseFragment {

	public static final String TAG = "TorStatusFragment";

	private static final TransportId TOR_ID = TorConstants.ID;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private PluginViewModel viewModel;

	private ImageView torStatusIcon;
	private TextView torStatusText;
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

	@Override
	public void onStop() {
		super.onStop();
	}

	private void updateTorStatus(org.briarproject.bramble.api.plugin.Plugin.State state) {
		if (state == null || state == org.briarproject.bramble.api.plugin.Plugin.State.DISABLED) {
			torStatusText.setText(R.string.disabled);
			torStatusText.setTextColor(0xFFFF5252);
			torOnionAddress.setText(R.string.not_available);
			torStatusIcon.setColorFilter(0xFFFF5252);
		} else if (state == org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE) {
			torStatusText.setText(R.string.connected);
			torStatusText.setTextColor(0xFF4CAF50);
			torOnionAddress.setText(R.string.tor_hidden_services_active);
			torStatusIcon.setColorFilter(0xFF26B7F0);
		} else {
			torStatusText.setText(R.string.connecting);
			torStatusText.setTextColor(0xFFFFA726);
			torOnionAddress.setText(R.string.tor_hidden_services_connecting);
			torStatusIcon.setColorFilter(0xFFFFA726);
		}
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}

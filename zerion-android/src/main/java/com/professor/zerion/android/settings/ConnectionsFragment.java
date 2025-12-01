package com.professor.zerion.android.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.professor.zerion.R;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import static com.professor.zerion.android.AppModule.getAndroidComponent;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ConnectionsFragment extends Fragment {

	static final String PREF_KEY_TOR_ENABLE = "pref_key_tor_enable";
	static final String PREF_KEY_TOR_NETWORK = "pref_key_tor_network";
	static final String PREF_KEY_TOR_MOBILE_DATA = "pref_key_tor_mobile_data";
	static final String PREF_KEY_TOR_ONLY_WHEN_CHARGING = "pref_key_tor_only_when_charging";

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private SettingsViewModel viewModel;
	private ConnectionsManager connectionsManager;

	private SwitchMaterial torEnableSwitch;
	private MaterialCardView torNetworkCard;
	private TextView torNetworkValue;
	private SwitchMaterial torMobileSwitch;
	private SwitchMaterial torChargingSwitch;

	private String[] torNetworkEntries;
	private String[] torNetworkValues;

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(SettingsViewModel.class);
		connectionsManager = viewModel.connectionsManager;
	}

	@Nullable
	@Override
	public View onCreateView(@NonNull LayoutInflater inflater,
			@Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
		return inflater.inflate(R.layout.fragment_settings_connections, container, false);
	}

	@Override
	public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
		super.onViewCreated(view, savedInstanceState);

		torEnableSwitch = view.findViewById(R.id.tor_enable_switch);
		torNetworkCard = view.findViewById(R.id.tor_network_card);
		torNetworkValue = view.findViewById(R.id.tor_network_value);
		torMobileSwitch = view.findViewById(R.id.tor_mobile_data_switch);
		torChargingSwitch = view.findViewById(R.id.tor_only_when_charging_switch);

		torNetworkEntries = getResources().getStringArray(R.array.tor_network_setting_names);
		torNetworkValues = getResources().getStringArray(R.array.tor_network_setting_values);

		torEnableSwitch.setChecked(true);
		torEnableSwitch.setEnabled(false);

		torNetworkCard.setEnabled(false);
		torNetworkCard.setAlpha(0.6f);
		torNetworkCard.setOnClickListener(v -> showTorNetworkDialog());

		torMobileSwitch.setEnabled(false);

		torChargingSwitch.setEnabled(false);

		observeTorSettings();
	}

	private void observeTorSettings() {
		connectionsManager.torEnabled().observe(getViewLifecycleOwner(), enabled -> {
			torEnableSwitch.setChecked(enabled);
		});

		connectionsManager.torNetwork().observe(getViewLifecycleOwner(), value -> {
			updateTorNetworkDisplay(value);
		});

		connectionsManager.torMobile().observe(getViewLifecycleOwner(), enabled -> {
			torMobileSwitch.setChecked(enabled);
		});

		connectionsManager.torCharging().observe(getViewLifecycleOwner(), enabled -> {
			torChargingSwitch.setChecked(enabled);
		});
	}

	private void updateTorNetworkDisplay(String value) {
		for (int i = 0; i < torNetworkValues.length; i++) {
			if (torNetworkValues[i].equals(value)) {
				torNetworkValue.setText(torNetworkEntries[i]);
				break;
			}
		}
	}

	private void showTorNetworkDialog() {
		String currentValue = connectionsManager.torNetwork().getValue();
		if (currentValue == null) currentValue = "0";

		int selectedIndex = 0;
		for (int i = 0; i < torNetworkValues.length; i++) {
			if (torNetworkValues[i].equals(currentValue)) {
				selectedIndex = i;
				break;
			}
		}

		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.tor_network_setting)
				.setSingleChoiceItems(torNetworkEntries, selectedIndex, (dialog, which) -> {
					String newValue = torNetworkValues[which];
					connectionsManager.torStore.putString(PREF_KEY_TOR_NETWORK, newValue);
					updateTorNetworkDisplay(newValue);
					dialog.dismiss();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.network_settings_title);
	}

}

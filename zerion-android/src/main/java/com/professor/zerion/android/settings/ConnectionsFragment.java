package com.professor.zerion.android.settings;

import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.widget.Toast;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.professor.zerion.R;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.plugin.tor.B4OnionRotation;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_MOBILE;
import static org.briarproject.bramble.api.plugin.TorConstants.PREF_TOR_NETWORK;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ConnectionsFragment extends Fragment {

	static final String PREF_KEY_TOR_NETWORK = "pref_key_tor_network";
	static final String PREF_KEY_TOR_MOBILE_DATA = "pref_key_tor_mobile_data";
	static final String PREF_KEY_ORBOT_ENABLED = "pref_key_orbot_enabled";
	static final String PREF_KEY_ORBOT_HOST = "pref_key_orbot_host";
	static final String PREF_KEY_ORBOT_PORT = "pref_key_orbot_port";

	private static final String DEFAULT_ORBOT_HOST = "127.0.0.1";
	private static final int DEFAULT_ORBOT_PORT = 9050;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	B4OnionRotation b4OnionRotation;

	@Inject
	@IoExecutor
	Executor ioExecutor;

	private SettingsViewModel viewModel;
	private ConnectionsManager connectionsManager;

	private View torNetworkCard;
	private TextView torNetworkValue;
	private SwitchMaterial orbotProxySwitch;
	private View orbotSettingsCard;
	private TextView orbotProxyValue;
	private View rotateOnionCard;
	private View forceCompleteRotationCard;

	private String[] torNetworkEntries;
	private String[] torNetworkValues;
	private String orbotHost = DEFAULT_ORBOT_HOST;
	private int orbotPort = DEFAULT_ORBOT_PORT;

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

		torNetworkCard = view.findViewById(R.id.tor_network_card);
		torNetworkValue = view.findViewById(R.id.tor_network_value);
		orbotProxySwitch = view.findViewById(R.id.orbot_proxy_switch);
		orbotSettingsCard = view.findViewById(R.id.orbot_settings_card);
		orbotProxyValue = view.findViewById(R.id.orbot_proxy_value);
		rotateOnionCard = view.findViewById(R.id.rotate_onion_card);
		forceCompleteRotationCard =
				view.findViewById(R.id.force_complete_rotation_card);

		if (rotateOnionCard != null) {
			rotateOnionCard.setOnClickListener(v -> showRotateOnionDialog());
		}
		if (forceCompleteRotationCard != null) {
			forceCompleteRotationCard.setOnClickListener(
					v -> showForceCompleteRotationDialog());
		}

		torNetworkEntries = getResources().getStringArray(R.array.tor_network_setting_names);
		torNetworkValues = getResources().getStringArray(R.array.tor_network_setting_values);
		torNetworkCard.setOnClickListener(v -> showTorNetworkDialog());
		orbotProxySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (buttonView.isPressed()) {
				connectionsManager.torStore.putBoolean(PREF_KEY_ORBOT_ENABLED, isChecked);
				updateOrbotSettingsVisibility(isChecked);
			}
		});
		orbotSettingsCard.setOnClickListener(v -> showOrbotSettingsDialog());

		observeSettings();
	}

	private void observeSettings() {
		connectionsManager.torNetwork().observe(getViewLifecycleOwner(), value -> {
			updateTorNetworkDisplay(value);
		});

		connectionsManager.orbotEnabled().observe(getViewLifecycleOwner(), enabled -> {
			orbotProxySwitch.setOnCheckedChangeListener(null);
			orbotProxySwitch.setChecked(enabled);
			updateOrbotSettingsVisibility(enabled);
			orbotProxySwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
				if (buttonView.isPressed()) {
					connectionsManager.torStore.putBoolean(PREF_KEY_ORBOT_ENABLED, isChecked);
					updateOrbotSettingsVisibility(isChecked);
				}
			});
		});

		connectionsManager.orbotHost().observe(getViewLifecycleOwner(), host -> {
			orbotHost = host != null ? host : DEFAULT_ORBOT_HOST;
			updateOrbotProxyDisplay();
		});

		connectionsManager.orbotPort().observe(getViewLifecycleOwner(), port -> {
			orbotPort = port != null ? port : DEFAULT_ORBOT_PORT;
			updateOrbotProxyDisplay();
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

	private void updateOrbotSettingsVisibility(boolean visible) {
		orbotSettingsCard.setVisibility(visible ? View.VISIBLE : View.GONE);
	}

	private void updateOrbotProxyDisplay() {
		orbotProxyValue.setText(orbotHost + ":" + orbotPort);
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
					connectionsManager.torStore.putString(PREF_TOR_NETWORK, newValue);
					updateTorNetworkDisplay(newValue);
					dialog.dismiss();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showOrbotSettingsDialog() {
		Context context = requireContext();

		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		int padding = (int) (16 * getResources().getDisplayMetrics().density);
		layout.setPadding(padding, padding, padding, 0);
		TextView hostLabel = new TextView(context);
		hostLabel.setText(R.string.orbot_host_label);
		layout.addView(hostLabel);

		EditText hostInput = new EditText(context);
		hostInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		hostInput.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
		hostInput.setPrivateImeOptions("nm");
		hostInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
		hostInput.setText(orbotHost);
		hostInput.setHint(DEFAULT_ORBOT_HOST);
		layout.addView(hostInput);
		TextView portLabel = new TextView(context);
		portLabel.setText(R.string.orbot_port_label);
		LinearLayout.LayoutParams portLabelParams = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.WRAP_CONTENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		portLabelParams.topMargin = padding;
		portLabel.setLayoutParams(portLabelParams);
		layout.addView(portLabel);

		EditText portInput = new EditText(context);
		portInput.setInputType(InputType.TYPE_CLASS_NUMBER);
		portInput.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
		portInput.setPrivateImeOptions("nm");
		portInput.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
		portInput.setText(String.valueOf(orbotPort));
		portInput.setHint(String.valueOf(DEFAULT_ORBOT_PORT));
		layout.addView(portInput);

		new MaterialAlertDialogBuilder(context)
				.setTitle(R.string.orbot_proxy_settings)
				.setView(layout)
				.setPositiveButton(R.string.ok, (dialog, which) -> {
					String newHost = hostInput.getText().toString().trim();
					if (newHost.isEmpty()) newHost = DEFAULT_ORBOT_HOST;

					int newPort = DEFAULT_ORBOT_PORT;
					try {
						newPort = Integer.parseInt(portInput.getText().toString().trim());
						if (newPort < 1 || newPort > 65535) newPort = DEFAULT_ORBOT_PORT;
					} catch (NumberFormatException e) {
					}

					connectionsManager.torStore.putString(PREF_KEY_ORBOT_HOST, newHost);
					connectionsManager.torStore.putInt(PREF_KEY_ORBOT_PORT, newPort);

					orbotHost = newHost;
					orbotPort = newPort;
					updateOrbotProxyDisplay();
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.network_settings_title);
		refreshForceCompleteVisibility();
	}

	private void refreshForceCompleteVisibility() {
		if (forceCompleteRotationCard == null) return;
		ioExecutor.execute(() -> {
			B4OnionRotation.RotationPhase phase;
			try {
				phase = b4OnionRotation.getPhase();
			} catch (DbException e) {
				phase = B4OnionRotation.RotationPhase.IDLE;
			}
			final boolean show =
					phase == B4OnionRotation.RotationPhase.ANNOUNCING;
			if (getActivity() == null) return;
			requireActivity().runOnUiThread(() -> {
				if (forceCompleteRotationCard == null) return;
				forceCompleteRotationCard.setVisibility(
						show ? View.VISIBLE : View.GONE);
			});
		});
	}

	private void showRotateOnionDialog() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_rotate_onion_confirm_title)
				.setMessage(R.string.pref_rotate_onion_confirm_message)
				.setPositiveButton(R.string.pref_rotate_onion_confirm_action,
						(dialog, which) -> triggerRotation())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showForceCompleteRotationDialog() {
		new MaterialAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_force_complete_rotation_confirm_title)
				.setMessage(
						R.string.pref_force_complete_rotation_confirm_message)
				.setPositiveButton(
						R.string.pref_force_complete_rotation_confirm_action,
						(dialog, which) -> triggerForceCompleteRotation())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void triggerForceCompleteRotation() {
		Context appContext = requireContext().getApplicationContext();
		ioExecutor.execute(() -> {
			boolean promoted = false;
			try {
				promoted = b4OnionRotation.forceCompleteRotation();
			} catch (DbException ignored) {
			}
			final boolean promotedFinal = promoted;
			if (getActivity() == null) return;
			requireActivity().runOnUiThread(() -> {
				if (getContext() == null) return;
				Toast.makeText(appContext, promotedFinal
								? R.string.pref_force_complete_rotation_done
								: R.string.pref_force_complete_rotation_not_announcing,
						Toast.LENGTH_LONG).show();
				refreshForceCompleteVisibility();
				new ViewModelProvider(requireActivity(), viewModelFactory)
						.get(com.professor.zerion.android.navdrawer
								.PluginViewModel.class)
						.refreshTorState();
			});
		});
	}

	private void triggerRotation() {
		Context appContext = requireContext().getApplicationContext();
		ioExecutor.execute(() -> {
			String newOnion = null;
			boolean success = false;
			try {
				b4OnionRotation.forceRotate();
				newOnion = b4OnionRotation.getAliceNextOnion();
				success = true;
			} catch (DbException ignored) {
			}
			boolean ok = success;
			String displayOnion = newOnion;
			if (getActivity() == null) return;
			requireActivity().runOnUiThread(() -> {
				if (getContext() == null) return;
				if (ok && displayOnion != null) {
					new ViewModelProvider(requireActivity(), viewModelFactory)
							.get(com.professor.zerion.android.navdrawer
									.PluginViewModel.class)
							.refreshTorState();
					new MaterialAlertDialogBuilder(requireContext())
							.setTitle(R.string.pref_rotate_onion_success_title)
							.setMessage(getString(
									R.string.pref_rotate_onion_success_message,
									displayOnion + ".onion"))
							.setPositiveButton(android.R.string.ok, null)
							.show();
				} else {
					Toast.makeText(appContext,
							ok ? R.string.pref_rotate_onion_started
									: R.string.pref_rotate_onion_failed,
							Toast.LENGTH_LONG).show();
				}
				refreshForceCompleteVisibility();
			});
		});
	}

}

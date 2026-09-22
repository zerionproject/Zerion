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

import com.professor.zerion.android.security.SecureAlertDialogBuilder;
import com.google.android.material.switchmaterial.SwitchMaterial;
import com.professor.zerion.R;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.PluginManager;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import com.professor.zerion.android.mesh.MeshController;
import com.professor.zerion.android.navdrawer.PluginViewModel;

import java.util.concurrent.Executor;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import static com.professor.zerion.android.TestingConstants.IS_DEBUG_BUILD;
import static com.professor.zerion.android.AppModule.getAndroidComponent;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_CUSTOM_BRIDGES;
import static org.zerionproject.core.api.plugin.TorConstants.PREF_TOR_NETWORK;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ConnectionsFragment extends Fragment {

	static final String PREF_KEY_TOR_NETWORK = "pref_key_tor_network";


	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	B4OnionRotation b4OnionRotation;

	@Inject
	@IoExecutor
	Executor ioExecutor;

	@Inject
	MeshController meshController;
	@Inject
	org.zerionproject.core.api.settings.SettingsManager settingsManager;
	@Inject
	@org.zerionproject.core.api.db.DatabaseExecutor
	java.util.concurrent.Executor dbExecutor;

	@Inject
	PluginManager pluginManager;

	private SettingsViewModel viewModel;
	private ConnectionsManager connectionsManager;
	private PluginViewModel pluginViewModel;

	private SwitchMaterial i2pSwitch;
	private SwitchMaterial i2pDirectReseedSwitch;
	private View i2pDirectReseedCard;
	private SwitchMaterial meshSwitch;
	private SwitchMaterial offlineModeSwitch;
	private boolean enableMeshForOffline = false;

	private final ActivityResultLauncher<String[]> meshPermissionLauncher =
			registerForActivityResult(new RequestMultiplePermissions(),
					result -> {
						boolean allGranted = !result.isEmpty();
						for (Boolean granted : result.values()) {
							if (granted == null || !granted) allGranted = false;
						}
						onMeshPermissionResult(allGranted);
					});

	private View torNetworkCard;
	private TextView torNetworkValue;
	private View customBridgesCard;
	private TextView customBridgesValue;
	private View rotateOnionCard;
	private View forceCompleteRotationCard;

	private String[] torNetworkEntries;
	private String[] torNetworkValues;

	@Override
	public void onAttach(@NonNull Context context) {
		super.onAttach(context);
		getAndroidComponent(context).inject(this);
		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(SettingsViewModel.class);
		connectionsManager = viewModel.connectionsManager;
		pluginViewModel = new ViewModelProvider(requireActivity(),
				viewModelFactory).get(PluginViewModel.class);
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
		customBridgesCard = view.findViewById(R.id.custom_bridges_card);
		customBridgesValue = view.findViewById(R.id.custom_bridges_value);
		customBridgesCard.setOnClickListener(v -> showCustomBridgesDialog());
		i2pSwitch = view.findViewById(R.id.i2p_switch);
		i2pSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (buttonView.isPressed()) onI2pToggle(isChecked);
		});

		i2pDirectReseedCard = view.findViewById(R.id.i2p_direct_reseed_card);
		i2pDirectReseedSwitch = view.findViewById(R.id.i2p_direct_reseed_switch);
		i2pDirectReseedSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (buttonView.isPressed()) onDirectReseedToggle(isChecked);
		});
		if (!IS_DEBUG_BUILD) {
			view.findViewById(R.id.i2p_card).setVisibility(View.GONE);
			i2pDirectReseedCard.setVisibility(View.GONE);
		}

		meshSwitch = view.findViewById(R.id.mesh_switch);
		meshSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (buttonView.isPressed()) onMeshToggle(isChecked);
		});
		refreshMeshSwitch();

		offlineModeSwitch = view.findViewById(R.id.offline_mode_switch);
		offlineModeSwitch.setChecked(pluginManager.isOfflineMode());
		offlineModeSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
			if (buttonView.isPressed()) onOfflineModeToggle(isChecked);
		});

		setupBackgroundConnections(view);

		observeSettings();
	}

	private void setupBackgroundConnections(View view) {
		android.widget.RadioGroup group = view.findViewById(R.id.bg_mode_group);
		View warning = view.findViewById(R.id.bg_conn_warning_card);
		com.professor.zerion.android.settings.BackgroundConnections.Mode current =
				com.professor.zerion.android.settings.BackgroundConnections.getMode(
						com.professor.zerion.android.AppModule.getUiPrefs());
		int checkedId;
		switch (current) {
			case WHILE_OPEN:
				checkedId = R.id.bg_mode_open;
				break;
			case PAUSED:
				checkedId = R.id.bg_mode_paused;
				break;
			default:
				checkedId = R.id.bg_mode_always;
		}
		group.check(checkedId);
		warning.setVisibility(current ==
				com.professor.zerion.android.settings.BackgroundConnections
						.Mode.ALWAYS ? View.GONE : View.VISIBLE);
		group.setOnCheckedChangeListener((g, id) -> {
			com.professor.zerion.android.settings.BackgroundConnections.Mode mode;
			if (id == R.id.bg_mode_open) {
				mode = com.professor.zerion.android.settings
						.BackgroundConnections.Mode.WHILE_OPEN;
			} else if (id == R.id.bg_mode_paused) {
				mode = com.professor.zerion.android.settings
						.BackgroundConnections.Mode.PAUSED;
			} else {
				mode = com.professor.zerion.android.settings
						.BackgroundConnections.Mode.ALWAYS;
			}
			com.professor.zerion.android.settings.BackgroundConnections.setMode(
					com.professor.zerion.android.AppModule.getUiPrefs(), mode);
			warning.setVisibility(mode ==
					com.professor.zerion.android.settings.BackgroundConnections
							.Mode.ALWAYS ? View.GONE : View.VISIBLE);
			applyBackgroundMode(mode);
		});
	}

	private void applyBackgroundMode(
			com.professor.zerion.android.settings.BackgroundConnections.Mode mode) {
		boolean paused = mode == com.professor.zerion.android.settings
				.BackgroundConnections.Mode.PAUSED;
		pluginManager.setConnectionsPaused(paused);
		if (paused) {
			return;
		}
		android.content.Context ctx = requireContext().getApplicationContext();
		android.content.Intent i = new android.content.Intent(ctx,
				com.professor.zerion.android.ZerionService.class);
		try {
			ctx.startService(i);
		} catch (Exception ignored) {
		}
	}

	private void onI2pToggle(boolean enable) {
		if (!enable) {
			pluginViewModel.enableTransport(I2pConstants.ID, false);
			return;
		}
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.i2p_enable_warning_title)
				.setMessage(R.string.i2p_enable_warning_message)
				.setPositiveButton(R.string.i2p_enable_warning_confirm,
						(d, w) -> pluginViewModel.enableTransport(
								I2pConstants.ID, true))
				.setNegativeButton(R.string.cancel,
						(d, w) -> i2pSwitch.setChecked(false))
				.setOnCancelListener(d -> i2pSwitch.setChecked(false))
				.show();
	}

	private void onDirectReseedToggle(boolean enable) {
		if (!enable) {
			pluginViewModel.setDirectReseed(false);
			return;
		}
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.i2p_direct_reseed_warning_title)
				.setMessage(R.string.i2p_direct_reseed_warning_message)
				.setPositiveButton(R.string.i2p_direct_reseed_warning_confirm,
						(d, w) -> pluginViewModel.setDirectReseed(true))
				.setNegativeButton(R.string.cancel,
						(d, w) -> i2pDirectReseedSwitch.setChecked(false))
				.setOnCancelListener(d -> i2pDirectReseedSwitch.setChecked(false))
				.show();
	}

	private void onMeshToggle(boolean enable) {
		if (!enable) {
			meshController.setMeshEnabled(false);
			return;
		}
		if (!MeshController.isSupported()) {
			Toast.makeText(requireContext(), R.string.mesh_unsupported,
					Toast.LENGTH_LONG).show();
			meshSwitch.setChecked(false);
			return;
		}
		if (MeshController.hasPermissions(requireContext())) {
			meshController.setMeshEnabled(true);
		} else {
			meshPermissionLauncher.launch(MeshController.requiredPermissions());
		}
	}

	private void onMeshPermissionResult(boolean allGranted) {
		boolean forOffline = enableMeshForOffline;
		enableMeshForOffline = false;
		if (allGranted) {
			meshController.setMeshEnabled(true);
			if (meshSwitch != null) meshSwitch.setChecked(true);
			if (forOffline) pluginManager.setOfflineMode(true);
		} else {
			Toast.makeText(requireContext(), R.string.mesh_permission_denied,
					Toast.LENGTH_LONG).show();
			meshSwitch.setChecked(false);
			if (forOffline) offlineModeSwitch.setChecked(false);
		}
	}

	private void onOfflineModeToggle(boolean enable) {
		if (!enable) {
			pluginManager.setOfflineMode(false);
			return;
		}
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.offline_mode_confirm_title)
				.setMessage(R.string.offline_mode_confirm_message)
				.setPositiveButton(R.string.offline_mode_confirm_button,
						(d, w) -> enableOfflineMode())
				.setNegativeButton(R.string.cancel,
						(d, w) -> offlineModeSwitch.setChecked(false))
				.setOnCancelListener(d -> offlineModeSwitch.setChecked(false))
				.show();
	}

	private void enableOfflineMode() {
		if (!MeshController.isSupported()) {
			Toast.makeText(requireContext(), R.string.mesh_unsupported,
					Toast.LENGTH_LONG).show();
			offlineModeSwitch.setChecked(false);
			return;
		}
		if (!MeshController.hasPermissions(requireContext())) {
			enableMeshForOffline = true;
			meshPermissionLauncher.launch(MeshController.requiredPermissions());
			return;
		}
		meshController.setMeshEnabled(true);
		if (meshSwitch != null) meshSwitch.setChecked(true);
		pluginManager.setOfflineMode(true);
	}

	private void refreshMeshSwitch() {
		ioExecutor.execute(() -> {
			boolean enabled;
			try {
				enabled = meshController.isMeshEnabled();
			} catch (DbException e) {
				enabled = false;
			}
			boolean finalEnabled = enabled;
			if (getActivity() == null) return;
			requireActivity().runOnUiThread(() -> {
				if (meshSwitch == null) return;
				meshSwitch.setOnCheckedChangeListener(null);
				meshSwitch.setChecked(finalEnabled);
				meshSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
					if (buttonView.isPressed()) onMeshToggle(isChecked);
				});
			});
		});
	}

	private void observeSettings() {
		connectionsManager.torNetwork().observe(getViewLifecycleOwner(), value -> {
			updateTorNetworkDisplay(value);
		});

		connectionsManager.customBridges().observe(getViewLifecycleOwner(),
				this::updateCustomBridgesDisplay);

		pluginViewModel.getPluginEnabledSetting(I2pConstants.ID).observe(
				getViewLifecycleOwner(), enabled -> {
					i2pSwitch.setOnCheckedChangeListener(null);
					i2pSwitch.setChecked(Boolean.TRUE.equals(enabled));
					i2pSwitch.setOnCheckedChangeListener(
							(buttonView, isChecked) -> {
								if (buttonView.isPressed()) {
									onI2pToggle(isChecked);
								}
							});
					i2pDirectReseedCard.setVisibility(
							IS_DEBUG_BUILD && Boolean.TRUE.equals(enabled)
									? View.VISIBLE : View.GONE);
				});

		pluginViewModel.getI2pDirectReseedSetting().observe(
				getViewLifecycleOwner(), enabled -> {
					i2pDirectReseedSwitch.setOnCheckedChangeListener(null);
					i2pDirectReseedSwitch.setChecked(
							Boolean.TRUE.equals(enabled));
					i2pDirectReseedSwitch.setOnCheckedChangeListener(
							(buttonView, isChecked) -> {
								if (buttonView.isPressed()) {
									onDirectReseedToggle(isChecked);
								}
							});
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

		new SecureAlertDialogBuilder(requireContext())
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

	private void showCustomBridgesDialog() {
		Context context = requireContext();
		LinearLayout layout = new LinearLayout(context);
		layout.setOrientation(LinearLayout.VERTICAL);
		int padding = (int) (16 * getResources().getDisplayMetrics().density);
		layout.setPadding(padding, padding, padding, 0);

		TextView message = new TextView(context);
		message.setText(R.string.tor_custom_bridges_dialog_message);
		message.setTextSize(13);
		layout.addView(message);

		EditText input = new EditText(context);
		input.setInputType(InputType.TYPE_CLASS_TEXT
				| InputType.TYPE_TEXT_FLAG_MULTI_LINE
				| InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
		input.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
		input.setPrivateImeOptions("nm");
		input.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
		input.setHint(R.string.tor_custom_bridges_hint);
		input.setMinLines(3);
		input.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
		String current = connectionsManager.customBridges().getValue();
		if (current != null) input.setText(current);
		LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT);
		lp.topMargin = padding;
		input.setLayoutParams(lp);
		layout.addView(input);

		new SecureAlertDialogBuilder(context)
				.setTitle(R.string.tor_custom_bridges_title)
				.setView(layout)
				.setPositiveButton(R.string.ok, (dialog, which) -> {
					String[] lines =
							input.getText().toString().split("\\r?\\n");
					StringBuilder sb = new StringBuilder();
					for (String line : lines) {
						String trimmed = line.trim();
						if (trimmed.isEmpty()) continue;
						if (sb.length() > 0) sb.append('\n');
						sb.append(trimmed);
					}
					String value = sb.toString();
					connectionsManager.torStore.putString(
							PREF_TOR_CUSTOM_BRIDGES, value);
					updateCustomBridgesDisplay(value);
				})
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void updateCustomBridgesDisplay(@Nullable String value) {
		if (customBridgesValue == null) return;
		int count = 0;
		if (value != null) {
			for (String line : value.split("\\r?\\n")) {
				if (!line.trim().isEmpty()) count++;
			}
		}
		if (count == 0) {
			customBridgesValue.setText(
					R.string.tor_custom_bridges_summary_none);
		} else {
			customBridgesValue.setText(getResources().getQuantityString(
					R.plurals.tor_custom_bridges_summary_set, count, count));
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.network_settings_title);
		refreshForceCompleteVisibility();
		if (offlineModeSwitch != null) {
			offlineModeSwitch.setChecked(pluginManager.isOfflineMode());
		}
		refreshMeshSwitch();
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
		new SecureAlertDialogBuilder(requireContext())
				.setTitle(R.string.pref_rotate_onion_confirm_title)
				.setMessage(R.string.pref_rotate_onion_confirm_message)
				.setPositiveButton(R.string.pref_rotate_onion_confirm_action,
						(dialog, which) -> triggerRotation())
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void showForceCompleteRotationDialog() {
		new SecureAlertDialogBuilder(requireContext())
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
				success = newOnion != null;
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
					new SecureAlertDialogBuilder(requireContext())
							.setTitle(R.string.pref_rotate_onion_success_title)
							.setMessage(getString(
									R.string.pref_rotate_onion_success_message,
									displayOnion + ".onion"))
							.setPositiveButton(android.R.string.ok, null)
							.show();
				} else {
					Toast.makeText(appContext,
							R.string.pref_rotate_onion_failed,
							Toast.LENGTH_LONG).show();
				}
				refreshForceCompleteVisibility();
			});
		});
	}

}

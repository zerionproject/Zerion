package com.professor.zerion.android.navdrawer;

import android.content.Context;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.professor.zerion.R;
import com.professor.zerion.android.fragment.BaseFragment;

import org.zerionproject.core.api.plugin.I2pConstants;
import org.zerionproject.core.api.plugin.Plugin;
import org.zerionproject.core.api.plugin.TorConstants;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.plugin.tor.B4OnionRotation;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import javax.inject.Inject;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import static com.professor.zerion.android.AppModule.getAndroidComponent;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class TorStatusFragment extends BaseFragment {

	public static final String TAG = "TorStatusFragment";

	private static final TransportId TOR_ID = TorConstants.ID;
	private static final TransportId I2P_ID = I2pConstants.ID;

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	@Inject
	com.professor.zerion.android.mesh.MeshController meshController;

	@Inject
	org.zerionproject.core.api.plugin.PluginManager pluginManager;

	private final android.os.Handler meshHandler =
			new android.os.Handler(android.os.Looper.getMainLooper());

	private PluginViewModel viewModel;

	private ImageView torStatusIcon;
	private TextView torStatusText;
	private TextView torOnionAddress;
	private LinearLayout onionCard;
	private TextView onionAddressValue;
	private MaterialButton onionCopyButton;
	private LinearLayout rotationCard;
	private TextView rotationPendingValue;
	private LinearLayout i2pCard;
	private ImageView i2pStatusIcon;
	private TextView i2pStatusText;
	private TextView internetStatusText;
	private LinearLayout meshStatusCard;
	private TextView meshStatusText;
	private TextView offlineModeBanner;

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
		onionCard = v.findViewById(R.id.onionCard);
		onionAddressValue = v.findViewById(R.id.onionAddressValue);
		onionCopyButton = v.findViewById(R.id.onionCopyButton);
		rotationCard = v.findViewById(R.id.rotationCard);
		rotationPendingValue = v.findViewById(R.id.rotationPendingValue);
		i2pCard = v.findViewById(R.id.i2pCard);
		i2pStatusIcon = v.findViewById(R.id.i2pStatusIcon);
		i2pStatusText = v.findViewById(R.id.i2pStatusText);
		internetStatusText = v.findViewById(R.id.internetStatusText);
		meshStatusCard = v.findViewById(R.id.meshStatusCard);
		meshStatusText = v.findViewById(R.id.meshStatusText);
		offlineModeBanner = v.findViewById(R.id.offlineModeBanner);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(PluginViewModel.class);

		onionCopyButton.setOnClickListener(view -> {
			com.professor.zerion.android.util.Haptics.tap(view);
			copyOnionToClipboard();
		});

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

		viewModel.getLocalOnion().observe(getViewLifecycleOwner(), onion -> {
			if (onion != null && !onion.isEmpty()) {
				String full = onion + ".onion";
				onionAddressValue.setText(full);
				onionCard.setVisibility(View.VISIBLE);
			} else {
				onionCard.setVisibility(View.GONE);
			}
		});

		viewModel.getRotationPhase().observe(getViewLifecycleOwner(), phase ->
				updateRotationCard(phase,
						viewModel.getRotationPendingOnion().getValue()));

		viewModel.getRotationPendingOnion().observe(getViewLifecycleOwner(),
				pending -> updateRotationCard(
						viewModel.getRotationPhase().getValue(), pending));

		viewModel.getPluginEnabledSetting(I2P_ID).observe(
				getViewLifecycleOwner(), enabled ->
						i2pCard.setVisibility(Boolean.TRUE.equals(enabled)
								? View.VISIBLE : View.GONE));

		viewModel.getPluginState(I2P_ID).observe(getViewLifecycleOwner(),
				state -> {
					if (state != null) updateI2pStatus(state);
				});

		viewModel.getNetworkStatus().observe(getViewLifecycleOwner(),
				status -> {
					Context ctx = requireContext();
					boolean online = status != null && status.isConnected();
					internetStatusText.setText(
							online ? R.string.online : R.string.offline);
					internetStatusText.setTextColor(ContextCompat.getColor(ctx,
							online ? R.color.zerion_success
									: R.color.zerion_text_secondary));
				});

		viewModel.refreshTorState();
		meshHandler.post(meshPoll);

		boolean offline = pluginManager.isOfflineMode();
		offlineModeBanner.setVisibility(offline ? View.VISIBLE : View.GONE);
	}

	@Override
	public void onStop() {
		super.onStop();
		meshHandler.removeCallbacks(meshPoll);
	}

	private final Runnable meshPoll = new Runnable() {
		@Override
		public void run() {
			updateMeshCard();
			meshHandler.postDelayed(this, 3000);
		}
	};

	private void updateMeshCard() {
		if (meshStatusCard == null) return;
		if (!meshController.isRunning()) {
			meshStatusCard.setVisibility(View.GONE);
			return;
		}
		meshStatusCard.setVisibility(View.VISIBLE);
		int n = meshController.getPeerCount();
		meshStatusText.setText(n == 0
				? getString(R.string.mesh_status_searching)
				: getResources().getQuantityString(
						R.plurals.mesh_status_active, n, n));
	}

	private void updateI2pStatus(Plugin.State state) {
		Context ctx = requireContext();
		if (pluginManager.isOfflineMode()) {
			i2pStatusText.setText(R.string.network_status_off);
			i2pStatusText.setTextColor(
					ContextCompat.getColor(ctx, R.color.zerion_text_secondary));
			i2pStatusIcon.setColorFilter(
					ContextCompat.getColor(ctx, R.color.zerion_text_secondary));
			return;
		}
		if (state == Plugin.State.DISABLED) {
			i2pStatusText.setText(R.string.disabled);
			i2pStatusText.setTextColor(
					ContextCompat.getColor(ctx, R.color.zerion_destructive));
			i2pStatusIcon.setColorFilter(
					ContextCompat.getColor(ctx, R.color.zerion_destructive));
		} else if (state == Plugin.State.ACTIVE) {
			i2pStatusText.setText(R.string.connected);
			i2pStatusText.setTextColor(
					ContextCompat.getColor(ctx, R.color.zerion_success));
			i2pStatusIcon.setColorFilter(
					ContextCompat.getColor(ctx, R.color.zerion_primary_accent));
		} else {
			i2pStatusText.setText(R.string.connecting);
			i2pStatusText.setTextColor(
					ContextCompat.getColor(ctx, R.color.zerion_warning));
			i2pStatusIcon.setColorFilter(
					ContextCompat.getColor(ctx, R.color.zerion_warning));
		}
	}

	private void updateRotationCard(
			@Nullable B4OnionRotation.RotationPhase phase,
			@Nullable String pendingOnion) {
		if (phase == B4OnionRotation.RotationPhase.ANNOUNCING
				&& pendingOnion != null && !pendingOnion.isEmpty()) {
			rotationPendingValue.setText(pendingOnion + ".onion");
			rotationCard.setVisibility(View.VISIBLE);
		} else {
			rotationCard.setVisibility(View.GONE);
		}
	}

	private void copyOnionToClipboard() {
		Context ctx = requireContext();
		String onion = onionAddressValue.getText().toString();
		if (onion.isEmpty()) return;
		com.professor.zerion.android.util.SecureClipboard.copy(ctx,
				ctx.getString(R.string.tor_onion_share_label), onion);
		Toast.makeText(ctx, R.string.tor_onion_copied, Toast.LENGTH_SHORT).show();
	}

	private void updateTorStatus(
			org.zerionproject.core.api.plugin.Plugin.State state) {
		Context ctx = requireContext();
		if (pluginManager.isOfflineMode()) {
			torStatusText.setText(R.string.network_status_off);
			torStatusText.setTextColor(
					ContextCompat.getColor(ctx, R.color.zerion_text_secondary));
			torOnionAddress.setText(R.string.offline_mode_transport_off);
			torStatusIcon.setColorFilter(
					ContextCompat.getColor(ctx, R.color.zerion_text_secondary));
			return;
		}
		if (state == null
				|| state == org.zerionproject.core.api.plugin.Plugin.State.DISABLED) {
			torStatusText.setText(R.string.disabled);
			torStatusText.setTextColor(
					ContextCompat.getColor(ctx, R.color.zerion_destructive));
			torOnionAddress.setText(R.string.not_available);
			torStatusIcon.setColorFilter(
					ContextCompat.getColor(ctx, R.color.zerion_destructive));
		} else if (state
				== org.zerionproject.core.api.plugin.Plugin.State.ACTIVE) {
			torStatusText.setText(R.string.connected);
			torStatusText.setTextColor(
					ContextCompat.getColor(ctx, R.color.zerion_success));
			torOnionAddress.setText(R.string.tor_hidden_services_active);
			torStatusIcon.setColorFilter(
					ContextCompat.getColor(ctx, R.color.zerion_primary_accent));
		} else {
			torStatusText.setText(R.string.connecting);
			torStatusText.setTextColor(
					ContextCompat.getColor(ctx, R.color.zerion_warning));
			torOnionAddress.setText(R.string.tor_hidden_services_connecting);
			torStatusIcon.setColorFilter(
					ContextCompat.getColor(ctx, R.color.zerion_warning));
		}
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}

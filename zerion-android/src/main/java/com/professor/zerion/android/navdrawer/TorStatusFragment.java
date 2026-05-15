package com.professor.zerion.android.navdrawer;

import android.content.ClipData;
import android.content.ClipboardManager;
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

import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.plugin.tor.B4OnionRotation;
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

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private PluginViewModel viewModel;

	private ImageView torStatusIcon;
	private TextView torStatusText;
	private TextView torOnionAddress;
	private LinearLayout onionCard;
	private TextView onionAddressValue;
	private MaterialButton onionCopyButton;
	private LinearLayout rotationCard;
	private TextView rotationPendingValue;

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

		viewModel.refreshTorState();
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
		ClipboardManager cm = (ClipboardManager)
				ctx.getSystemService(Context.CLIPBOARD_SERVICE);
		if (cm == null) return;
		ClipData clip = ClipData.newPlainText(
				ctx.getString(R.string.tor_onion_share_label), onion);
		cm.setPrimaryClip(clip);
		Toast.makeText(ctx, R.string.tor_onion_copied, Toast.LENGTH_SHORT).show();
	}

	private void updateTorStatus(
			org.briarproject.bramble.api.plugin.Plugin.State state) {
		Context ctx = requireContext();
		if (state == null
				|| state == org.briarproject.bramble.api.plugin.Plugin.State.DISABLED) {
			torStatusText.setText(R.string.disabled);
			torStatusText.setTextColor(
					ContextCompat.getColor(ctx, R.color.zerion_destructive));
			torOnionAddress.setText(R.string.not_available);
			torStatusIcon.setColorFilter(
					ContextCompat.getColor(ctx, R.color.zerion_destructive));
		} else if (state
				== org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE) {
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

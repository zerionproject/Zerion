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
import com.professor.zerion.android.network.TorStatusMonitor;
import com.professor.zerion.android.view.NetworkGraphView;

import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.Locale;

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

	@Inject
	TorStatusMonitor torStatusMonitor;

	private PluginViewModel viewModel;

	private ImageView torStatusIcon;
	private TextView torStatusText;
	private TextView torOnionAddress;
	private NetworkGraphView networkGraph;
	private TextView totalDownload;
	private TextView totalUpload;
	private ImageView clearStatsButton;

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
		networkGraph = v.findViewById(R.id.networkGraph);
		totalDownload = v.findViewById(R.id.totalDownload);
		totalUpload = v.findViewById(R.id.totalUpload);
		clearStatsButton = v.findViewById(R.id.clearStatsButton);

		clearStatsButton.setOnClickListener(view -> {
			torStatusMonitor.resetStatistics();
			networkGraph.clearData();
		});

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(PluginViewModel.class);

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.network_status_title);

		torStatusMonitor.startMonitoring();

		viewModel.getPluginState(TOR_ID).observe(getViewLifecycleOwner(),
				state -> {
					if (state != null) {
						updateTorStatus(state);
					}
				});

		torStatusMonitor.getBandwidthUpdate().observe(getViewLifecycleOwner(),
				update -> {
					if (update != null) {
						networkGraph.addDataPoint(update.downloadSpeed, update.uploadSpeed);
						totalDownload.setText(formatBytes(update.totalDownload));
						totalUpload.setText(formatBytes(update.totalUpload));
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

	private String formatBytes(long bytes) {
		if (bytes < 1024) {
			return bytes + " B";
		} else if (bytes < 1024 * 1024) {
			return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
		} else if (bytes < 1024 * 1024 * 1024) {
			return String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0));
		} else {
			return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
		}
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}

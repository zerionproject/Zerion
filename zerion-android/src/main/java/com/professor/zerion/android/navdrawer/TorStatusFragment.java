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

	// Views
	private ImageView torStatusIcon;
	private TextView torStatusText;
	private TextView torConnectionStatus;
	private TextView torUptime;
	private TextView torOnionAddress;
	private NetworkGraphView networkGraph;
	private TextView totalDownload;
	private TextView totalUpload;

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

		// Initialize views
		torStatusIcon = v.findViewById(R.id.torStatusIcon);
		torStatusText = v.findViewById(R.id.torStatusText);
		torConnectionStatus = v.findViewById(R.id.torConnectionStatus);
		torUptime = v.findViewById(R.id.torUptime);
		torOnionAddress = v.findViewById(R.id.torOnionAddress);
		networkGraph = v.findViewById(R.id.networkGraph);
		totalDownload = v.findViewById(R.id.totalDownload);
		totalUpload = v.findViewById(R.id.totalUpload);

		viewModel = new ViewModelProvider(requireActivity(), viewModelFactory)
				.get(PluginViewModel.class);

		return v;
	}

	@Override
	public void onStart() {
		super.onStart();
		requireActivity().setTitle(R.string.network_status_title);

		// Start monitoring
		torStatusMonitor.startMonitoring();

		// Observe plugin state for connection status
		viewModel.getPluginState(TOR_ID).observe(getViewLifecycleOwner(),
				state -> {
					if (state != null) {
						updateTorStatus(state);
					}
				});

		// Observe bandwidth updates for real-time graph
		torStatusMonitor.getBandwidthUpdate().observe(getViewLifecycleOwner(),
				update -> {
					if (update != null) {
						// Add data point to graph
						networkGraph.addDataPoint(update.downloadSpeed, update.uploadSpeed);

						// Update total statistics
						totalDownload.setText(formatBytes(update.totalDownload));
						totalUpload.setText(formatBytes(update.totalUpload));
					}
				});

		// Observe statistics for uptime
		torStatusMonitor.getStatistics().observe(getViewLifecycleOwner(),
				stats -> {
					if (stats != null) {
						torUptime.setText(formatUptime(stats.uptimeSeconds));
					}
				});
	}

	@Override
	public void onStop() {
		super.onStop();
		// Don't stop monitoring here - let it continue in background
		// The service handles the full lifecycle
	}

	private void updateTorStatus(org.briarproject.bramble.api.plugin.Plugin.State state) {
		if (state == null || state == org.briarproject.bramble.api.plugin.Plugin.State.DISABLED) {
			torStatusText.setText(R.string.disabled);
			torStatusText.setTextColor(0xFFFF5252);
			torConnectionStatus.setText(R.string.disabled);
			torOnionAddress.setText(R.string.not_available);
			torStatusIcon.setColorFilter(0xFFFF5252);
		} else if (state == org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE) {
			torStatusText.setText(R.string.connected);
			torStatusText.setTextColor(0xFF4CAF50);
			torConnectionStatus.setText(R.string.active);
			torOnionAddress.setText(R.string.tor_hidden_services_active);
			torStatusIcon.setColorFilter(0xFF26B7F0);
		} else {
			torStatusText.setText(R.string.connecting);
			torStatusText.setTextColor(0xFFFFA726);
			torConnectionStatus.setText(R.string.connecting);
			torOnionAddress.setText(R.string.tor_hidden_services_connecting);
			torStatusIcon.setColorFilter(0xFFFFA726);
		}
	}

	/**
	 * Format bytes to human-readable string.
	 */
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

	/**
	 * Format uptime seconds to human-readable string.
	 */
	private String formatUptime(long seconds) {
		if (seconds < 60) {
			return seconds + "s";
		} else if (seconds < 3600) {
			long mins = seconds / 60;
			long secs = seconds % 60;
			return String.format(Locale.US, "%dm %ds", mins, secs);
		} else {
			long hours = seconds / 3600;
			long mins = (seconds % 3600) / 60;
			return String.format(Locale.US, "%dh %dm", hours, mins);
		}
	}

	@Override
	public String getUniqueTag() {
		return TAG;
	}
}

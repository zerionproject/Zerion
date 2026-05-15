package com.professor.zerion.android.navdrawer;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import org.briarproject.bramble.api.network.NetworkStatus;
import org.briarproject.bramble.api.plugin.Plugin.State;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;

import androidx.annotation.ColorRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.ViewModelProvider;

import static android.view.View.GONE;
import static android.view.View.VISIBLE;
import static org.briarproject.bramble.api.plugin.Plugin.State.ACTIVE;
import static org.briarproject.bramble.api.plugin.Plugin.State.DISABLED;
import static org.briarproject.bramble.api.plugin.Plugin.State.ENABLING;
import static org.briarproject.bramble.api.plugin.Plugin.State.STARTING_STOPPING;
import static org.briarproject.bramble.api.plugin.TorConstants.REASON_BATTERY;
import static org.briarproject.bramble.api.plugin.TorConstants.REASON_COUNTRY_BLOCKED;
import static org.briarproject.bramble.api.plugin.TorConstants.REASON_MOBILE_DATA;
import static com.professor.zerion.android.util.UiUtils.showOnboardingDialog;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class TransportsActivity extends ZerionActivity {

	@Inject
	ViewModelProvider.Factory viewModelFactory;

	private final List<Transport> transports = new ArrayList<>(3);

	private PluginViewModel viewModel;
	private BaseAdapter transportsAdapter;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(PluginViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		super.onCreate(state);

		ActionBar actionBar = getSupportActionBar();
		if (actionBar != null) {
			actionBar.setHomeButtonEnabled(true);
			actionBar.setDisplayHomeAsUpEnabled(true);
		}

		setContentView(R.layout.activity_transports);

		GridView grid = findViewById(R.id.grid);
		initializeCards();
		grid.setAdapter(transportsAdapter);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		if (item.getItemId() == android.R.id.home) {
			onBackPressed();
			return true;
		} else if (item.getItemId() == R.id.action_help) {
			String text = getString(R.string.transports_help_text);
			showOnboardingDialog(this, text);
			return true;
		}
		return false;
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.help_action, menu);
		return super.onCreateOptionsMenu(menu);
	}

	private void initializeCards() {
		transportsAdapter = new BaseAdapter() {

			@Override
			public int getCount() {
				return transports.size();
			}

			@Override
			public Transport getItem(int position) {
				return transports.get(position);
			}

			@Override
			public long getItemId(int position) {
				return 0;
			}

			@Override
			public View getView(int position, View convertView,
					ViewGroup parent) {
				View view;
				if (convertView != null) {
					view = convertView;
				} else {
					LayoutInflater inflater = getLayoutInflater();
					view = inflater.inflate(R.layout.list_item_transport_card,
							parent, false);
				}

				Transport t = getItem(position);

				ImageView icon = view.findViewById(R.id.icon);
				icon.setImageResource(t.iconDrawable);
				icon.setColorFilter(ContextCompat.getColor(
						TransportsActivity.this, t.iconColor));

				TextView title = view.findViewById(R.id.title);
				title.setText(getString(t.title));

				SwitchCompat switchCompat =
						view.findViewById(R.id.switchCompat);
				switchCompat.setText(getString(t.switchLabel));
				switchCompat.setOnClickListener(v ->
						onClicked(t.id, switchCompat.isChecked()));
				switchCompat.setChecked(t.isSwitchChecked);

				TextView summary = view.findViewById(R.id.summary);
				if (t.summary == 0) {
					summary.setVisibility(GONE);
				} else {
					summary.setText(t.summary);
					summary.setVisibility(VISIBLE);
				}

				TextView deviceStatus = view.findViewById(R.id.deviceStatus);
				deviceStatus.setText(getBulletString(t.deviceStatus));

				TextView pluginStatus = view.findViewById(R.id.appStatus);
				pluginStatus.setText(getBulletString(t.pluginStatus));
				pluginStatus.setVisibility(t.showPluginStatus ? VISIBLE : GONE);

				return view;
			}
		};

		Transport tor = createTransport(TorConstants.ID,
				R.drawable.transport_tor, R.string.transport_tor,
				R.string.tor_enable_title, R.string.tor_enable_summary,
				R.string.tor_device_status_offline,
				R.string.tor_plugin_status_inactive);
		transports.add(tor);

		viewModel.getNetworkStatus().observe(this, status -> {
			updateTorResources(tor, status);
			transportsAdapter.notifyDataSetChanged();
		});
	}

	private void onClicked(TransportId transportId, boolean enable) {
		viewModel.enableTransport(transportId, enable);
	}

	private String getBulletString(@StringRes int resId) {
		return "\u2022 " + getString(resId);
	}

	@ColorRes
	private int getIconColor(State state) {
		if (state == ACTIVE) return R.color.zerion_lime_400;
		else if (state == ENABLING) return R.color.zerion_orange_500;
		else return android.R.color.tertiary_text_light;
	}

	private void updateTorResources(Transport tor, NetworkStatus status) {
		if (status.isConnected()) {
			if (status.isWifi()) {
				tor.deviceStatus = R.string.tor_device_status_online_wifi;
			} else {
				tor.deviceStatus = R.string.tor_device_status_online_mobile;
			}
			tor.showPluginStatus = true;
		} else {
			tor.deviceStatus = R.string.tor_device_status_offline;
			tor.showPluginStatus = false;
		}
	}

	@StringRes
	private int getPluginStatus(TransportId id, State state) {
		if (id.equals(TorConstants.ID)) {
			return getTorPluginStatus(state);
		}
		throw new AssertionError("Unknown transport: " + id);
	}

	@StringRes
	private int getTorPluginStatus(State state) {
		if (state == ENABLING) {
			return R.string.tor_plugin_status_enabling;
		} else if (state == ACTIVE) {
			return R.string.tor_plugin_status_active;
		} else if (state == DISABLED) {
			int reasons = viewModel.getReasonsTorDisabled();
			if ((reasons & REASON_MOBILE_DATA) != 0) {
				return R.string.tor_plugin_status_disabled_mobile_data;
			} else if ((reasons & REASON_BATTERY) != 0) {
				return R.string.tor_plugin_status_disabled_battery;
			} else if ((reasons & REASON_COUNTRY_BLOCKED) != 0) {
				return R.string.tor_plugin_status_disabled_country_blocked;
			} else {
				return R.string.tor_plugin_status_disabled;
			}
		} else {
			return R.string.tor_plugin_status_inactive;
		}
	}

	private Transport createTransport(TransportId id,
			@DrawableRes int iconDrawable, @StringRes int title,
			@StringRes int switchLabel, @StringRes int summary,
			@StringRes int deviceStatus, @StringRes int pluginStatus) {
		int iconColor = getIconColor(STARTING_STOPPING);
		Transport transport = new Transport(id, iconDrawable, iconColor, title,
				switchLabel, false, summary, deviceStatus, pluginStatus, false);
		viewModel.getPluginState(id).observe(this, state -> {
			transport.iconColor = getIconColor(state);
			transport.pluginStatus = getPluginStatus(transport.id, state);
			transportsAdapter.notifyDataSetChanged();
		});
		viewModel.getPluginEnabledSetting(id).observe(this, enabled -> {
			transport.isSwitchChecked = enabled;
			transportsAdapter.notifyDataSetChanged();
		});
		return transport;
	}

	private static class Transport {

		private final TransportId id;

		@DrawableRes
		private final int iconDrawable;
		@StringRes
		private final int title, switchLabel, summary;

		@ColorRes
		private int iconColor;
		@StringRes
		private int deviceStatus, pluginStatus;
		private boolean isSwitchChecked, showPluginStatus;

		private Transport(TransportId id,
				@DrawableRes int iconDrawable,
				@ColorRes int iconColor,
				@StringRes int title,
				@StringRes int switchLabel,
				boolean isSwitchChecked,
				@StringRes int summary,
				@StringRes int deviceStatus,
				@StringRes int pluginStatus,
				boolean showPluginStatus) {
			this.id = id;
			this.iconDrawable = iconDrawable;
			this.iconColor = iconColor;
			this.title = title;
			this.switchLabel = switchLabel;
			this.isSwitchChecked = isSwitchChecked;
			this.summary = summary;
			this.deviceStatus = deviceStatus;
			this.pluginStatus = pluginStatus;
			this.showPluginStatus = showPluginStatus;
		}
	}
}

package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.api.channel.ChannelApplication;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelPendingApplicationsActivity extends ZerionActivity {

	private static final String EXTRA_CHANNEL_ID =
			"com.professor.zerion.android.channel.PENDING_CHANNEL_ID";

	public static Intent intent(Context ctx, byte[] channelId) {
		Intent i = new Intent(ctx, ChannelPendingApplicationsActivity.class);
		i.putExtra(EXTRA_CHANNEL_ID, channelId);
		return i;
	}

	@Inject
	ChannelManager channelManager;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private byte[] channelId = new byte[0];
	private RecyclerView recycler;
	private TextView emptyView;
	private PendingAdapter adapter;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		getWindow().setFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE,
				android.view.WindowManager.LayoutParams.FLAG_SECURE);
		setContentView(R.layout.activity_channel_pending_applications);

		byte[] cid = getIntent().getByteArrayExtra(EXTRA_CHANNEL_ID);
		if (cid != null) channelId = cid;

		Toolbar toolbar = findViewById(R.id.pendingApplicationsToolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		toolbar.setNavigationOnClickListener(v -> finish());

		recycler = findViewById(R.id.pendingApplicationsRecycler);
		emptyView = findViewById(R.id.pendingApplicationsEmptyView);
		adapter = new PendingAdapter(this::confirmApprove, this::confirmDeny);
		recycler.setLayoutManager(new LinearLayoutManager(this));
		recycler.setAdapter(adapter);
	}

	@Override
	public void onResume() {
		super.onResume();
		refresh();
	}

	private void refresh() {
		ioExecutor.execute(() -> {
			List<ChannelApplication> apps;
			try {
				apps = channelManager.listPendingApplications(channelId);
			} catch (DbException ex) {
				apps = new ArrayList<>();
			}
			List<ChannelApplication> finalApps = apps;
			runOnUiThread(() -> render(finalApps));
		});
	}

	private void render(List<ChannelApplication> apps) {
		if (apps.isEmpty()) {
			recycler.setVisibility(View.GONE);
			emptyView.setVisibility(View.VISIBLE);
		} else {
			recycler.setVisibility(View.VISIBLE);
			emptyView.setVisibility(View.GONE);
			adapter.setItems(apps);
		}
		setTitle(getString(R.string.channels_pending_count, apps.size()));
	}

	private void confirmApprove(ChannelApplication app) {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.channels_pending_approve)
				.setMessage(app.getDisplayName())
				.setPositiveButton(R.string.channels_pending_approve,
						(d, w) -> doApprove(app))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void doApprove(ChannelApplication app) {
		ioExecutor.execute(() -> {
			try {
				channelManager.approveApplication(channelId,
						app.getApplicantEd25519());
				runOnUiThread(() -> {
					Toast.makeText(this,
							R.string.channels_pending_approved,
							Toast.LENGTH_SHORT).show();
					refresh();
				});
			} catch (DbException ex) {
				runOnUiThread(() -> Toast.makeText(this,
						R.string.channels_apply_failed,
						Toast.LENGTH_SHORT).show());
			}
		});
	}

	private void confirmDeny(ChannelApplication app) {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.channels_pending_deny)
				.setMessage(app.getDisplayName())
				.setPositiveButton(R.string.channels_pending_deny,
						(d, w) -> doDeny(app))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void doDeny(ChannelApplication app) {
		ioExecutor.execute(() -> {
			try {
				channelManager.denyApplication(channelId,
						app.getApplicantEd25519());
				runOnUiThread(() -> {
					Toast.makeText(this,
							R.string.channels_pending_denied,
							Toast.LENGTH_SHORT).show();
					refresh();
				});
			} catch (DbException ex) {
				runOnUiThread(() -> Toast.makeText(this,
						R.string.channels_apply_failed,
						Toast.LENGTH_SHORT).show());
			}
		});
	}

	private static class PendingAdapter
			extends RecyclerView.Adapter<PendingViewHolder> {

		interface OnAction {
			void onAction(ChannelApplication app);
		}

		private List<ChannelApplication> items = new ArrayList<>();
		private final OnAction onApprove;
		private final OnAction onDeny;

		PendingAdapter(OnAction onApprove, OnAction onDeny) {
			this.onApprove = onApprove;
			this.onDeny = onDeny;
		}

		void setItems(List<ChannelApplication> apps) {
			this.items = apps;
			notifyDataSetChanged();
		}

		@NonNull
		@Override
		public PendingViewHolder onCreateViewHolder(
				@NonNull ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext()).inflate(
					R.layout.list_item_channel_pending_application,
					parent, false);
			return new PendingViewHolder(v);
		}

		@Override
		public void onBindViewHolder(@NonNull PendingViewHolder h,
				int position) {
			ChannelApplication app = items.get(position);
			h.bind(app);
			h.approveButton.setOnClickListener(v -> onApprove.onAction(app));
			h.denyButton.setOnClickListener(v -> onDeny.onAction(app));
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}

	private static class PendingViewHolder
			extends RecyclerView.ViewHolder {

		final TextView name;
		final TextView subtitle;
		final MaterialButton approveButton;
		final MaterialButton denyButton;

		PendingViewHolder(@NonNull View itemView) {
			super(itemView);
			name = itemView.findViewById(R.id.applicantName);
			subtitle = itemView.findViewById(R.id.applicantSubtitle);
			approveButton = itemView.findViewById(
					R.id.applicantApproveButton);
			denyButton = itemView.findViewById(R.id.applicantDenyButton);
		}

		void bind(ChannelApplication app) {
			name.setText(app.getDisplayName());
			subtitle.setText(toHexShort(app.getApplicantEd25519()));
		}

		private static String toHexShort(byte[] b) {
			StringBuilder sb = new StringBuilder();
			int take = Math.min(b.length, 12);
			for (int i = 0; i < take; i++) {
				sb.append(String.format(Locale.US, "%02x", b[i]));
			}
			if (b.length > take) sb.append("…");
			return sb.toString();
		}
	}
}

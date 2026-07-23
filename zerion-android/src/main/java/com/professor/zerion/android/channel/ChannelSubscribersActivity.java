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

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelSubscribersActivity extends ZerionActivity {

	private static final String EXTRA_CHANNEL_ID =
			"com.professor.zerion.android.channel.SUBSCRIBERS_CHANNEL_ID";

	public static Intent intent(Context ctx, byte[] channelId) {
		Intent i = new Intent(ctx, ChannelSubscribersActivity.class);
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
	private SubscribersAdapter adapter;
	private volatile boolean channelIsClosed = false;

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
		setContentView(R.layout.activity_channel_subscribers);

		byte[] cid = getIntent().getByteArrayExtra(EXTRA_CHANNEL_ID);
		if (cid != null) channelId = cid;

		Toolbar toolbar = findViewById(R.id.subscribersToolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		toolbar.setNavigationOnClickListener(v -> finish());

		recycler = findViewById(R.id.subscribersRecycler);
		emptyView = findViewById(R.id.subscribersEmptyView);
		adapter = new SubscribersAdapter(this::confirmBan);
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
			List<ChannelSubscriber> subs;
			try {
				subs = channelManager.getAnnouncedSubscribers(channelId);
				org.zerionproject.app.api.channel.ChannelState s =
						channelManager.getChannel(channelId);
				channelIsClosed = s != null && !s.isPublicChannel();
			} catch (DbException ex) {
				subs = new ArrayList<>();
			}
			List<ChannelSubscriber> finalSubs = subs;
			runOnUiThread(() -> render(finalSubs));
		});
	}

	private void render(List<ChannelSubscriber> subs) {
		if (subs.isEmpty()) {
			recycler.setVisibility(View.GONE);
			emptyView.setVisibility(View.VISIBLE);
		} else {
			recycler.setVisibility(View.VISIBLE);
			emptyView.setVisibility(View.GONE);
			adapter.setItems(subs);
		}
		setTitle(getString(R.string.channels_subscribers_count,
				subs.size()));
	}

	private void confirmBan(ChannelSubscriber sub) {
		if (sub.isBanned()) return;
		android.widget.LinearLayout container =
				new android.widget.LinearLayout(this);
		container.setOrientation(android.widget.LinearLayout.VERTICAL);
		int pad = (int) (16f * getResources().getDisplayMetrics().density);
		container.setPadding(pad, 0, pad, 0);
		android.widget.TextView message = new android.widget.TextView(this);
		message.setText(R.string.channels_subscribers_ban_confirm);
		message.setTextColor(getResources()
				.getColor(R.color.zerion_text_primary, getTheme()));
		container.addView(message);
		final com.google.android.material.checkbox.MaterialCheckBox rotateBox;
		if (channelIsClosed) {
			rotateBox =
					new com.google.android.material.checkbox.MaterialCheckBox(
							this);
			rotateBox.setText(R.string.channels_subscribers_ban_rotate);
			rotateBox.setChecked(true);
			android.widget.LinearLayout.LayoutParams lp =
					new android.widget.LinearLayout.LayoutParams(
							android.widget.LinearLayout.LayoutParams
									.MATCH_PARENT,
							android.widget.LinearLayout.LayoutParams
									.WRAP_CONTENT);
			lp.topMargin = pad;
			container.addView(rotateBox, lp);
		} else {
			rotateBox = null;
		}
		new MaterialAlertDialogBuilder(this)
				.setView(container)
				.setPositiveButton(R.string.channels_subscribers_ban,
						(d, w) -> doBan(sub,
								rotateBox != null && rotateBox.isChecked()))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void doBan(ChannelSubscriber sub, boolean alsoRotate) {
		ioExecutor.execute(() -> {
			try {
				channelManager.banSubscriber(channelId,
						sub.getEd25519PubKey());
				if (alsoRotate) {
					try {
						channelManager.rotateJoinCapability(channelId);
					} catch (DbException ignored) {
					}
				}
				runOnUiThread(this::refresh);
			} catch (DbException ignored) {
				runOnUiThread(() -> Toast.makeText(this,
						R.string.channels_react_failed,
						Toast.LENGTH_SHORT).show());
			}
		});
	}

	private static class SubscribersAdapter
			extends RecyclerView.Adapter<SubscriberViewHolder> {

		interface OnBan {
			void onBan(ChannelSubscriber sub);
		}

		private List<ChannelSubscriber> items = new ArrayList<>();
		private final OnBan onBan;

		SubscribersAdapter(OnBan onBan) {
			this.onBan = onBan;
		}

		void setItems(List<ChannelSubscriber> subs) {
			this.items = subs;
			notifyDataSetChanged();
		}

		@NonNull
		@Override
		public SubscriberViewHolder onCreateViewHolder(
				@NonNull ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext()).inflate(
					R.layout.list_item_channel_subscriber, parent, false);
			return new SubscriberViewHolder(v);
		}

		@Override
		public void onBindViewHolder(@NonNull SubscriberViewHolder h,
				int position) {
			ChannelSubscriber sub = items.get(position);
			h.bind(sub);
			h.itemView.setOnLongClickListener(v -> {
				onBan.onBan(sub);
				return true;
			});
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}

	private static class SubscriberViewHolder
			extends RecyclerView.ViewHolder {

		final TextView name;
		final TextView subtitle;
		final TextView bannedBadge;

		SubscriberViewHolder(@NonNull View itemView) {
			super(itemView);
			name = itemView.findViewById(R.id.subscriberName);
			subtitle = itemView.findViewById(R.id.subscriberSubtitle);
			bannedBadge = itemView.findViewById(
					R.id.subscriberBannedBadge);
		}

		void bind(ChannelSubscriber sub) {
			name.setText(sub.getDisplayName());
			subtitle.setText(toHexShort(sub.getEd25519PubKey()));
			bannedBadge.setVisibility(sub.isBanned()
					? View.VISIBLE : View.GONE);
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

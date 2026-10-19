package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelSubscribersActivity extends ZerionActivity {

	private static final String EXTRA_CHANNEL_ID =
			"com.professor.zerion.android.channel.SUBSCRIBERS_CHANNEL_ID";
	private static final String EXTRA_BLOCKED_ONLY =
			"com.professor.zerion.android.channel.SUBSCRIBERS_BLOCKED_ONLY";

	public static Intent intent(Context ctx, byte[] channelId) {
		Intent i = new Intent(ctx, ChannelSubscribersActivity.class);
		i.putExtra(EXTRA_CHANNEL_ID, channelId);
		return i;
	}

	public static Intent blockedIntent(Context ctx, byte[] channelId) {
		Intent i = intent(ctx, channelId);
		i.putExtra(EXTRA_BLOCKED_ONLY, true);
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
	private boolean blockedOnly = false;

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
		blockedOnly = getIntent().getBooleanExtra(EXTRA_BLOCKED_ONLY, false);

		Toolbar toolbar = findViewById(R.id.subscribersToolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		toolbar.setNavigationOnClickListener(v -> finish());

		recycler = findViewById(R.id.subscribersRecycler);
		emptyView = findViewById(R.id.subscribersEmptyView);
		if (blockedOnly) emptyView.setText(R.string.channels_member_none_blocked);
		else setTitle(R.string.channels_settings_named_people);
		adapter = new SubscribersAdapter(this::showMember);
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
				subs = new ArrayList<>(
						channelManager.getAnnouncedSubscribers(channelId));
				for (byte[] key : channelManager.getBannedKeys(channelId)) {
					boolean listed = false;
					for (ChannelSubscriber sub : subs) {
						if (java.util.Arrays.equals(sub.getEd25519PubKey(),
								key)) {
							listed = true;
							break;
						}
					}
					if (listed) {
						markBanned(subs, key);
					} else {
						subs.add(new ChannelSubscriber("", key, new byte[0],
								0L, true));
					}
				}
				List<ChannelSubscriber> shown = new ArrayList<>();
				for (ChannelSubscriber sub : subs) {
					if (sub.isBanned() == blockedOnly) shown.add(sub);
				}
				subs = shown;
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
		setTitle(blockedOnly ? getString(R.string.channels_settings_blocked)
				: getString(R.string.channels_named_people_count,
						subs.size()));
	}

	private static void markBanned(List<ChannelSubscriber> subs, byte[] key) {
		for (int i = 0; i < subs.size(); i++) {
			ChannelSubscriber sub = subs.get(i);
			if (!sub.isBanned() && java.util.Arrays.equals(
					sub.getEd25519PubKey(), key)) {
				subs.set(i, new ChannelSubscriber(sub.getDisplayName(),
						sub.getEd25519PubKey(), sub.getMlDsaPubKey(),
						sub.getJoinedAtHourMs(), true));
			}
		}
	}

	private void showMember(ChannelSubscriber sub) {
		ChannelMemberSheet.open(this, channelManager, ioExecutor, channelId,
				sub.getEd25519PubKey(), sub.getDisplayName(),
				sub.getMlDsaPubKey(), this::refresh);
	}

	private static class SubscribersAdapter
			extends RecyclerView.Adapter<SubscriberViewHolder> {

		interface OnMember {
			void onMember(ChannelSubscriber sub);
		}

		private List<ChannelSubscriber> items = new ArrayList<>();
		private final OnMember onMember;

		SubscribersAdapter(OnMember onMember) {
			this.onMember = onMember;
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
			h.itemView.setOnClickListener(v -> onMember.onMember(sub));
			h.itemView.setOnLongClickListener(v -> {
				onMember.onMember(sub);
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
			name.setText(sub.getDisplayName().isEmpty()
					? itemView.getContext().getString(
							R.string.channels_delegations_unnamed_person)
					: sub.getDisplayName());
			boolean trusted = sub.isTrusted() && !sub.isBanned();
			subtitle.setText(R.string.channels_subscribers_trusted_badge);
			subtitle.setVisibility(trusted ? View.VISIBLE : View.GONE);
			bannedBadge.setVisibility(sub.isBanned()
					? View.VISIBLE : View.GONE);
		}
	}
}

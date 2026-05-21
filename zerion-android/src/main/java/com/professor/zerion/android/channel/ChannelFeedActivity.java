package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.ChipGroup;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.ChannelPost;
import org.briarproject.briar.api.channel.ChannelState;
import org.briarproject.briar.api.channel.event.ChannelPostReceivedEvent;
import org.briarproject.briar.api.channel.event.ChannelStateChangedEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelFeedActivity extends ZerionActivity
		implements EventListener {

	private static final String EXTRA_CHANNEL_ID =
			"com.professor.zerion.android.channel.CHANNEL_ID";

	public static Intent intent(Context ctx, byte[] channelId) {
		Intent i = new Intent(ctx, ChannelFeedActivity.class);
		i.putExtra(EXTRA_CHANNEL_ID, channelId);
		return i;
	}

	@Inject
	ChannelManager channelManager;
	@Inject
	EventBus eventBus;
	@Inject
	@IoExecutor
	Executor ioExecutor;

	private byte[] channelId = new byte[0];
	private Toolbar toolbar;
	private RecyclerView recycler;
	private TextView emptyView;
	private LinearLayout composeBar;
	private EditText composeInput;
	private MaterialButton composeSendButton;
	private ChipGroup ttlChipGroup;
	private LinearLayout pinnedBanner;
	private TextView pinnedBannerText;
	private android.widget.ImageButton pinnedBannerClose;
	private PostAdapter adapter;
	private boolean weArePublisher = false;
	private long currentPinnedSeq = ChannelState.NO_PINNED_POST;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
	}

	@Override
	public void onCreate(@Nullable Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		setContentView(R.layout.activity_channel_feed);

		Intent i = getIntent();
		byte[] cid = i.getByteArrayExtra(EXTRA_CHANNEL_ID);
		if (cid != null) channelId = cid;

		toolbar = findViewById(R.id.channelFeedToolbar);
		recycler = findViewById(R.id.channelFeedRecycler);
		emptyView = findViewById(R.id.channelFeedEmptyView);
		composeBar = findViewById(R.id.channelComposeBar);
		composeInput = findViewById(R.id.channelComposeInput);
		composeSendButton = findViewById(R.id.channelComposeSendButton);
		ttlChipGroup = findViewById(R.id.channelTtlChipGroup);
		pinnedBanner = findViewById(R.id.channelPinnedBanner);
		pinnedBannerText = findViewById(R.id.channelPinnedBannerText);
		pinnedBannerClose = findViewById(R.id.channelPinnedBannerClose);

		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		toolbar.setNavigationOnClickListener(v -> finish());

		adapter = new PostAdapter(post -> showPostMenu(post,
				post.getSeqNum() == currentPinnedSeq));
		recycler.setLayoutManager(new LinearLayoutManager(this));
		recycler.setAdapter(adapter);

		composeSendButton.setOnClickListener(v -> handleSend());
	}

	private static final long FOREGROUND_REFRESH_INTERVAL_MS = 10_000L;
	@Nullable
	private android.os.Handler refreshHandler;
	private final Runnable refreshTick = new Runnable() {
		@Override
		public void run() {
			refreshFromPublisherSafely();
			if (refreshHandler != null) {
				refreshHandler.postDelayed(this,
						FOREGROUND_REFRESH_INTERVAL_MS);
			}
		}
	};

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		loadChannel();
		markRead();
		refreshFromPublisherSafely();
		refreshHandler = new android.os.Handler(
				android.os.Looper.getMainLooper());
		refreshHandler.postDelayed(refreshTick,
				FOREGROUND_REFRESH_INTERVAL_MS);
	}

	private void refreshFromPublisherSafely() {
		ioExecutor.execute(() -> {
			try {
				channelManager.refreshChannel(channelId);
				runOnUiThread(this::loadChannel);
			} catch (DbException ignored) {
			}
		});
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(this);
		if (refreshHandler != null) {
			refreshHandler.removeCallbacks(refreshTick);
			refreshHandler = null;
		}
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ChannelPostReceivedEvent) {
			ChannelPostReceivedEvent ev = (ChannelPostReceivedEvent) e;
			if (Arrays.equals(ev.getChannelId(), channelId)) {
				runOnUiThread(this::loadChannel);
			}
		} else if (e instanceof ChannelStateChangedEvent) {
			ChannelStateChangedEvent ev = (ChannelStateChangedEvent) e;
			if (Arrays.equals(ev.getChannelId(), channelId)) {
				runOnUiThread(this::loadChannel);
			}
		}
	}

	private void loadChannel() {
		ioExecutor.execute(() -> {
			ChannelState state;
			List<ChannelPost> posts;
			try {
				state = channelManager.getChannel(channelId);
				posts = state == null ? new ArrayList<>()
						: channelManager.getRecentPosts(channelId, 500L);
			} catch (DbException ex) {
				state = null;
				posts = new ArrayList<>();
			}
			ChannelState finalState = state;
			List<ChannelPost> finalPosts = posts;
			runOnUiThread(() -> render(finalState, finalPosts));
		});
	}

	private void render(@Nullable ChannelState state,
			List<ChannelPost> posts) {
		if (state == null) {
			finish();
			return;
		}
		weArePublisher = state.weArePublisher();
		toolbar.setTitle(state.getName());
		toolbar.setSubtitle(state.isPublicChannel()
				? getString(R.string.channels_row_public)
				: getString(R.string.channels_row_closed));

		if (posts.isEmpty()) {
			recycler.setVisibility(View.GONE);
			emptyView.setVisibility(View.VISIBLE);
			emptyView.setText(weArePublisher
					? R.string.channels_feed_empty_publisher
					: R.string.channels_feed_empty_subscriber);
		} else {
			recycler.setVisibility(View.VISIBLE);
			emptyView.setVisibility(View.GONE);
			adapter.setPosts(posts);
			recycler.scrollToPosition(posts.size() - 1);
		}

		composeBar.setVisibility(weArePublisher ? View.VISIBLE : View.GONE);
		ttlChipGroup.setVisibility(weArePublisher
				? View.VISIBLE : View.GONE);

		currentPinnedSeq = state.getPinnedPostSeq();
		bindPinnedBanner(state, posts);
	}

	private void bindPinnedBanner(ChannelState state,
			List<ChannelPost> posts) {
		long pinned = state.getPinnedPostSeq();
		if (pinned < 0L) {
			pinnedBanner.setVisibility(View.GONE);
			return;
		}
		ChannelPost target = null;
		int targetIndex = -1;
		for (int i = 0; i < posts.size(); i++) {
			if (posts.get(i).getSeqNum() == pinned) {
				target = posts.get(i);
				targetIndex = i;
				break;
			}
		}
		if (target == null) {
			pinnedBanner.setVisibility(View.GONE);
			return;
		}
		pinnedBanner.setVisibility(View.VISIBLE);
		pinnedBannerText.setText(target.getBody());
		final int idx = targetIndex;
		pinnedBanner.setOnClickListener(v ->
				recycler.smoothScrollToPosition(idx));
		pinnedBannerClose.setVisibility(
				weArePublisher ? View.VISIBLE : View.GONE);
		pinnedBannerClose.setOnClickListener(v -> handleUnpin());
	}

	private void handlePin(long seqNum) {
		ioExecutor.execute(() -> {
			try {
				channelManager.pinPost(channelId, seqNum);
				runOnUiThread(this::loadChannel);
			} catch (DbException ignored) {
			}
		});
	}

	private void handleUnpin() {
		ioExecutor.execute(() -> {
			try {
				channelManager.unpinPost(channelId);
				runOnUiThread(this::loadChannel);
			} catch (DbException ignored) {
			}
		});
	}

	private void showPostMenu(ChannelPost post, boolean isPinned) {
		java.util.List<CharSequence> labels = new java.util.ArrayList<>();
		java.util.List<Runnable> actions = new java.util.ArrayList<>();
		if (weArePublisher) {
			if (isPinned) {
				labels.add(getString(R.string.channels_action_unpin));
				actions.add(this::handleUnpin);
			} else {
				labels.add(getString(R.string.channels_action_pin));
				actions.add(() -> handlePin(post.getSeqNum()));
			}
		}
		labels.add(getString(R.string.channels_action_copy_text));
		actions.add(() -> copyPostText(post));
		new com.google.android.material.dialog.MaterialAlertDialogBuilder(
				this)
				.setItems(labels.toArray(new CharSequence[0]),
						(d, which) -> actions.get(which).run())
				.show();
	}

	private void copyPostText(ChannelPost post) {
		android.content.ClipboardManager cm =
				(android.content.ClipboardManager) getSystemService(
						android.content.Context.CLIPBOARD_SERVICE);
		if (cm == null) return;
		cm.setPrimaryClip(android.content.ClipData.newPlainText(
				"zerion-channel-post", post.getBody()));
		Toast.makeText(this, R.string.channels_post_copied,
				Toast.LENGTH_SHORT).show();
	}

	private long readSelectedTtlSeconds() {
		int id = ttlChipGroup.getCheckedChipId();
		if (id == R.id.channelTtlChipHour) return 60L * 60L;
		if (id == R.id.channelTtlChipDay) return 24L * 60L * 60L;
		if (id == R.id.channelTtlChipWeek) return 7L * 24L * 60L * 60L;
		if (id == R.id.channelTtlChipMonth) return 30L * 24L * 60L * 60L;
		return 0L;
	}

	private void markRead() {
		ioExecutor.execute(() -> {
			try {
				channelManager.markChannelRead(channelId);
			} catch (DbException ignored) {
			}
		});
	}

	private void handleSend() {
		String body = composeInput.getText() == null
				? "" : composeInput.getText().toString().trim();
		if (body.isEmpty()) {
			Toast.makeText(this,
					R.string.channels_compose_error_empty,
					Toast.LENGTH_SHORT).show();
			return;
		}
		composeInput.setText("");
		long ttlSeconds = readSelectedTtlSeconds();
		ioExecutor.execute(() -> {
			try {
				channelManager.publishPost(channelId, body, ttlSeconds);
				runOnUiThread(this::loadChannel);
			} catch (DbException ex) {
				runOnUiThread(() -> Toast.makeText(this,
						R.string.channels_compose_error_empty,
						Toast.LENGTH_SHORT).show());
			}
		});
	}

	private static class PostAdapter
			extends RecyclerView.Adapter<PostViewHolder> {

		interface OnPostLongPress {
			void onPostLongPress(ChannelPost post);
		}

		private List<ChannelPost> posts = new ArrayList<>();
		private final OnPostLongPress longPressListener;

		PostAdapter(OnPostLongPress longPressListener) {
			this.longPressListener = longPressListener;
		}

		void setPosts(List<ChannelPost> p) {
			this.posts = p;
			notifyDataSetChanged();
		}

		@NonNull
		@Override
		public PostViewHolder onCreateViewHolder(@NonNull ViewGroup parent,
				int viewType) {
			View v = LayoutInflater.from(parent.getContext()).inflate(
					R.layout.list_item_channel_post, parent, false);
			return new PostViewHolder(v);
		}

		@Override
		public void onBindViewHolder(@NonNull PostViewHolder holder,
				int position) {
			ChannelPost p = posts.get(position);
			holder.bind(p);
			holder.itemView.setOnLongClickListener(v -> {
				longPressListener.onPostLongPress(p);
				return true;
			});
		}

		@Override
		public int getItemCount() {
			return posts.size();
		}
	}

	private static class PostViewHolder extends RecyclerView.ViewHolder {

		private final TextView body;
		private final TextView timestamp;
		private final TextView signerBadge;
		private final TextView ttlLabel;

		PostViewHolder(@NonNull View itemView) {
			super(itemView);
			body = itemView.findViewById(R.id.channelPostBodyView);
			timestamp = itemView.findViewById(R.id.channelPostTimestampView);
			signerBadge =
					itemView.findViewById(R.id.channelPostSignerBadge);
			ttlLabel = itemView.findViewById(R.id.channelPostTtlLabel);
		}

		void bind(ChannelPost p) {
			body.setText(p.getBody());
			timestamp.setText(
					com.professor.zerion.android.util.UiUtils.formatDate(
							itemView.getContext(),
							p.getTimestampHourMs()));

			if (p.signedByDelegate()) {
				signerBadge.setText(itemView.getContext()
						.getString(R.string.channels_post_signer_editor)
						.toUpperCase(Locale.ROOT));
				signerBadge.setVisibility(View.VISIBLE);
			} else {
				signerBadge.setVisibility(View.GONE);
			}

			if (p.isEphemeral()) {
				long expiresAt = p.getTimestampHourMs() + p.getTtlMs();
				long remaining = expiresAt - System.currentTimeMillis();
				if (remaining > 0) {
					ttlLabel.setText(formatRemaining(itemView.getContext(),
							remaining));
					ttlLabel.setVisibility(View.VISIBLE);
				} else {
					ttlLabel.setVisibility(View.GONE);
				}
			} else {
				ttlLabel.setVisibility(View.GONE);
			}
		}

		private static String formatRemaining(android.content.Context ctx,
				long remainingMs) {
			long hours = remainingMs / (60L * 60L * 1000L);
			if (hours <= 1L) {
				return ctx.getString(R.string.channels_ttl_one_hour);
			}
			if (hours <= 24L) {
				return ctx.getString(R.string.channels_ttl_one_day);
			}
			if (hours <= 24L * 7L) {
				return ctx.getString(R.string.channels_ttl_one_week);
			}
			return ctx.getString(R.string.channels_ttl_thirty_days);
		}
	}
}

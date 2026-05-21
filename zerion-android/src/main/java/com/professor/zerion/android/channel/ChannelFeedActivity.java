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
	private PostAdapter adapter;
	private boolean weArePublisher = false;

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

		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		toolbar.setNavigationOnClickListener(v -> finish());

		adapter = new PostAdapter();
		recycler.setLayoutManager(new LinearLayoutManager(this));
		recycler.setAdapter(adapter);

		composeSendButton.setOnClickListener(v -> handleSend());
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		loadChannel();
		markRead();
		refreshFromPublisherSafely();
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

		private List<ChannelPost> posts = new ArrayList<>();

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
			holder.bind(posts.get(position));
		}

		@Override
		public int getItemCount() {
			return posts.size();
		}
	}

	private static class PostViewHolder extends RecyclerView.ViewHolder {

		private final TextView body;
		private final TextView timestamp;
		private final TextView seq;
		private final TextView signerBadge;
		private final TextView ttlLabel;

		PostViewHolder(@NonNull View itemView) {
			super(itemView);
			body = itemView.findViewById(R.id.channelPostBodyView);
			timestamp = itemView.findViewById(R.id.channelPostTimestampView);
			seq = itemView.findViewById(R.id.channelPostSeqView);
			signerBadge =
					itemView.findViewById(R.id.channelPostSignerBadge);
			ttlLabel = itemView.findViewById(R.id.channelPostTtlLabel);
		}

		void bind(ChannelPost p) {
			body.setText(p.getBody());
			timestamp.setText(DateUtils.getRelativeTimeSpanString(
					p.getTimestampHourMs(),
					System.currentTimeMillis(),
					DateUtils.MINUTE_IN_MILLIS));
			seq.setText(String.format(Locale.US, "#%d", p.getSeqNum()));

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
					ttlLabel.setText(itemView.getContext().getString(
							R.string.channels_ttl_post_label,
							formatRemaining(itemView.getContext(),
									remaining)));
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

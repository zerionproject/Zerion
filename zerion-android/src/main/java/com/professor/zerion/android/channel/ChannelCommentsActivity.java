package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.button.MaterialButton;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelQueuedItem;
import org.zerionproject.app.api.channel.ChannelRequestQueuedException;
import org.zerionproject.app.api.channel.event.ChannelCommentReceivedEvent;
import org.zerionproject.app.api.channel.event.ChannelStateChangedEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelCommentsActivity extends ZerionActivity
		implements EventListener {

	private static final String EXTRA_CHANNEL_ID =
			"com.professor.zerion.android.channel.COMMENTS_CHANNEL_ID";
	private static final String EXTRA_PARENT_SEQ =
			"com.professor.zerion.android.channel.COMMENTS_PARENT_SEQ";

	public static Intent intent(Context ctx, byte[] channelId,
			long parentSeq) {
		Intent i = new Intent(ctx, ChannelCommentsActivity.class);
		i.putExtra(EXTRA_CHANNEL_ID, channelId);
		i.putExtra(EXTRA_PARENT_SEQ, parentSeq);
		return i;
	}

	@Inject
	ChannelManager channelManager;
	@Inject
	EventBus eventBus;
	@Inject
	@IoExecutor
	Executor ioExecutor;
	@Inject
	com.professor.zerion.android.api.AndroidNotificationManager
			notificationManager;

	private byte[] channelId = new byte[0];
	private long parentSeq = 0L;
	private RecyclerView recycler;
	private TextView emptyView;
	private EditText composeInput;
	private MaterialButton sendButton;
	private View composeBar;
	private TextView disabledNotice;
	private CommentsAdapter adapter;

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
		setContentView(R.layout.activity_channel_comments);

		byte[] cid = getIntent().getByteArrayExtra(EXTRA_CHANNEL_ID);
		if (cid != null) channelId = cid;
		parentSeq = getIntent().getLongExtra(EXTRA_PARENT_SEQ, 0L);

		Toolbar toolbar = findViewById(R.id.commentsToolbar);
		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		toolbar.setNavigationOnClickListener(v -> finish());

		recycler = findViewById(R.id.commentsRecycler);
		emptyView = findViewById(R.id.commentsEmptyView);
		composeInput = findViewById(R.id.commentsComposeInput);
		sendButton = findViewById(R.id.commentsComposeSendButton);
		composeBar = findViewById(R.id.commentsComposeBar);
		disabledNotice = findViewById(R.id.commentsDisabledNotice);
		adapter = new CommentsAdapter(this::confirmBanAuthor,
				new CommentsAdapter.OnQueued() {
					@Override
					public void retry(ChannelQueuedItem item) {
						retryQueued(item);
					}

					@Override
					public void delete(ChannelQueuedItem item) {
						deleteQueued(item);
					}
				});
		recycler.setLayoutManager(new LinearLayoutManager(this));
		recycler.setAdapter(adapter);
		sendButton.setOnClickListener(v -> handleSend());
		composeInput.setOnKeyListener((v, keyCode, event) -> {
			if (keyCode == android.view.KeyEvent.KEYCODE_ENTER
					&& event.getAction() == android.view.KeyEvent.ACTION_DOWN
					&& !event.isShiftPressed()) {
				handleSend();
				return true;
			}
			return false;
		});
		composeInput.setImeOptions(
				android.view.inputmethod.EditorInfo.IME_ACTION_SEND);
		composeInput.setOnEditorActionListener((v, actionId, ev) -> {
			if (actionId
					== android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
				handleSend();
				return true;
			}
			return false;
		});
		restoreCommentDraft();
	}

	private String commentDraftKey() {
		StringBuilder sb = new StringBuilder("channel_comment_draft_");
		for (byte b : channelId) sb.append(String.format(
				java.util.Locale.US, "%02x", b & 0xFF));
		return sb.append('_').append(parentSeq).toString();
	}

	private void restoreCommentDraft() {
		if (channelId.length == 0 || composeInput == null) return;
		String draft = com.professor.zerion.android.AppModule
				.getAndroidComponent(this).profilePreferences()
				.getString(commentDraftKey(), null);
		if (draft != null && !draft.isEmpty()) {
			composeInput.setText(draft);
			composeInput.setSelection(draft.length());
		}
	}

	private void saveCommentDraft() {
		if (composeInput == null || channelId.length == 0) return;
		String draft = composeInput.getText() == null
				? "" : composeInput.getText().toString();
		android.content.SharedPreferences sp = com.professor.zerion.android
				.AppModule.getAndroidComponent(this).profilePreferences();
		if (draft.trim().isEmpty()) {
			sp.edit().remove(commentDraftKey()).apply();
		} else {
			sp.edit().putString(commentDraftKey(), draft).apply();
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		if (channelId.length > 0) {
			notificationManager.clearChannelNotification(channelId);
		}
		refresh();
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(this);
		saveCommentDraft();
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ChannelStateChangedEvent) {
			ChannelStateChangedEvent ev = (ChannelStateChangedEvent) e;
			if (Arrays.equals(ev.getChannelId(), channelId)) {
				runOnUiThreadUnlessDestroyed(this::refresh);
			}
		} else if (e instanceof ChannelCommentReceivedEvent) {
			ChannelCommentReceivedEvent ev = (ChannelCommentReceivedEvent) e;
			if (Arrays.equals(ev.getChannelId(), channelId)
					&& ev.getParentPostSeqNum() == parentSeq) {
				runOnUiThreadUnlessDestroyed(this::refresh);
			}
		}
	}

	private void refresh() {
		ioExecutor.execute(() -> {
			List<ChannelComment> comments;
			boolean enabled = true;
			boolean channelEnabled = true;
			byte[] ownerKey = new byte[0];
			byte[] myKey = new byte[0];
			boolean owner = false;
			try {
				comments = channelManager.getComments(channelId,
						parentSeq);
			} catch (DbException ex) {
				comments = Collections.emptyList();
			}
			List<ChannelQueuedItem> queued = new ArrayList<>();
			try {
				for (ChannelQueuedItem q
						: channelManager.getQueuedItems(channelId)) {
					if (q.getKind() == ChannelQueuedItem.Kind.COMMENT
							&& q.getPostSeqNum() == parentSeq) {
						queued.add(q);
					}
				}
			} catch (DbException ignored) {
			}
			try {
				channelEnabled =
						channelManager.areDiscussionsEnabled(channelId);
				enabled = channelManager.areCommentsEnabled(channelId,
						parentSeq);
				org.zerionproject.app.api.channel.ChannelState s =
						channelManager.getChannel(channelId);
				if (s != null) {
					ownerKey = s.getPublisherEd25519PubKey();
					owner = s.weArePublisher();
				}
				byte[] mine = channelManager.getMyChannelPublicKey(channelId);
				myKey = Arrays.copyOf(mine, Math.min(32, mine.length));
			} catch (DbException ignored) {
			}
			List<ChannelComment> finalComments = comments;
			final boolean finalEnabled = enabled;
			final boolean finalChannelEnabled = channelEnabled;
			final byte[] finalOwnerKey = ownerKey;
			final byte[] finalMyKey = myKey;
			final boolean finalOwner = owner;
			runOnUiThreadUnlessDestroyed(() -> {
				adapter.setKeys(finalOwnerKey, finalMyKey, finalOwner);
				render(finalComments, queued);
				bindComposerEnabled(finalEnabled, finalChannelEnabled);
			});
		});
	}

	private void retryQueued(ChannelQueuedItem item) {
		ioExecutor.execute(() -> {
			try {
				channelManager.retryQueuedItem(channelId, item.getId());
			} catch (DbException ignored) {
			}
			runOnUiThreadUnlessDestroyed(this::refresh);
		});
	}

	private void deleteQueued(ChannelQueuedItem item) {
		ioExecutor.execute(() -> {
			ChannelQueuedItem.DeleteResult result =
					ChannelQueuedItem.DeleteResult.GONE;
			try {
				result = channelManager.deleteQueuedItem(channelId,
						item.getId());
			} catch (DbException ignored) {
			}
			boolean sent = result == ChannelQueuedItem.DeleteResult.SENT;
			runOnUiThreadUnlessDestroyed(() -> {
				if (sent) {
					Toast.makeText(this, R.string.channels_queued_already_sent,
							Toast.LENGTH_SHORT).show();
				}
				refresh();
			});
		});
	}

	private void confirmBanAuthor(ChannelComment comment) {
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this)
				.setTitle(R.string.channels_comment_ban_author)
				.setMessage(R.string.channels_comment_ban_confirm)
				.setPositiveButton(R.string.channels_subscribers_ban,
						(d, w) -> ioExecutor.execute(() -> {
							try {
								channelManager.banSubscriber(channelId,
										comment.getAuthorEd25519PubKey());
								runOnUiThreadUnlessDestroyed(this::refresh);
							} catch (DbException ignored) {
								runOnUiThreadUnlessDestroyed(() ->
										Toast.makeText(this,
												R.string.channels_ban_failed,
												Toast.LENGTH_SHORT).show());
							}
						}))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void bindComposerEnabled(boolean enabled, boolean channelEnabled) {
		composeBar.setVisibility(enabled ? View.VISIBLE : View.GONE);
		disabledNotice.setVisibility(enabled ? View.GONE : View.VISIBLE);
		disabledNotice.setText(channelEnabled
				? R.string.channels_post_comments_off
				: R.string.channels_comments_disabled_compose);
	}

	private void render(List<ChannelComment> comments,
			List<ChannelQueuedItem> queued) {
		List<Row> rows = new ArrayList<>(comments.size() + queued.size());
		for (ChannelComment c : comments) rows.add(new Row(c, null));
		for (ChannelQueuedItem q : queued) rows.add(new Row(null, q));
		if (rows.isEmpty()) {
			recycler.setVisibility(View.GONE);
			emptyView.setVisibility(View.VISIBLE);
		} else {
			recycler.setVisibility(View.VISIBLE);
			emptyView.setVisibility(View.GONE);
			adapter.setItems(rows);
			recycler.scrollToPosition(rows.size() - 1);
		}
	}

	private void handleSend() {
		String body = composeInput.getText() == null
				? "" : composeInput.getText().toString().trim();
		if (body.isEmpty()) return;
		composeInput.setText("");
		ioExecutor.execute(() -> {
			try {
				channelManager.postComment(channelId, parentSeq, body);
				runOnUiThreadUnlessDestroyed(this::refresh);
			} catch (ChannelRequestQueuedException queued) {
				runOnUiThreadUnlessDestroyed(() -> {
					Toast.makeText(this, queued.isBehindEarlierItems()
							? R.string.channels_queued_behind_toast
							: R.string.channels_comment_queued_toast,
							Toast.LENGTH_SHORT).show();
					refresh();
				});
			} catch (org.zerionproject.app.api.channel
					.ChannelNoAccessException noAccess) {
				runOnUiThreadUnlessDestroyed(() -> {
					composeInput.setText(body);
					composeInput.setSelection(body.length());
					Toast.makeText(this, R.string.channels_no_access_toast,
							Toast.LENGTH_LONG).show();
				});
			} catch (DbException ignored) {
				runOnUiThreadUnlessDestroyed(() -> {
					composeInput.setText(body);
					composeInput.setSelection(body.length());
					Toast.makeText(this, R.string.channels_comments_failed,
							Toast.LENGTH_SHORT).show();
				});
			}
		});
	}

	private static final class Row {

		@Nullable
		final ChannelComment comment;
		@Nullable
		final ChannelQueuedItem queued;

		Row(@Nullable ChannelComment comment,
				@Nullable ChannelQueuedItem queued) {
			this.comment = comment;
			this.queued = queued;
		}
	}

	private static class CommentsAdapter
			extends RecyclerView.Adapter<CommentViewHolder> {

		interface OnBan {
			void onBan(ChannelComment comment);
		}

		interface OnQueued {
			void retry(ChannelQueuedItem item);

			void delete(ChannelQueuedItem item);
		}

		private final OnBan onBan;
		private final OnQueued onQueued;
		private List<Row> items = new ArrayList<>();
		private byte[] ownerKey = new byte[0];
		private byte[] myKey = new byte[0];
		private boolean weAreOwner;

		CommentsAdapter(OnBan onBan, OnQueued onQueued) {
			this.onBan = onBan;
			this.onQueued = onQueued;
		}

		void setKeys(byte[] ownerKey, byte[] myKey, boolean weAreOwner) {
			this.ownerKey = ownerKey;
			this.myKey = myKey;
			this.weAreOwner = weAreOwner;
		}

		void setItems(List<Row> rows) {
			this.items = rows;
			notifyDataSetChanged();
		}

		@NonNull
		@Override
		public CommentViewHolder onCreateViewHolder(
				@NonNull ViewGroup parent, int viewType) {
			View v = LayoutInflater.from(parent.getContext()).inflate(
					R.layout.list_item_channel_comment, parent, false);
			return new CommentViewHolder(v);
		}

		@Override
		public void onBindViewHolder(@NonNull CommentViewHolder h,
				int position) {
			Row row = items.get(position);
			ChannelQueuedItem q = row.queued;
			if (q != null || row.comment == null) {
				if (q != null) h.bindQueued(q, onQueued);
				h.itemView.setOnLongClickListener(null);
				h.itemView.setLongClickable(false);
				return;
			}
			ChannelComment c = row.comment;
			byte[] author = c.getAuthorEd25519PubKey();
			boolean byOwner = Arrays.equals(author, ownerKey);
			boolean byMe = Arrays.equals(author, myKey);
			h.bind(c, byOwner, byMe);
			if (weAreOwner && !byOwner && !byMe) {
				h.itemView.setOnLongClickListener(v -> {
					onBan.onBan(c);
					return true;
				});
			} else {
				h.itemView.setOnLongClickListener(null);
				h.itemView.setLongClickable(false);
			}
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}

	private static class CommentViewHolder
			extends RecyclerView.ViewHolder {

		private static String fingerprint(byte[] key) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < Math.min(8, key.length); i++) {
				sb.append(String.format(java.util.Locale.US, "%02x",
						key[i]));
			}
			return sb.toString();
		}

		final TextView author;
		final TextView body;
		final TextView timestamp;
		final View queuedActions;
		final View retryButton;
		final View deleteButton;

		CommentViewHolder(@NonNull View itemView) {
			super(itemView);
			author = itemView.findViewById(R.id.commentAuthor);
			body = itemView.findViewById(R.id.commentBody);
			timestamp = itemView.findViewById(R.id.commentTimestamp);
			queuedActions = itemView.findViewById(R.id.commentQueuedActions);
			retryButton = itemView.findViewById(R.id.commentRetryButton);
			deleteButton = itemView.findViewById(R.id.commentDeleteButton);
		}

		void bindQueued(ChannelQueuedItem q, CommentsAdapter.OnQueued on) {
			android.content.Context ctx = itemView.getContext();
			author.setText(ctx.getString(R.string.channels_comment_you));
			body.setText(q.getText());
			body.setAlpha(q.isNotSent() ? 1f : 0.6f);
			timestamp.setText(!q.isNotSent()
					? R.string.channels_queued_waiting
					: q.getReason() == ChannelQueuedItem.Reason.NO_ACCESS
					? R.string.channels_queued_no_access
					: R.string.channels_queued_not_sent);
			queuedActions.setVisibility(q.isNotSent()
					? View.VISIBLE : View.GONE);
			retryButton.setOnClickListener(v -> on.retry(q));
			deleteButton.setOnClickListener(v -> on.delete(q));
		}

		void bind(ChannelComment c, boolean byOwner, boolean byMe) {
			android.content.Context ctx = itemView.getContext();
			body.setAlpha(1f);
			queuedActions.setVisibility(View.GONE);
			retryButton.setOnClickListener(null);
			deleteButton.setOnClickListener(null);
			String name = c.getAuthorDisplayName().trim();
			StringBuilder label = new StringBuilder(name.isEmpty()
					? ctx.getString(R.string.channels_comment_anonymous)
					: name);
			label.append("  ").append(fingerprint(
					c.getAuthorEd25519PubKey()));
			if (byOwner) {
				label.append("  ").append(
						ctx.getString(R.string.channels_comment_owner));
			} else if (byMe) {
				label.append("  ").append(
						ctx.getString(R.string.channels_comment_you));
			}
			author.setText(label.toString());
			body.setText(c.getBody());
			timestamp.setText(com.professor.zerion.android.util
					.UiUtils.formatChannelHour(itemView.getContext(),
							c.getTimestampHourMs()));
		}
	}
}

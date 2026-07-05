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

import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.briar.api.channel.ChannelComment;
import org.briarproject.briar.api.channel.ChannelManager;
import org.briarproject.briar.api.channel.event.ChannelCommentReceivedEvent;
import org.briarproject.briar.api.channel.event.ChannelStateChangedEvent;
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
		adapter = new CommentsAdapter();
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
		for (byte b : channelId) sb.append(String.format("%02x", b & 0xFF));
		return sb.append('_').append(parentSeq).toString();
	}

	private void restoreCommentDraft() {
		if (channelId.length == 0 || composeInput == null) return;
		String draft = com.professor.zerion.android.AppModule
				.getAndroidComponent(this).securePreferences()
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
				.AppModule.getAndroidComponent(this).securePreferences();
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
			try {
				comments = channelManager.getComments(channelId,
						parentSeq);
			} catch (DbException ex) {
				comments = Collections.emptyList();
			}
			try {
				enabled =
						channelManager.areDiscussionsEnabled(channelId);
			} catch (DbException ignored) {
			}
			Collections.sort(comments, (a, b) ->
					Long.compare(a.getTimestampHourMs(),
							b.getTimestampHourMs()));
			List<ChannelComment> finalComments = comments;
			final boolean finalEnabled = enabled;
			runOnUiThreadUnlessDestroyed(() -> {
				render(finalComments);
				bindComposerEnabled(finalEnabled);
			});
		});
	}

	private void bindComposerEnabled(boolean enabled) {
		composeBar.setVisibility(enabled ? View.VISIBLE : View.GONE);
		disabledNotice.setVisibility(enabled ? View.GONE : View.VISIBLE);
	}

	private void render(List<ChannelComment> comments) {
		if (comments.isEmpty()) {
			recycler.setVisibility(View.GONE);
			emptyView.setVisibility(View.VISIBLE);
		} else {
			recycler.setVisibility(View.VISIBLE);
			emptyView.setVisibility(View.GONE);
			adapter.setItems(comments);
			recycler.scrollToPosition(comments.size() - 1);
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

	private static class CommentsAdapter
			extends RecyclerView.Adapter<CommentViewHolder> {

		private List<ChannelComment> items = new ArrayList<>();

		void setItems(List<ChannelComment> comments) {
			this.items = comments;
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
			h.bind(items.get(position));
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}

	private static class CommentViewHolder
			extends RecyclerView.ViewHolder {

		final TextView author;
		final TextView body;
		final TextView timestamp;

		CommentViewHolder(@NonNull View itemView) {
			super(itemView);
			author = itemView.findViewById(R.id.commentAuthor);
			body = itemView.findViewById(R.id.commentBody);
			timestamp = itemView.findViewById(R.id.commentTimestamp);
		}

		void bind(ChannelComment c) {
			author.setText(c.getAuthorDisplayName());
			body.setText(c.getBody());
			timestamp.setText(com.professor.zerion.android.util
					.UiUtils.formatChannelHour(itemView.getContext(),
							c.getTimestampHourMs()));
		}
	}
}

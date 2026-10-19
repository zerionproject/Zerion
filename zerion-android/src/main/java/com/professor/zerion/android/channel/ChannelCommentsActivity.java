package com.professor.zerion.android.channel;

import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.accessibility.RowReading;
import com.professor.zerion.android.accessibility.RowStates;
import com.professor.zerion.android.message.MessageState;
import com.professor.zerion.android.message.MessageStates;
import com.professor.zerion.android.message.PendingSends;
import com.professor.zerion.android.selection.MessageInfo;
import com.professor.zerion.android.selection.RowActions;
import com.professor.zerion.android.selection.SelectedFill;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionBar;
import com.professor.zerion.android.selection.SelectionRows;
import com.professor.zerion.android.util.Feedback;
import com.professor.zerion.android.util.UiUtils;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelNoAccessException;
import org.zerionproject.app.api.channel.ChannelQueuedItem;
import org.zerionproject.app.api.channel.ChannelRefusedException;
import org.zerionproject.app.api.channel.ChannelRequestQueuedException;
import org.zerionproject.app.api.channel.ChannelSentNotSavedException;
import org.zerionproject.app.api.channel.event.ChannelCommentReceivedEvent;
import org.zerionproject.app.api.channel.event.ChannelStateChangedEvent;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelCommentsActivity extends ZerionActivity
		implements EventListener, SelectionBar.Host {

	private static final String EXTRA_CHANNEL_ID =
			"com.professor.zerion.android.channel.COMMENTS_CHANNEL_ID";
	private static final String EXTRA_PARENT_SEQ =
			"com.professor.zerion.android.channel.COMMENTS_PARENT_SEQ";
	static final String EXTRA_WRITE =
			"com.professor.zerion.android.channel.COMMENTS_WRITE";
	static final String EXTRA_EXPIRES_AT =
			"com.professor.zerion.android.channel.COMMENTS_EXPIRES_AT";

	public static Intent intent(Context ctx, byte[] channelId,
			long parentSeq) {
		Intent i = new Intent(ctx, ChannelCommentsActivity.class);
		i.putExtra(EXTRA_CHANNEL_ID, channelId);
		i.putExtra(EXTRA_PARENT_SEQ, parentSeq);
		return i;
	}

	static Intent intent(Context ctx, byte[] channelId, long parentSeq,
			long expiresAtMs) {
		return intent(ctx, channelId, parentSeq)
				.putExtra(EXTRA_EXPIRES_AT, expiresAtMs);
	}

	static Intent writeIntent(Context ctx, byte[] channelId, long parentSeq) {
		return intent(ctx, channelId, parentSeq).putExtra(EXTRA_WRITE, true);
	}

	static Intent writeIntent(Context ctx, byte[] channelId, long parentSeq,
			long expiresAtMs) {
		return intent(ctx, channelId, parentSeq, expiresAtMs)
				.putExtra(EXTRA_WRITE, true);
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
	private View sendButton;
	private View composeBar;
	private TextView disabledNotice;
	private TextView disappearsLine;
	private long expiresAtMs = 0L;
	private CommentsAdapter adapter;
	private final PendingSends pendingSends = PendingSends.get();
	private final PendingSends.Listener pendingListener =
			this::onPendingChanged;
	private String pendingScope = "";
	private final PendingSends.ClearListener clearListener =
			prefixes -> {
				if (PendingSends.affects(prefixes, pendingScope)) {
					runOnUiThreadUnlessDestroyed(this::onPendingCleared);
				}
			};
	private final java.util.Set<Long> changingQueued =
			new java.util.HashSet<>();
	@Nullable
	private com.google.android.material.snackbar.Snackbar undoBar;
	private String queueScope = "";
	private List<ChannelComment> shownComments = Collections.emptyList();
	private List<ChannelQueuedItem> shownQueued = Collections.emptyList();
	private boolean scrollNext = false;
	private boolean writeRequested = false;
	private SelectionBar selectionBar;
	private final LinkedHashSet<Long> selected = new LinkedHashSet<>();
	private byte[] shownOwnerKey = new byte[0];
	private byte[] shownMyKey = new byte[0];
	private SendNames shownSendNames = new SendNames();

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
		expiresAtMs = getIntent().getLongExtra(EXTRA_EXPIRES_AT, 0L);
		writeRequested = savedInstanceState == null
				&& getIntent().getBooleanExtra(EXTRA_WRITE, false);
		queueScope = queueScope(channelId);
		pendingScope = PendingSends.channelCommentScope(channelId, parentSeq);
		pendingSends.addClearListener(clearListener);

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
		disappearsLine = findViewById(R.id.commentsDisappearsLine);
		bindDisappearsLine();
		adapter = new CommentsAdapter(new CommentsAdapter.OnSelect() {
					@Override
					public void onLongPress(ChannelComment comment) {
						startOrToggle(comment);
					}

					@Override
					public void onTap(ChannelComment comment) {
						if (selectionBar.isShowing()) toggle(comment);
					}

					@Override
					public void onAuthorTap(ChannelComment comment) {
						if (selectionBar.isShowing()) {
							toggle(comment);
						} else {
							showMember(comment);
						}
					}

					@Override
					public Set<SelectionAction> rowActions(
							ChannelComment comment) {
						return ChannelCommentsActivity.this.rowActions(comment);
					}

					@Override
					public boolean onRowAction(ChannelComment comment,
							SelectionAction action) {
						return ChannelCommentsActivity.this.onRowAction(
								comment, action);
					}
				}, selected,
				new CommentsAdapter.OnQueued() {
					@Override
					public void retry(ChannelQueuedItem item) {
						retryQueued(item);
					}

					@Override
					public void delete(ChannelQueuedItem item) {
						deleteQueued(item, false);
					}

					@Override
					public void edit(ChannelQueuedItem item) {
						deleteQueued(item, true);
					}

					@Override
					public void retry(PendingSends.Entry entry) {
						retryPending(entry);
					}

					@Override
					public void delete(PendingSends.Entry entry) {
						deletePending(entry);
					}

					@Override
					public void edit(PendingSends.Entry entry) {
						editPending(entry);
					}

					@Override
					public boolean composerShown() {
						return ChannelCommentsActivity.this.composerShown();
					}
				});
		recycler.setLayoutManager(new LinearLayoutManager(this));
		recycler.setAdapter(adapter);
		selectionBar = new SelectionBar(this, this);
		SelectionRows.clearOnEmptyTap(recycler, selectionBar);
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

	static String queueScope(byte[] channelId) {
		return PendingSends.channelQueueScope(channelId);
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
		if (adapter != null) adapter.setMessageStyle(com.professor.zerion.android.settings.MessageStyle.read(this));
		eventBus.addListener(this);
		pendingSends.addListener(pendingListener, pendingScope, queueScope);
		if (channelId.length > 0) {
			notificationManager.clearChannelNotification(channelId);
		}
		refresh();
	}

	@Override
	public void onStop() {
		super.onStop();
		eventBus.removeListener(this);
		pendingSends.removeListener(pendingListener);
		saveCommentDraft();
	}

	@Override
	public void onDestroy() {
		pendingSends.removeClearListener(clearListener);
		super.onDestroy();
	}

	private void onPendingCleared() {
		if (undoBar != null) {
			undoBar.dismiss();
			undoBar = null;
		}
		render();
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
			try {
				comments = channelManager.getComments(channelId,
						parentSeq);
			} catch (DbException ex) {
				comments = Collections.emptyList();
			}
			List<ChannelQueuedItem> queued = new ArrayList<>();
			List<ChannelQueuedItem> queue = Collections.emptyList();
			try {
				queue = channelManager.getQueuedItems(channelId);
				for (ChannelQueuedItem q : queue) {
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
				if (s != null) ownerKey = s.getPublisherEd25519PubKey();
				byte[] mine = channelManager.getMyChannelPublicKey(channelId);
				myKey = Arrays.copyOf(mine, Math.min(32, mine.length));
			} catch (DbException ignored) {
			}
			SendNames names = sendNames(queue, parentSeq,
					storedOwnName(myKey));
			List<ChannelComment> finalComments = comments;
			final boolean finalEnabled = enabled;
			final boolean finalChannelEnabled = channelEnabled;
			final byte[] finalOwnerKey = ownerKey;
			final byte[] finalMyKey = myKey;
			runOnUiThreadUnlessDestroyed(() -> {
				adapter.setKeys(finalOwnerKey, finalMyKey);
				shownOwnerKey = finalOwnerKey;
				shownMyKey = finalMyKey;
				shownSendNames = names;
				shownComments = finalComments;
				shownQueued = queued;
				pendingSends.settle(pendingScope, shown());
				bindDisappearsLine();
				bindComposerEnabled(finalEnabled, finalChannelEnabled);
				takeBackReturned();
				render();
				showHeldNotices();
			});
		});
	}

	private void onPendingChanged(PendingSends.Change change) {
		boolean mine = change.getScope().equals(pendingScope);
		if (!mine && !change.getScope().equals(queueScope)) return;
		runOnUiThreadUnlessDestroyed(() -> {
			PendingSends.Notice notice = change.getNotice();
			switch (change.getKind()) {
				case STORED:
				case HANDED_OVER:
					if (notice != null) feedback(notice.text(this));
					render();
					refresh();
					break;
				case FAILED:
					render();
					List<PendingSends.Entry> again =
							pendingSends.retryable(pendingScope);
					CharSequence text = notice != null ? notice.text(this)
							: getResources().getQuantityString(
									R.plurals.message_state_could_not_send,
									again.size(), again.size());
					Feedback.show(composeBar, text, again.isEmpty() ? 0
							: R.string.message_state_retry,
							again.isEmpty() ? null : this::retryAllNotSent);
					break;
				case RETURNED:
					takeBackReturned();
					render();
					if (notice != null) feedback(notice.text(this));
					break;
				case REMOVED:
					render();
					showHeldNotices();
					break;
				default:
					render();
					break;
			}
		});
	}

	private PendingSends.Shown shown() {
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (ChannelComment c : shownComments) {
			ids.add(identity(c.getCommentId()));
		}
		for (ChannelQueuedItem q : shownQueued) ids.add(identity(q.getId()));
		return ids::contains;
	}

	static String identity(long commentId) {
		return "comment:" + commentId;
	}

	static PendingSends.Attempt commentAttempt(ChannelManager manager,
			byte[] channelId, long parentSeq, String body) {
		return () -> {
			try {
				long id = manager.postCommentWithId(channelId, parentSeq,
						body);
				return PendingSends.Result.stored(identity(id), null);
			} catch (ChannelRequestQueuedException queued) {
				return PendingSends.Result.handedOver(
						queued.getItemId() == -1L ? null
								: identity(queued.getItemId()),
						PendingSends.Notice.of(queued.isBehindEarlierItems()
								? R.string.channels_queued_behind_toast
								: R.string.channels_comment_queued_toast));
			} catch (ChannelSentNotSavedException sent) {
				return PendingSends.Result.stored(identity(sent.getItemId()),
						null);
			} catch (ChannelNoAccessException noAccess) {
				return PendingSends.Result.refused(PendingSends.Notice.of(
						R.string.channels_no_access_toast));
			} catch (ChannelRefusedException refused) {
				return PendingSends.Result.refused(PendingSends.Notice.of(
						R.string.channels_comments_failed));
			} catch (DbException ex) {
				return PendingSends.Result.failed(PendingSends.Notice.of(
						R.string.channels_comments_failed));
			}
		};
	}

	private void showHeldNotices() {
		for (PendingSends.Notice n : pendingSends.takeNotices(pendingScope)) {
			feedback(n.text(this));
		}
	}

	private boolean composerShown() {
		return composeBar.getVisibility() == View.VISIBLE;
	}

	private boolean composerFree() {
		return composerShown() && composeInput.length() == 0;
	}

	private void takeBackReturned() {
		if (!composerFree()) return;
		for (PendingSends.Entry e : pendingSends.entries(pendingScope)) {
			if (e.isReturned() && e.canEdit()) {
				editPending(e);
				return;
			}
		}
	}

	private void putInComposer(String text) {
		composeInput.setText(text);
		composeInput.setSelection(text.length());
	}

	private void copy(String text) {
		com.professor.zerion.android.util.SecureClipboard.copy(this,
				"message", text);
		feedback(getText(R.string.copied_to_clipboard));
	}

	private void retryPending(PendingSends.Entry entry) {
		pendingSends.retry(entry, ioExecutor);
		render();
	}

	private void retryAllNotSent() {
		for (PendingSends.Entry e : pendingSends.retryable(pendingScope)) {
			retryPending(e);
		}
	}

	private void deletePending(PendingSends.Entry entry) {
		if (!pendingSends.discard(entry)) {
			render();
			return;
		}
		render();
		undoBar = Feedback.show(composeBar,
				getText(R.string.message_state_deleted),
				R.string.message_state_undo, () -> {
					undoBar = null;
					pendingSends.restore(entry);
					render();
				});
	}

	private void editPending(PendingSends.Entry entry) {
		if (!entry.canEdit()) {
			render();
			return;
		}
		if (!composerShown()) {
			copy(entry.getText());
			render();
			return;
		}
		if (composeInput.length() > 0) {
			feedback(getText(R.string.message_state_edit_busy));
			render();
			return;
		}
		if (!pendingSends.discard(entry)) {
			render();
			return;
		}
		putInComposer(entry.getText());
		render();
	}

	private void retryQueued(ChannelQueuedItem item) {
		if (!pendingSends.beginQueuedRetry(queueScope, item.getId())) {
			render();
			return;
		}
		ioExecutor.execute(() -> {
			boolean failed = false;
			try {
				channelManager.retryQueuedItem(channelId, item.getId());
			} catch (DbException ex) {
				failed = stillQueued(item.getId());
			} finally {
				pendingSends.endQueuedRetry(queueScope, item.getId());
			}
			boolean finalFailed = failed;
			runOnUiThreadUnlessDestroyed(() -> {
				if (finalFailed) {
					feedback(getText(R.string.channels_comments_failed));
				}
				refresh();
			});
		});
	}

	private boolean stillQueued(long itemId) {
		try {
			for (ChannelQueuedItem q : channelManager.getQueuedItems(
					channelId)) {
				if (q.getId() == itemId) return true;
			}
			return false;
		} catch (DbException e) {
			return true;
		}
	}

	private void deleteQueued(ChannelQueuedItem item, boolean edit) {
		if (edit && !composerShown()) {
			copy(item.getText());
			render();
			return;
		}
		if (edit && composeInput.length() > 0) {
			feedback(getText(R.string.message_state_edit_busy));
			render();
			return;
		}
		if (!changingQueued.add(item.getId())) {
			render();
			return;
		}
		ioExecutor.execute(() -> {
			ChannelQueuedItem.DeleteResult result = null;
			try {
				result = channelManager.deleteQueuedItem(channelId,
						item.getId());
			} catch (DbException ignored) {
			}
			ChannelQueuedItem.DeleteResult finalResult = result;
			runOnUiThreadUnlessDestroyed(() -> {
				changingQueued.remove(item.getId());
				if (finalResult == null) {
					feedback(getText(R.string.delete_failed));
				} else if (finalResult
						== ChannelQueuedItem.DeleteResult.SENT) {
					feedback(getText(R.string.channels_queued_already_sent));
				} else if (finalResult
						== ChannelQueuedItem.DeleteResult.GONE) {
					feedback(getText(R.string.message_state_maybe_sent));
				} else if (edit && composerFree()) {
					putInComposer(item.getText());
				} else {
					keepUnsent(item.getText(), !edit);
				}
				refresh();
			});
		});
	}

	private void keepUnsent(String text, boolean offerUndo) {
		Runnable restore = () -> {
			pendingSends.addNotSent(pendingScope, text, null, text.length(),
					true, commentAttempt(channelManager, channelId,
							parentSeq, text));
			render();
		};
		if (!offerUndo) {
			restore.run();
			return;
		}
		undoBar = Feedback.show(composeBar,
				getText(R.string.message_state_deleted),
				R.string.message_state_undo, () -> {
					undoBar = null;
					restore.run();
				});
	}

	private void feedback(CharSequence text) {
		Feedback.show(composeBar, text);
	}

	private void showMember(ChannelComment comment) {
		ChannelMemberSheet.open(this, channelManager, ioExecutor, channelId,
				comment.getAuthorEd25519PubKey(),
				comment.getAuthorDisplayName(),
				comment.getAuthorMlDsaPubKey(), this::refresh);
	}

	private void bindDisappearsLine() {
		long remaining = expiresAtMs - System.currentTimeMillis();
		if (expiresAtMs <= 0L || remaining <= 0L) {
			disappearsLine.setVisibility(View.GONE);
			return;
		}
		disappearsLine.setText(getString(R.string.channels_post_disappears,
				ChannelFeedActivity.PostViewHolder.formatRemaining(this,
						remaining)));
		disappearsLine.setVisibility(View.VISIBLE);
	}

	private void bindComposerEnabled(boolean enabled, boolean channelEnabled) {
		composeBar.setVisibility(enabled ? View.VISIBLE : View.GONE);
		disabledNotice.setVisibility(enabled ? View.GONE : View.VISIBLE);
		disabledNotice.setText(channelEnabled
				? R.string.channels_post_comments_off
				: R.string.channels_comments_disabled_compose);
		if (enabled && writeRequested) {
			writeRequested = false;
			composeInput.requestFocus();
			UiUtils.showSoftKeyboard(composeInput);
		}
	}

	private boolean atBottom() {
		RecyclerView.LayoutManager lm = recycler.getLayoutManager();
		if (!(lm instanceof LinearLayoutManager)) return true;
		int last = ((LinearLayoutManager) lm).findLastVisibleItemPosition();
		return last == RecyclerView.NO_POSITION
				|| last >= adapter.getItemCount() - 1;
	}

	private void render() {
		boolean pin = scrollNext || atBottom();
		scrollNext = false;
		List<PendingSends.Entry> pending = PendingSends.visible(
				pendingSends.entries(pendingScope), shown());
		List<Row> rows = new ArrayList<>(shownComments.size()
				+ shownQueued.size() + pending.size());
		for (ChannelComment c : shownComments) rows.add(new Row(c, null));
		adapter.setAuthorLabels(authorLabels());
		for (ChannelQueuedItem q : shownQueued) {
			Row row = new Row(null, q);
			row.retrying = pendingSends.isQueuedRetry(queueScope, q.getId());
			row.sendName = shownSendNames.forQueued(q.getId());
			rows.add(row);
		}
		for (PendingSends.Entry e : pending) {
			Row row = Row.pending(e);
			row.sendName = shownSendNames.afterQueue;
			rows.add(row);
		}
		labelOwnRows(rows);
		if (rows.isEmpty()) {
			adapter.setItems(rows);
			recycler.setVisibility(View.GONE);
			emptyView.setVisibility(View.VISIBLE);
		} else {
			recycler.setVisibility(View.VISIBLE);
			emptyView.setVisibility(View.GONE);
			adapter.setItems(rows);
			if (pin) recycler.scrollToPosition(rows.size() - 1);
		}
		dropVanishedFromSelection();
	}

	static final class SendNames {

		final Map<Long, String> queued = new java.util.HashMap<>();
		String afterQueue = "";

		String forQueued(long itemId) {
			String name = queued.get(itemId);
			return name == null ? afterQueue : name;
		}
	}

	static String sentName(String name) {
		int max = org.zerionproject.app.api.channel.ChannelConstants
				.MAX_COMMENT_AUTHOR_NAME_CHARS;
		if (name.length() > max) {
			int end = Character.isHighSurrogate(name.charAt(max - 1))
					? max - 1 : max;
			name = name.substring(0, end);
		}
		return name.trim();
	}

	static SendNames sendNames(List<ChannelQueuedItem> queue, long parentSeq,
			String stored) {
		SendNames out = new SendNames();
		String name = sentName(stored);
		for (ChannelQueuedItem q : queue) {
			if (q.getKind() == ChannelQueuedItem.Kind.ANNOUNCE
					&& !q.isNotSent()) {
				name = sentName(q.getText());
			} else if (q.getKind() == ChannelQueuedItem.Kind.COMMENT
					&& q.getPostSeqNum() == parentSeq) {
				out.queued.put(q.getId(), name);
			}
		}
		out.afterQueue = name;
		return out;
	}

	private String storedOwnName(byte[] myKey) {
		if (myKey.length == 0) return "";
		try {
			for (org.zerionproject.app.api.channel.ChannelSubscriber sub
					: channelManager.getAnnouncedSubscribers(channelId)) {
				if (Arrays.equals(sub.getEd25519PubKey(), myKey)) {
					return sub.getDisplayName();
				}
			}
		} catch (DbException ignored) {
		}
		return "";
	}

	private void labelOwnRows(List<Row> rows) {
		List<ChannelComment> named = othersAndMyNamed();
		boolean any = false;
		for (Row row : rows) {
			long at = row.queued != null ? row.queued.getQueuedAtMs()
					: row.pending != null ? row.pending.getCreatedAtMs() : -1L;
			if (at < 0L || row.sendName.isEmpty()) continue;
			named.add(new ChannelComment(parentSeq, 0L, "", row.sendName,
					shownMyKey, new byte[0], at / HOUR_MS * HOUR_MS));
			any = true;
		}
		if (!any) return;
		String mine = labelsOf(named).get(
				org.zerionproject.core.util.StringUtils.toHexString(
						shownMyKey));
		for (Row row : rows) {
			if ((row.queued != null || row.pending != null)
					&& !row.sendName.isEmpty() && mine != null) {
				row.sendName = mine;
			}
		}
	}

	private static final long HOUR_MS = 60L * 60L * 1000L;

	private List<ChannelComment> othersAndMyNamed() {
		List<ChannelComment> others = new ArrayList<>(shownComments.size());
		for (ChannelComment c : shownComments) {
			if (!mineWithoutName(c)) others.add(c);
		}
		return others;
	}

	private Map<String, String> authorLabels() {
		return labelsOf(othersAndMyNamed());
	}

	private Map<String, String> labelsOf(List<ChannelComment> others) {
		return ChannelAuthorLabels.of(others,
				getString(R.string.channels_delegations_unnamed_person),
				getString(R.string.channels_comment_author_first_wrote),
				new ChannelAuthorLabels.DayFormatter() {
					@Override
					public String day(long hourMs) {
						return android.text.format.DateUtils.formatDateTime(
								ChannelCommentsActivity.this, hourMs,
								android.text.format.DateUtils.FORMAT_SHOW_DATE
										| android.text.format.DateUtils
												.FORMAT_ABBREV_MONTH);
					}

					@Override
					public String dayAndHour(long hourMs) {
						return com.professor.zerion.android.util.HourTimes
								.withDay(ChannelCommentsActivity.this, hourMs,
										System.currentTimeMillis());
					}
				});
	}

	private String authorLabel(ChannelComment c) {
		return ChannelAuthorLabels.label(authorLabels(), c,
				getString(R.string.channels_delegations_unnamed_person));
	}

	private void startOrToggle(ChannelComment comment) {
		if (!selectionBar.isShowing()) {
			selected.clear();
			selected.add(comment.getCommentId());
			adapter.notifyDataSetChanged();
			selectionBar.show(1);
			return;
		}
		toggle(comment);
	}

	private void toggle(ChannelComment comment) {
		long id = comment.getCommentId();
		if (!selected.remove(id)) selected.add(id);
		if (selected.isEmpty()) {
			adapter.notifyDataSetChanged();
		} else {
			adapter.refreshComment(id);
		}
		updateSelectionBar();
	}

	private void updateSelectionBar() {
		if (selected.isEmpty()) {
			selectionBar.close();
		} else {
			selectionBar.show(selected.size());
		}
	}

	private void dropVanishedFromSelection() {
		if (selected.isEmpty()) return;
		Set<Long> shown = new java.util.HashSet<>();
		for (ChannelComment c : shownComments) shown.add(c.getCommentId());
		if (selected.retainAll(shown)) {
			adapter.notifyDataSetChanged();
			updateSelectionBar();
		} else {
			selectionBar.refresh();
		}
	}

	private List<ChannelComment> selectedComments() {
		List<ChannelComment> out = new ArrayList<>();
		for (ChannelComment c : shownComments) {
			if (selected.contains(c.getCommentId())) out.add(c);
		}
		return out;
	}

	private void finishSelection() {
		if (selectionBar.isShowing()) {
			selectionBar.close();
		} else {
			onSelectionClosed();
		}
	}

	private boolean byOwner(ChannelComment c) {
		return Arrays.equals(c.getAuthorEd25519PubKey(), shownOwnerKey);
	}

	private boolean byMe(ChannelComment c) {
		return Arrays.equals(c.getAuthorEd25519PubKey(), shownMyKey);
	}

	private boolean mineWithoutName(ChannelComment c) {
		return byMe(c) && c.getAuthorDisplayName().trim().isEmpty();
	}

	@Override
	public Set<SelectionAction> offeredActions() {
		return offeredFor(selectedComments());
	}

	private Set<SelectionAction> rowActions(ChannelComment c) {
		if (selectionBar.isShowing()) return RowActions.none();
		return RowActions.forRow(offeredFor(
				java.util.Collections.singletonList(c)));
	}

	private boolean onRowAction(ChannelComment c, SelectionAction action) {
		if (!rowActions(c).contains(action)) return false;
		return selectionBar.performOnly(() -> startOrToggle(c), action,
				selected::size);
	}

	private EnumSet<SelectionAction> offeredFor(List<ChannelComment> chosen) {
		EnumSet<SelectionAction> offered =
				EnumSet.noneOf(SelectionAction.class);
		if (chosen.isEmpty()) return offered;
		boolean text = true;
		for (ChannelComment c : chosen) {
			if (c.getBody().trim().isEmpty()) text = false;
		}
		if (text) offered.add(SelectionAction.COPY);
		if (chosen.size() == 1) {
			offered.add(SelectionAction.MEMBER);
			offered.add(SelectionAction.INFO);
		}
		return offered;
	}

	@Override
	public void onSelectionAction(SelectionAction action) {
		List<ChannelComment> chosen = selectedComments();
		finishSelection();
		if (chosen.isEmpty()) return;
		switch (action) {
			case COPY:
				StringBuilder sb = new StringBuilder();
				for (ChannelComment c : chosen) {
					if (sb.length() > 0) sb.append('\n');
					sb.append(c.getBody());
				}
				copy(sb.toString());
				break;
			case INFO:
				showInfo(chosen.get(0));
				break;
			case MEMBER:
				showMember(chosen.get(0));
				break;
			default:
				break;
		}
	}

	@Override
	public void onSelectionClosed() {
		if (selected.isEmpty()) return;
		selected.clear();
		adapter.notifyDataSetChanged();
	}

	private void showInfo(ChannelComment c) {
		String from = byMe(c) ? getString(R.string.channels_comment_you)
				: byOwner(c) ? getString(R.string.channels_comment_owner)
				: authorLabel(c);
		new MessageInfo(this)
				.line(R.string.message_info_from, from)
				.line(R.string.message_info_posted, UiUtils.formatChannelHour(
						this, c.getTimestampHourMs()))
				.show();
	}

	private void handleSend() {
		String body = composeInput.getText() == null
				? "" : composeInput.getText().toString().trim();
		if (body.isEmpty()) return;
		PendingSends.Entry pending = pendingSends.add(pendingScope, body,
				null, body.length(), true, commentAttempt(channelManager,
						channelId, parentSeq, body));
		scrollNext = true;
		render();
		composeInput.setText("");
		pendingSends.start(pending, ioExecutor);
	}

	private static final class Row {

		@Nullable
		final ChannelComment comment;
		@Nullable
		final ChannelQueuedItem queued;
		@Nullable
		PendingSends.Entry pending;
		boolean retrying;
		String sendName = "";

		Row(@Nullable ChannelComment comment,
				@Nullable ChannelQueuedItem queued) {
			this.comment = comment;
			this.queued = queued;
		}

		static Row pending(PendingSends.Entry entry) {
			Row row = new Row(null, null);
			row.pending = entry;
			return row;
		}
	}

	private static class CommentsAdapter
			extends RecyclerView.Adapter<CommentViewHolder> {

		interface OnSelect {
			void onLongPress(ChannelComment comment);

			void onTap(ChannelComment comment);

			void onAuthorTap(ChannelComment comment);

			Set<SelectionAction> rowActions(ChannelComment comment);

			boolean onRowAction(ChannelComment comment,
					SelectionAction action);
		}

		interface OnQueued {
			void retry(ChannelQueuedItem item);

			void delete(ChannelQueuedItem item);

			void edit(ChannelQueuedItem item);

			void retry(PendingSends.Entry entry);

			void delete(PendingSends.Entry entry);

			void edit(PendingSends.Entry entry);

			boolean composerShown();
		}

		private final OnSelect onSelect;
		private final Set<Long> selected;
		private final OnQueued onQueued;
		private List<Row> items = new ArrayList<>();
		private byte[] ownerKey = new byte[0];
		private byte[] myKey = new byte[0];
		private Map<String, String> authorLabels = Collections.emptyMap();

		private final com.professor.zerion.android.settings.MessageStyle.Source messageStyle =
				new com.professor.zerion.android.settings.MessageStyle.Source();

		CommentsAdapter(OnSelect onSelect, @Nullable Set<Long> selected,
				OnQueued onQueued) {
			this.onSelect = onSelect;
			this.selected = selected == null
					? Collections.emptySet() : selected;
			this.onQueued = onQueued;
		}

		void setMessageStyle(com.professor.zerion.android.settings.MessageStyle style) {
			if (messageStyle.update(style)) notifyDataSetChanged();
		}

		void setKeys(byte[] ownerKey, byte[] myKey) {
			this.ownerKey = ownerKey;
			this.myKey = myKey;
		}

		void setAuthorLabels(Map<String, String> labels) {
			this.authorLabels = labels;
		}

		void setItems(List<Row> rows) {
			this.items = rows;
			notifyDataSetChanged();
		}

		void refreshComment(long id) {
			for (int i = 0; i < items.size(); i++) {
				ChannelComment c = items.get(i).comment;
				if (c != null && c.getCommentId() == id) notifyItemChanged(i);
			}
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
			messageStyle.get(h.itemView.getContext()).applyTextSize(h.body);
			RowReading.oneItem(h.itemView);
			Row row = items.get(position);
			ChannelQueuedItem q = row.queued;
			PendingSends.Entry pending = row.pending;
			boolean meOwner = myKey.length > 0
					&& Arrays.equals(myKey, ownerKey);
			if (pending != null) {
				h.bindPending(pending, onQueued);
				h.ownHeading(row.sendName, meOwner);
			} else if (q != null) {
				h.bindQueued(q, row.retrying, onQueued);
				h.ownHeading(row.sendName, meOwner);
			}
			if (q != null || pending != null || row.comment == null) {
				SelectionRows.mark(h.itemView, false);
				SelectionRows.notSelectable(h.itemView);
				h.itemView.setOnClickListener(null);
				h.itemView.setClickable(false);
				h.author.setOnClickListener(null);
				h.author.setClickable(false);
				h.author.setMinHeight(0);
				RowActions.bind(h.itemView, null);
				return;
			}
			ChannelComment c = row.comment;
			byte[] author = c.getAuthorEd25519PubKey();
			boolean byOwner = Arrays.equals(author, ownerKey);
			boolean byMe = Arrays.equals(author, myKey);
			h.bind(c, byOwner, byMe, ChannelAuthorLabels.label(authorLabels,
					c, h.itemView.getContext().getString(
							R.string.channels_delegations_unnamed_person)));
			SelectionRows.mark(h.itemView,
					selected.contains(c.getCommentId()));
			h.itemView.setOnLongClickListener(v -> {
				onSelect.onLongPress(c);
				return true;
			});
			SelectionRows.nameLongPress(h.itemView);
			h.author.setOnClickListener(v -> onSelect.onAuthorTap(c));
			h.author.setMinHeight(h.authorTarget);
			RowReading.partOf(h.author);
			RowActions.bind(h.itemView, new RowActions.Row() {
				@Override
				public Set<SelectionAction> offered() {
					return onSelect.rowActions(c);
				}

				@Override
				public boolean perform(SelectionAction action) {
					return onSelect.onRowAction(c, action);
				}
			});
			if (selected.isEmpty()) {
				h.itemView.setOnClickListener(null);
				h.itemView.setClickable(false);
			} else {
				h.itemView.setOnClickListener(v -> onSelect.onTap(c));
			}
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}

	private static class CommentViewHolder
			extends RecyclerView.ViewHolder {

		final TextView author;
		final TextView authorTag;
		final TextView body;
		final TextView timestamp;
		final TextView reason;
		final View card;
		final View actions;
		private final ColorStateList accentText;
		private final ColorStateList onIn;
		private final ColorStateList onInMuted;
		private final ColorStateList onOut;
		private final ColorStateList onOutMuted;
		private final int ownFill;
		private final int tailMargin;
		private final int nonTailMargin;
		private final int authorTarget;
		private boolean outgoing;

		CommentViewHolder(@NonNull View itemView) {
			super(itemView);
			author = itemView.findViewById(R.id.commentAuthor);
			authorTag = itemView.findViewById(R.id.commentAuthorTag);
			body = itemView.findViewById(R.id.commentBody);
			timestamp = itemView.findViewById(R.id.commentTimestamp);
			reason = itemView.findViewById(R.id.commentStateReason);
			card = itemView.findViewById(R.id.commentCard);
			actions = itemView.findViewById(R.id.messageNotSentActions);
			authorTarget = author.getMinHeight();
			android.content.Context ctx = itemView.getContext();
			accentText = AppCompatResources.getColorStateList(ctx,
					R.color.selectable_accent_text);
			onIn = ColorStateList.valueOf(UiUtils.resolveColorAttribute(ctx,
					R.attr.zerionColorOnBubbleIn));
			onInMuted = AppCompatResources.getColorStateList(ctx,
					R.color.selectable_on_bubble_in_muted);
			onOut = ColorStateList.valueOf(UiUtils.resolveColorAttribute(ctx,
					R.attr.zerionColorOnBubbleOut));
			onOutMuted = ColorStateList.valueOf(UiUtils.resolveColorAttribute(
					ctx, R.attr.zerionColorOnBubbleOutMuted));
			ownFill = UiUtils.resolveColorAttribute(ctx,
					R.attr.zerionColorBubbleOut);
			tailMargin = ctx.getResources().getDimensionPixelSize(
					R.dimen.message_bubble_margin_tail);
			nonTailMargin = ctx.getResources().getDimensionPixelSize(
					R.dimen.message_bubble_margin_non_tail);
			outgoing = false;
		}

		private void side(boolean mine) {
			outgoing = mine;
			card.setBackgroundResource(mine ? R.drawable.msg_out
					: R.drawable.msg_in);
			if (mine) SelectedFill.own(card, ownFill);
			else SelectedFill.received(card);
			ViewGroup.LayoutParams raw = card.getLayoutParams();
			if (raw instanceof android.widget.LinearLayout.LayoutParams) {
				android.widget.LinearLayout.LayoutParams lp =
						(android.widget.LinearLayout.LayoutParams) raw;
				lp.gravity = mine ? android.view.Gravity.END
						: android.view.Gravity.START;
				lp.setMarginStart(mine ? nonTailMargin : tailMargin);
				lp.setMarginEnd(mine ? tailMargin : nonTailMargin);
				card.setLayoutParams(lp);
			}
			author.setTextColor(mine ? onOut : accentText);
			authorTag.setTextColor(mine ? onOutMuted : onInMuted);
			body.setTextColor(mine ? onOut : onIn);
			body.setLinkTextColor(mine ? onOut : accentText);
			timestamp.setTextColor(mine ? onOutMuted : onInMuted);
			reason.setTextColor(mine ? onOutMuted : onInMuted);
		}

		private void bindState(String text, long timeMs,
				@Nullable MessageState state, @Nullable Runnable edit,
				boolean editCopies, @Nullable Runnable retry,
				@Nullable Runnable delete) {
			android.content.Context ctx = itemView.getContext();
			MessageStates.bind(timestamp, com.professor.zerion.android.util
					.UiUtils.formatChannelHour(ctx, timeMs), state);
			MessageStates.outline(card, state == MessageState.NOT_SENT,
					outgoing ? R.drawable.bg_message_not_sent_out
							: R.drawable.bg_message_not_sent_in);
			MessageStates.actions(actions, text, edit, editCopies, retry,
					delete);
		}

		void ownHeading(String label, boolean owner) {
			android.content.Context ctx = itemView.getContext();
			if (label.isEmpty()) {
				author.setText(ctx.getString(R.string.channels_comment_you));
				authorTag.setVisibility(View.GONE);
				return;
			}
			author.setText(label);
			authorTag.setText(ctx.getString(owner
					? R.string.channels_comment_owner
					: R.string.channels_comment_you));
			authorTag.setVisibility(View.VISIBLE);
		}

		void bindQueued(ChannelQueuedItem q, boolean retrying,
				CommentsAdapter.OnQueued on) {
			android.content.Context ctx = itemView.getContext();
			side(true);
			author.setText(ctx.getString(R.string.channels_comment_you));
			authorTag.setVisibility(View.GONE);
			body.setText(q.getText());
			boolean notSent = q.isNotSent() && !retrying;
			MessageState state = retrying ? MessageState.SENDING
					: notSent ? MessageState.NOT_SENT : MessageState.WAITING;
			bindState(q.getText(), q.getQueuedAtMs(), state,
					notSent ? () -> on.edit(q) : null, !on.composerShown(),
					notSent ? () -> on.retry(q) : null,
					notSent ? () -> on.delete(q) : null);
			RowStates.of(timestamp).saw(timestamp, "queued:" + q.getId(),
					state, q::getText);
			boolean noAccess = notSent
					&& q.getReason() == ChannelQueuedItem.Reason.NO_ACCESS;
			reason.setVisibility(noAccess ? View.VISIBLE : View.GONE);
			if (noAccess) reason.setText(R.string.channels_queued_no_access);
		}

		void bindPending(PendingSends.Entry e, CommentsAdapter.OnQueued on) {
			android.content.Context ctx = itemView.getContext();
			side(true);
			author.setText(ctx.getString(R.string.channels_comment_you));
			authorTag.setVisibility(View.GONE);
			body.setText(e.getText());
			bindState(e.getText(), e.getCreatedAtMs(), e.getState(),
					e.canEdit() ? () -> on.edit(e) : null,
					!on.composerShown(),
					e.canRetry() ? () -> on.retry(e) : null,
					e.canDiscard() ? () -> on.delete(e) : null);
			RowStates.of(timestamp).saw(timestamp, "pending:" + e.getId(),
					e.getState(), e::getText);
			reason.setVisibility(View.GONE);
		}

		void bind(ChannelComment c, boolean byOwner, boolean byMe,
				String authorLabel) {
			android.content.Context ctx = itemView.getContext();
			side(byMe);
			bindState("", c.getTimestampHourMs(), null, null, false, null,
					null);
			reason.setVisibility(View.GONE);
			boolean youWithoutName = byMe
					&& c.getAuthorDisplayName().trim().isEmpty();
			author.setText(youWithoutName
					? ctx.getString(R.string.channels_comment_you)
					: authorLabel);
			if (youWithoutName) {
				authorTag.setVisibility(View.GONE);
			} else if (byOwner) {
				authorTag.setText(ctx.getString(R.string.channels_comment_owner));
				authorTag.setVisibility(View.VISIBLE);
			} else if (byMe) {
				authorTag.setText(ctx.getString(R.string.channels_comment_you));
				authorTag.setVisibility(View.VISIBLE);
			} else {
				authorTag.setVisibility(View.GONE);
			}
			body.setText(c.getBody());
		}
	}
}

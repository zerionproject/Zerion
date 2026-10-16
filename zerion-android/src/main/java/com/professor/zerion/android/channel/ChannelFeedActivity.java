package com.professor.zerion.android.channel;

import com.professor.zerion.android.vault.utils.SecureMemory;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
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
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.attachment.SaveCleanCopy;

import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.event.Event;
import org.zerionproject.core.api.event.EventBus;
import org.zerionproject.core.api.event.EventListener;
import org.zerionproject.core.api.lifecycle.IoExecutor;
import org.zerionproject.app.api.channel.ApplicationStatus;
import org.zerionproject.app.api.channel.AttachmentBlob;
import org.zerionproject.app.api.channel.AttachmentSpec;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelComment;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelPost;
import org.zerionproject.app.api.channel.ChannelQueuedItem;
import org.zerionproject.app.api.channel.ChannelReaction;
import org.zerionproject.app.api.channel.ChannelRequestQueuedException;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSyncStatus;
import org.zerionproject.app.api.channel.event.ChannelPostReceivedEvent;
import org.zerionproject.app.api.channel.event.ChannelCommentReceivedEvent;
import org.zerionproject.app.api.channel.event.ChannelStateChangedEvent;
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

	public static final String EXTRA_CHANNEL_ID =
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
	@Inject
	com.professor.zerion.android.api.AndroidNotificationManager
			notificationManager;

	private byte[] channelId = new byte[0];
	private Toolbar toolbar;
	private RecyclerView recycler;
	private TextView emptyView;
	private android.widget.ProgressBar feedProgress;
	private TextView connectingText;
	private boolean reachedPublisherAtLeastOnce = false;
	private boolean showSlowConnectingHint = false;
	private boolean feedRendered = false;
	private static final long SLOW_CONNECT_HINT_MS = 25_000L;
	private final android.os.Handler slowConnectHandler =
			new android.os.Handler(android.os.Looper.getMainLooper());
	private LinearLayout composeBar;
	private EditText composeInput;
	private MaterialButton composeSendButton;
	private android.widget.ImageButton ttlButton;
	private android.widget.ImageButton attachButton;
	private android.widget.ProgressBar composeProgress;
	private long selectedTtlSeconds = 0L;
	private androidx.activity.result.ActivityResultLauncher<String[]>
			attachmentPicker;
	private LinearLayout pinnedBanner;
	private TextView pinnedBannerText;
	private android.widget.ImageButton pinnedBannerClose;
	private TextView approvalBanner;
	private boolean applying = false;
	private TextView noticeBanner;
	private TextView syncStatusLine;
	private boolean syncDormant = false;
	private boolean refreshAttempted = false;
	private PostAdapter adapter;
	private boolean weArePublisher = false;
	private boolean canPost = false;
	private boolean discussionsEnabledCached = true;
	private java.util.Set<Long> commentExceptions =
			java.util.Collections.emptySet();
	private byte[] myEditorKey = new byte[0];
	private List<ChannelQueuedItem> notSentItems = new ArrayList<>();
	private long currentPinnedSeq = ChannelState.NO_PINNED_POST;
	private static final int MENU_ITEM_DISCUSSIONS = 7341;
	private static final int MENU_ITEM_MUTE = 7342;
	private static final int MENU_ITEM_COPY_KEY = 7343;

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
		setContentView(R.layout.activity_channel_feed);

		Intent i = getIntent();
		byte[] cid = i.getByteArrayExtra(EXTRA_CHANNEL_ID);
		if (cid != null) channelId = cid;

		toolbar = findViewById(R.id.channelFeedToolbar);
		recycler = findViewById(R.id.channelFeedRecycler);
		emptyView = findViewById(R.id.channelFeedEmptyView);
		feedProgress = findViewById(R.id.channelFeedProgress);
		connectingText = findViewById(R.id.channelFeedConnectingText);
		feedProgress.setVisibility(View.VISIBLE);
		composeBar = findViewById(R.id.channelComposeBar);
		composeInput = findViewById(R.id.channelComposeInput);
		composeSendButton = findViewById(R.id.channelComposeSendButton);
		ttlButton = findViewById(R.id.channelComposeTtlButton);
		ttlButton.setOnClickListener(v -> showTtlPicker());
		attachButton = findViewById(R.id.channelComposeAttachButton);
		composeProgress = findViewById(R.id.channelComposeProgress);
		attachmentPicker = registerForActivityResult(
				new androidx.activity.result.contract.ActivityResultContracts
						.OpenMultipleDocuments(),
				this::handleAttachmentsPicked);
		attachButton.setOnClickListener(v -> attachmentPicker.launch(
				new String[]{"*/*"}));
		pinnedBanner = findViewById(R.id.channelPinnedBanner);
		pinnedBannerText = findViewById(R.id.channelPinnedBannerText);
		pinnedBannerClose = findViewById(R.id.channelPinnedBannerClose);
		approvalBanner = findViewById(R.id.channelApprovalBanner);
		noticeBanner = findViewById(R.id.channelNoticeBanner);
		syncStatusLine = findViewById(R.id.channelSyncStatusLine);

		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		toolbar.setNavigationOnClickListener(v -> finish());

		adapter = new PostAdapter(
				post -> showPostMenu(post,
						post.getSeqNum() == currentPinnedSeq),
				(post, att, thumb, spinner) ->
						handleAttachmentTap(post, att, thumb, spinner),
				this::openComments,
				item -> showNotSentDialog(
						java.util.Collections.singletonList(item)));
		noticeBanner.setOnClickListener(v -> {
			if (!notSentItems.isEmpty()) {
				showNotSentDialog(new ArrayList<>(notSentItems));
			}
		});
		recycler.setLayoutManager(new LinearLayoutManager(this));
		recycler.setAdapter(adapter);

		composeSendButton.setOnClickListener(v -> handleSend());
		composeInput.setOnKeyListener((v, keyCode, event) -> {
			if (keyCode == android.view.KeyEvent.KEYCODE_ENTER
					&& event.getAction() == android.view.KeyEvent.ACTION_DOWN
					&& !event.isShiftPressed()) {
				handleSend();
				return true;
			}
			return false;
		});
		composeInput.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEND
				| android.view.inputmethod.EditorInfo.IME_FLAG_NO_EXTRACT_UI);
		composeInput.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_SEND) {
				handleSend();
				return true;
			}
			return false;
		});
		if (channelId.length > 0) {
			String draft = com.professor.zerion.android.AppModule
					.getAndroidComponent(this).profilePreferences()
					.getString(draftKey(), "");
			if (!draft.isEmpty()) composeInput.setText(draft);
		}
	}

	private static final long FOREGROUND_REFRESH_ACTIVE_MS = 3_500L;
	private static final long FOREGROUND_REFRESH_IDLE_MS = 12_000L;
	private static final int ACTIVE_ROUNDS_AFTER_HIT = 4;
	private int activeRoundsRemaining = 0;
	private final java.security.SecureRandom refreshJitter =
			new java.security.SecureRandom();

	static long jitteredRefreshDelay(long baseMs, double unit) {
		return Math.round(baseMs * (0.5 + unit));
	}

	private long nextRefreshDelay(long baseMs) {
		return jitteredRefreshDelay(baseMs, refreshJitter.nextDouble());
	}

	@Nullable
	private android.os.Handler refreshHandler;
	private final Runnable refreshTick = new Runnable() {
		@Override
		public void run() {
			refreshFromPublisherSafely(false);
			if (refreshHandler != null) {
				long delay = activeRoundsRemaining > 0
						? FOREGROUND_REFRESH_ACTIVE_MS
						: FOREGROUND_REFRESH_IDLE_MS;
				if (activeRoundsRemaining > 0) {
					activeRoundsRemaining--;
				}
				refreshHandler.postDelayed(this, nextRefreshDelay(delay));
			}
		}
	};

	@Override
	public void onStart() {
		super.onStart();
		eventBus.addListener(this);
		if (channelId.length > 0) {
			notificationManager.clearChannelNotification(channelId);
			channelManager.refreshChannelReachability(channelId);
		}
		loadChannel();
		markRead();
		refreshFromPublisherSafely(true);
		refreshHandler = new android.os.Handler(
				android.os.Looper.getMainLooper());
		refreshHandler.postDelayed(refreshTick,
				nextRefreshDelay(FOREGROUND_REFRESH_ACTIVE_MS));
	}

	private void refreshFromPublisherSafely(boolean opening) {
		ioExecutor.execute(() -> {
			boolean reached = false;
			boolean pulled = true;
			try {
				if (opening) {
					channelManager.refreshChannelOnOpen(channelId);
				} else {
					pulled = channelManager.refreshChannelTick(channelId);
				}
				reached = pulled;
			} catch (DbException ignored) {
			} finally {
				boolean finalReached = reached;
				if (pulled) {
					runOnUiThreadUnlessDestroyed(() -> {
						refreshAttempted = true;
						if (finalReached) {
							reachedPublisherAtLeastOnce = true;
						}
						loadChannel();
					});
				}
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
		slowConnectHandler.removeCallbacks(slowConnectRunnable);
		slowConnectScheduled = false;
		if (composeInput != null && channelId.length > 0) {
			String draft = composeInput.getText().toString();
			android.content.SharedPreferences sp = com.professor.zerion.android
					.AppModule.getAndroidComponent(this).profilePreferences();
			if (draft.trim().isEmpty()) {
				sp.edit().remove(draftKey()).apply();
			} else {
				sp.edit().putString(draftKey(), draft).apply();
			}
		}
	}

	@Override
	public void onResume() {
		super.onResume();
		ioExecutor.execute(this::wipeAttachmentStagingDir);
	}

	@Override
	public void onDestroy() {
		ioExecutor.execute(this::wipeAttachmentStagingDir);
		super.onDestroy();
	}

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ChannelPostReceivedEvent) {
			ChannelPostReceivedEvent ev = (ChannelPostReceivedEvent) e;
			if (Arrays.equals(ev.getChannelId(), channelId)) {
				runOnUiThreadUnlessDestroyed(() -> {
					activeRoundsRemaining = ACTIVE_ROUNDS_AFTER_HIT;
					loadChannel();
				});
			}
		} else if (e instanceof ChannelStateChangedEvent) {
			ChannelStateChangedEvent ev = (ChannelStateChangedEvent) e;
			if (Arrays.equals(ev.getChannelId(), channelId)) {
				if (ev.getKind()
						== ChannelStateChangedEvent.Kind.APPLICANT_APPROVED) {
					runOnUiThreadUnlessDestroyed(() -> Toast.makeText(this,
							R.string.channels_applicant_approved_notice,
							Toast.LENGTH_SHORT).show());
				}
				runOnUiThreadUnlessDestroyed(this::loadChannel);
			}
		} else if (e instanceof ChannelCommentReceivedEvent) {
			ChannelCommentReceivedEvent ev = (ChannelCommentReceivedEvent) e;
			if (Arrays.equals(ev.getChannelId(), channelId)) {
				runOnUiThreadUnlessDestroyed(this::loadChannel);
			}
		}
	}

	private void loadChannel() {
		ioExecutor.execute(() -> {
			ChannelState state;
			List<ChannelPost> posts;
			ApplicationStatus appStatus = ApplicationStatus.NOT_APPLIED;
			try {
				state = channelManager.getChannel(channelId);
				posts = state == null ? new ArrayList<>()
						: channelManager.getRecentPosts(channelId, 500L);
				if (state != null && !state.weArePublisher()
						&& state.requiresApproval()) {
					appStatus = channelManager.getMyApplicationStatus(
							channelId);
				}
			} catch (DbException ex) {
				state = null;
				posts = new ArrayList<>();
			}
			java.util.Map<String, byte[]> thumbnails = new java.util.HashMap<>();
			java.util.Map<Long, java.util.List<org.zerionproject.app.api
					.channel.ChannelReaction>> reactions =
					new java.util.HashMap<>();
			java.util.Map<Long, Integer> commentCounts =
					new java.util.HashMap<>();
			boolean discussionsEnabled = true;
			java.util.Set<Long> exceptions = java.util.Collections.emptySet();
			java.util.Map<Long, ChannelQueuedItem> queuedReactions =
					new java.util.HashMap<>();
			List<ChannelQueuedItem> otherWaiting = new ArrayList<>();
			List<ChannelQueuedItem> otherNotSent = new ArrayList<>();
			if (state != null && !state.weArePublisher()) {
				try {
					for (ChannelQueuedItem q
							: channelManager.getQueuedItems(channelId)) {
						if (q.getKind() == ChannelQueuedItem.Kind.REACTION) {
							queuedReactions.put(q.getPostSeqNum(), q);
						} else if (q.getKind()
								!= ChannelQueuedItem.Kind.COMMENT) {
							if (q.isNotSent()) otherNotSent.add(q);
							else otherWaiting.add(q);
						}
					}
				} catch (DbException ignored) {
				}
			}
			if (state != null) {
				try {
					discussionsEnabled =
							channelManager.areDiscussionsEnabled(channelId);
					exceptions =
							channelManager.getCommentExceptions(channelId);
				} catch (DbException ignored) {
				}
				java.util.Map<Long, java.util.List<ChannelReaction>>
						reactionsBySeq = new java.util.HashMap<>();
				java.util.Map<Long, Integer> commentCountsBySeq =
						new java.util.HashMap<>();
				try {
					for (ChannelReaction rx :
							channelManager.getAllReactions(channelId)) {
						reactionsBySeq.computeIfAbsent(rx.getPostSeqNum(),
								k -> new java.util.ArrayList<>()).add(rx);
					}
				} catch (DbException ignored) {
				}
				try {
					for (ChannelComment cm :
							channelManager.getAllComments(channelId)) {
						commentCountsBySeq.merge(cm.getParentPostSeqNum(),
								1, Integer::sum);
					}
				} catch (DbException ignored) {
				}
				showWaitingReactions(reactionsBySeq, queuedReactions);
				for (ChannelPost p : posts) {
					java.util.List<ChannelReaction> rl =
							reactionsBySeq.get(p.getSeqNum());
					reactions.put(p.getSeqNum(),
							rl != null ? rl : new java.util.ArrayList<>());
					Integer cc = commentCountsBySeq.get(p.getSeqNum());
					commentCounts.put(p.getSeqNum(), cc != null ? cc : 0);
					for (ChannelPost.ChannelAttachment att
							: p.getAttachments()) {
						if (att.getThumbnail() == null) continue;
						try {
							String bhKey = bytesToHex(att.getBlobHash());
							byte[] dec = FEED_THUMB_BYTES_CACHE.get(bhKey);
							if (dec == null) {
								dec = channelManager
										.decryptAttachmentThumbnail(
												channelId, p.getSeqNum(),
												att.getBlobHash());
								if (dec != null) {
									FEED_THUMB_BYTES_CACHE.put(bhKey, dec);
								}
							}
							if (dec != null) {
								thumbnails.put(thumbnailKey(p.getSeqNum(),
										att.getBlobHash()), dec);
							}
						} catch (DbException ignored) {
						}
					}
				}
			}
			if (state != null && !posts.isEmpty()) {
				try {
					channelManager.markChannelRead(channelId);
				} catch (DbException ignored) {
				}
			}
			boolean mayPost = false;
			boolean outdated = false;
			long givenUp = 0L;
			byte[] editorKey = new byte[0];
			if (state != null) {
				try {
					mayPost = channelManager.canPost(channelId);
					if (state.weArePublisher()) {
						outdated = channelManager.hasOutdatedSubscribers(
								channelId);
					} else {
						givenUp = channelManager.getPostsGivenUp(channelId);
						if (mayPost) {
							byte[] mine = channelManager
									.getMyChannelPublicKey(channelId);
							editorKey = Arrays.copyOf(mine,
									Math.min(32, mine.length));
						}
					}
				} catch (DbException ignored) {
				}
			}
			ChannelSyncStatus sync = null;
			if (state != null && !state.weArePublisher()) {
				try {
					sync = channelManager.getSyncStatus(channelId);
				} catch (DbException ignored) {
				}
			}
			ChannelSyncStatus finalSync = sync;
			ChannelState finalState = state;
			List<ChannelPost> finalPosts = posts;
			ApplicationStatus finalStatus = appStatus;
			boolean finalDiscussionsEnabled = discussionsEnabled;
			java.util.Set<Long> finalExceptions = exceptions;
			boolean finalMayPost = mayPost;
			boolean finalOutdated = outdated;
			long finalGivenUp = givenUp;
			byte[] finalEditorKey = editorKey;
			runOnUiThreadUnlessDestroyed(() -> {
				canPost = finalMayPost;
				myEditorKey = finalEditorKey;
				commentExceptions = finalExceptions;
				syncDormant = finalSync != null && finalSync.isDormant();
				notSentItems = otherNotSent;
				adapter.setQueuedReactions(queuedReactions);
				render(finalState, finalPosts, finalStatus,
						thumbnails, reactions, commentCounts,
						finalDiscussionsEnabled);
				bindNotice(finalOutdated, finalGivenUp,
						!otherWaiting.isEmpty());
				bindSyncStatus(finalState, !finalPosts.isEmpty(), finalSync);
			});
		});
	}

	private void showWaitingReactions(
			java.util.Map<Long, List<ChannelReaction>> reactionsBySeq,
			java.util.Map<Long, ChannelQueuedItem> queuedReactions) {
		if (queuedReactions.isEmpty()) return;
		byte[] mine;
		try {
			byte[] key = channelManager.getMyChannelPublicKey(channelId);
			mine = Arrays.copyOf(key, Math.min(32, key.length));
		} catch (DbException e) {
			return;
		}
		for (ChannelQueuedItem q : queuedReactions.values()) {
			if (q.isNotSent()) continue;
			List<ChannelReaction> list = reactionsBySeq.computeIfAbsent(
					q.getPostSeqNum(), k -> new ArrayList<>());
			list.removeIf(r -> Arrays.equals(r.getSignerEd25519PubKey(),
					mine));
			list.add(new ChannelReaction(q.getPostSeqNum(), q.getText(),
					mine, new byte[0], q.getQueuedAtMs()));
		}
	}

	private void showNotSentDialog(List<ChannelQueuedItem> items) {
		if (items.isEmpty()) return;
		StringBuilder text = new StringBuilder();
		for (ChannelQueuedItem q : items) {
			if (text.length() > 0) text.append('\n');
			String label = queuedLabel(q);
			if (label.length() > 80) {
				int end = Character.isHighSurrogate(label.charAt(79))
						? 79 : 80;
				label = label.substring(0, end) + "…";
			}
			text.append(label);
		}
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this)
				.setTitle(R.string.channels_queued_not_sent)
				.setMessage(text.toString())
				.setPositiveButton(R.string.channels_queued_retry,
						(d, w) -> changeQueued(items, true))
				.setNegativeButton(R.string.channels_queued_delete,
						(d, w) -> changeQueued(items, false))
				.setNeutralButton(android.R.string.cancel, null)
				.show();
	}

	private String queuedLabel(ChannelQueuedItem q) {
		switch (q.getKind()) {
			case EDIT:
				return getString(R.string.action_edit_message) + ": "
						+ q.getText();
			case DELETE:
				return getString(R.string.channels_action_delete);
			case COMMENTS_SETTING:
				return getString(q.isCommentsOn()
						? R.string.channels_action_comments_on
						: R.string.channels_action_comments_off);
			case ANNOUNCE:
				return getString(R.string.channels_queued_announce_label,
						q.getText());
			default:
				return q.getText();
		}
	}

	private void changeQueued(List<ChannelQueuedItem> items, boolean retry) {
		ioExecutor.execute(() -> {
			boolean alreadySent = false;
			for (ChannelQueuedItem q : items) {
				try {
					if (retry) {
						channelManager.retryQueuedItem(channelId, q.getId());
					} else if (channelManager.deleteQueuedItem(channelId,
							q.getId()) == ChannelQueuedItem.DeleteResult.SENT) {
						alreadySent = true;
					}
				} catch (DbException ignored) {
				}
			}
			boolean finalAlreadySent = alreadySent;
			runOnUiThreadUnlessDestroyed(() -> {
				if (finalAlreadySent) {
					Toast.makeText(this, R.string.channels_queued_already_sent,
							Toast.LENGTH_SHORT).show();
				}
				loadChannel();
			});
		});
	}

	private static String thumbnailKey(long seqNum, byte[] blobHash) {
		StringBuilder sb = new StringBuilder();
		sb.append(seqNum).append('|');
		for (byte b : blobHash) {
			sb.append(String.format(java.util.Locale.US, "%02x", b));
		}
		return sb.toString();
	}

	private void render(@Nullable ChannelState state,
			List<ChannelPost> posts, ApplicationStatus appStatus,
			java.util.Map<String, byte[]> thumbnails,
			java.util.Map<Long, java.util.List<org.zerionproject.app.api
					.channel.ChannelReaction>> reactions,
			java.util.Map<Long, Integer> commentCounts,
			boolean discussionsEnabled) {
		this.discussionsEnabledCached = discussionsEnabled;
		if (state == null) {
			finish();
			return;
		}
		weArePublisher = state.weArePublisher();
		toolbar.setTitle(state.getName());
		toolbar.setSubtitle(state.isPublicChannel()
				? getString(R.string.channels_row_public)
				: getString(R.string.channels_row_closed));
		bindApprovalBanner(state, appStatus);

		if (posts.isEmpty()) {
			recycler.setVisibility(View.GONE);
			if (weArePublisher || reachedPublisherAtLeastOnce
					|| (syncDormant && refreshAttempted)) {
				feedProgress.setVisibility(View.GONE);
				connectingText.setVisibility(View.GONE);
				cancelSlowConnectHint();
				emptyView.setVisibility(View.VISIBLE);
				emptyView.setText(weArePublisher
						? R.string.channels_feed_empty_publisher
						: R.string.channels_feed_empty_subscriber);
			} else {
				feedProgress.setVisibility(View.VISIBLE);
				emptyView.setVisibility(View.GONE);
				connectingText.setVisibility(View.VISIBLE);
				connectingText.setText(showSlowConnectingHint
						? R.string.channels_feed_connecting_slow
						: R.string.channels_feed_connecting);
				scheduleSlowConnectHint();
			}
		} else {
			feedProgress.setVisibility(View.GONE);
			connectingText.setVisibility(View.GONE);
			cancelSlowConnectHint();
			recycler.setVisibility(View.VISIBLE);
			emptyView.setVisibility(View.GONE);
			boolean pinToBottom = !feedRendered || isFeedAtBottom();
			adapter.setPosts(posts, thumbnails, reactions,
					commentCounts, discussionsEnabled, commentExceptions);
			if (pinToBottom) {
				recycler.scrollToPosition(posts.size() - 1);
			}
			feedRendered = true;
		}

		composeBar.setVisibility(canPost ? View.VISIBLE : View.GONE);
		attachButton.setVisibility(weArePublisher ? View.VISIBLE : View.GONE);

		currentPinnedSeq = state.getPinnedPostSeq();
		bindPinnedBanner(state, posts);
	}

	private void bindNotice(boolean outdated, long givenUp,
			boolean waiting) {
		if (outdated) {
			noticeBanner.setText(R.string.channels_outdated_subscribers_notice);
			noticeBanner.setVisibility(View.VISIBLE);
		} else if (!notSentItems.isEmpty()) {
			noticeBanner.setText(R.string.channels_queued_notice_not_sent);
			noticeBanner.setVisibility(View.VISIBLE);
		} else if (waiting) {
			noticeBanner.setText(R.string.channels_queued_notice_waiting);
			noticeBanner.setVisibility(View.VISIBLE);
		} else if (givenUp > 0L) {
			noticeBanner.setText(R.string.channels_posts_given_up_notice);
			noticeBanner.setVisibility(View.VISIBLE);
		} else {
			noticeBanner.setVisibility(View.GONE);
		}
	}

	private void bindSyncStatus(@Nullable ChannelState state,
			boolean postsHeld, @Nullable ChannelSyncStatus sync) {
		String line = state == null || state.weArePublisher() || sync == null
				? null : syncStatusText(this, sync, postsHeld);
		if (line == null) {
			syncStatusLine.setVisibility(View.GONE);
		} else {
			syncStatusLine.setText(line);
			syncStatusLine.setVisibility(View.VISIBLE);
		}
	}

	@Nullable
	static String syncStatusText(Context ctx, ChannelSyncStatus sync,
			boolean postsHeld) {
		long at = sync.getLastUpdatedMs();
		if (sync.isDormant() && at >= 0L) {
			return ctx.getString(R.string.channels_sync_not_updated_since,
					android.text.format.DateUtils.formatDateTime(ctx, at,
							android.text.format.DateUtils.FORMAT_SHOW_DATE
							| android.text.format.DateUtils
									.FORMAT_ABBREV_MONTH));
		}
		if (!postsHeld) return null;
		if (at < 0L) {
			return sync.isLastAttemptFailed() || sync.isDormant()
					? ctx.getString(R.string.channels_sync_saved_posts)
					: null;
		}
		int flags = sameDay(at, System.currentTimeMillis())
				? android.text.format.DateUtils.FORMAT_SHOW_TIME
				: android.text.format.DateUtils.FORMAT_SHOW_DATE
				| android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
		String when = android.text.format.DateUtils.formatDateTime(ctx, at,
				flags);
		return ctx.getString(sync.isLastAttemptFailed()
				? R.string.channels_sync_saved_posts_last_updated
				: R.string.channels_sync_last_updated, when);
	}

	private static boolean sameDay(long a, long b) {
		java.util.Calendar x = java.util.Calendar.getInstance();
		x.setTimeInMillis(a);
		java.util.Calendar y = java.util.Calendar.getInstance();
		y.setTimeInMillis(b);
		return x.get(java.util.Calendar.YEAR) == y.get(java.util.Calendar.YEAR)
				&& x.get(java.util.Calendar.DAY_OF_YEAR)
				== y.get(java.util.Calendar.DAY_OF_YEAR);
	}

	private boolean slowConnectScheduled = false;

	private final Runnable slowConnectRunnable = () -> {
		slowConnectScheduled = false;
		showSlowConnectingHint = true;
		if (!isDestroyed()
				&& connectingText.getVisibility() == View.VISIBLE) {
			connectingText.setText(R.string.channels_feed_connecting_slow);
		}
	};

	private void scheduleSlowConnectHint() {
		if (slowConnectScheduled || showSlowConnectingHint) return;
		slowConnectScheduled = true;
		slowConnectHandler.postDelayed(slowConnectRunnable,
				SLOW_CONNECT_HINT_MS);
	}

	private void cancelSlowConnectHint() {
		slowConnectScheduled = false;
		showSlowConnectingHint = false;
		slowConnectHandler.removeCallbacks(slowConnectRunnable);
	}

	private boolean isFeedAtBottom() {
		RecyclerView.LayoutManager lm = recycler.getLayoutManager();
		if (!(lm instanceof LinearLayoutManager)) return true;
		LinearLayoutManager llm = (LinearLayoutManager) lm;
		int last = llm.findLastVisibleItemPosition();
		int count = llm.getItemCount();
		return last == RecyclerView.NO_POSITION || last >= count - 2;
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
		pinnedBannerText.setText(target.isWithheld()
				? getString(R.string.channels_post_withheld)
				: target.getBody());
		final int idx = targetIndex;
		pinnedBanner.setOnClickListener(v ->
				recycler.smoothScrollToPosition(idx));
		pinnedBannerClose.setVisibility(
				weArePublisher ? View.VISIBLE : View.GONE);
		pinnedBannerClose.setOnClickListener(v -> handleUnpin());
	}

	private void bindApprovalBanner(ChannelState state,
			ApplicationStatus appStatus) {
		if (state.weArePublisher() || !state.requiresApproval()) {
			approvalBanner.setVisibility(View.GONE);
			return;
		}
		approvalBanner.setOnClickListener(null);
		approvalBanner.setClickable(false);
		if (applying) {
			approvalBanner.setText(R.string.channels_apply_progress);
			approvalBanner.setVisibility(View.VISIBLE);
		} else if (appStatus == ApplicationStatus.PENDING) {
			approvalBanner.setText(
					R.string.channels_approval_pending_banner);
			approvalBanner.setVisibility(View.VISIBLE);
		} else if (appStatus == ApplicationStatus.DENIED) {
			approvalBanner.setText(
					R.string.channels_approval_denied_banner);
			approvalBanner.setVisibility(View.VISIBLE);
		} else if (appStatus == ApplicationStatus.NOT_APPLIED
				&& state.getJoinCapability() == null) {
			approvalBanner.setText(R.string.channels_apply_banner);
			approvalBanner.setOnClickListener(v -> showApplyDialog());
			approvalBanner.setVisibility(View.VISIBLE);
		} else {
			approvalBanner.setVisibility(View.GONE);
		}
	}

	private void showApplyDialog() {
		View view = LayoutInflater.from(this).inflate(
				R.layout.dialog_apply_to_join, null);
		com.google.android.material.textfield.TextInputEditText nameInput =
				view.findViewById(R.id.channelApplyNameInput);
		nameInput.setFilters(new android.text.InputFilter[] {
				new com.professor.zerion.android.util.Utf8ByteLengthFilter(
						org.zerionproject.app.api.channel.ChannelConstants
								.MAX_DISPLAY_NAME_BYTES)});
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this)
				.setTitle(R.string.channels_apply_title)
				.setView(view)
				.setPositiveButton(R.string.channels_apply_action,
						(d, w) -> applyAs(nameInput.getText() == null ? ""
								: nameInput.getText().toString().trim()))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void applyAs(String name) {
		if (name.isEmpty()) {
			Toast.makeText(this, R.string.channels_create_error_name,
					Toast.LENGTH_SHORT).show();
			return;
		}
		applying = true;
		approvalBanner.setOnClickListener(null);
		approvalBanner.setClickable(false);
		approvalBanner.setText(R.string.channels_apply_progress);
		ioExecutor.execute(() -> {
			try {
				channelManager.applyToJoin(channelId, name);
				runOnUiThreadUnlessDestroyed(() -> {
					applying = false;
					loadChannel();
				});
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> {
					applying = false;
					Toast.makeText(this,
							ex instanceof org.zerionproject.app.api.channel
									.ChannelApplicationUnconfirmedException
									? R.string.channels_apply_unconfirmed
									: R.string.channels_apply_failed,
							Toast.LENGTH_LONG).show();
					loadChannel();
				});
			}
		});
	}

	private void handlePin(long seqNum) {
		ioExecutor.execute(() -> {
			try {
				channelManager.pinPost(channelId, seqNum);
				runOnUiThreadUnlessDestroyed(this::loadChannel);
			} catch (DbException ignored) {
			}
		});
	}

	private void handleUnpin() {
		ioExecutor.execute(() -> {
			try {
				channelManager.unpinPost(channelId);
				runOnUiThreadUnlessDestroyed(this::loadChannel);
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
		labels.add(getString(R.string.channels_action_comments));
		actions.add(() -> openComments(post));
		labels.add(getString(R.string.channels_action_react));
		actions.add(() -> showReactionPicker(post));
		labels.add(getString(R.string.channels_action_copy_text));
		actions.add(() -> copyPostText(post));
		boolean manage = mayChange(post);
		boolean deleted = ChannelConstants.DELETED_POST_PLACEHOLDER.equals(
				post.getBody());
		if (!deleted) addSaveActions(post, labels, actions);
		if (manage && !deleted
				&& post.isEditableAt(System.currentTimeMillis())) {
			labels.add(getString(R.string.action_edit_message));
			actions.add(() -> showEditDialog(post));
		}
		if (manage && !deleted) {
			boolean on = commentsOn(post);
			labels.add(getString(on
					? R.string.channels_action_comments_off
					: R.string.channels_action_comments_on));
			actions.add(() -> setPostComments(post, !on));
		}
		if (manage) {
			labels.add(getString(R.string.channels_action_delete));
			actions.add(() -> confirmDelete(post));
		}
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this)
				.setItems(labels.toArray(new CharSequence[0]),
						(d, which) -> actions.get(which).run())
				.show();
	}

	private boolean mayChange(ChannelPost post) {
		if (weArePublisher) return true;
		byte[] signer = post.getDelegateSignerEd25519PubKey();
		return canPost && signer != null && myEditorKey.length > 0
				&& Arrays.equals(signer, myEditorKey);
	}

	private boolean commentsOn(ChannelPost post) {
		return discussionsEnabledCached
				!= commentExceptions.contains(post.getSeqNum());
	}

	private void showEditDialog(ChannelPost post) {
		LinearLayout container = new LinearLayout(this);
		container.setOrientation(LinearLayout.VERTICAL);
		int pad = (int) (16f * getResources().getDisplayMetrics().density);
		container.setPadding(pad, pad / 2, pad, 0);
		EditText input = new EditText(this);
		input.setInputType(android.text.InputType.TYPE_CLASS_TEXT
				| android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
				| android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
		input.setText(post.getBody());
		input.setSelection(post.getBody().length());
		input.setContentDescription(getString(R.string.action_edit_message));
		container.addView(input, new LinearLayout.LayoutParams(
				LinearLayout.LayoutParams.MATCH_PARENT,
				LinearLayout.LayoutParams.WRAP_CONTENT));
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this)
				.setTitle(R.string.action_edit_message)
				.setView(container)
				.setPositiveButton(android.R.string.ok, (d, w) -> {
					String text = input.getText() == null
							? "" : input.getText().toString().trim();
					if (!text.isEmpty() && !text.equals(post.getBody())) {
						handleEdit(post, text);
					}
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void handleEdit(ChannelPost post, String text) {
		ioExecutor.execute(() -> {
			try {
				channelManager.editPost(channelId, post.getSeqNum(), text);
				runOnUiThreadUnlessDestroyed(this::loadChannel);
			} catch (ChannelRequestQueuedException queued) {
				runOnUiThreadUnlessDestroyed(() -> {
					Toast.makeText(this, queued.isBehindEarlierItems()
							? R.string.channels_queued_behind_toast
							: R.string.channels_change_queued_toast,
							Toast.LENGTH_SHORT).show();
					loadChannel();
				});
			} catch (DbException ex) {
				int message = ex instanceof org.zerionproject.app.api.channel
						.ChannelPostTooLongException
						? R.string.text_too_long
						: R.string.channels_post_edit_failed;
				runOnUiThreadUnlessDestroyed(() -> Toast.makeText(this,
						message, Toast.LENGTH_SHORT).show());
			}
		});
	}

	private void setPostComments(ChannelPost post, boolean enabled) {
		ioExecutor.execute(() -> {
			try {
				channelManager.setPostCommentsEnabled(channelId,
						post.getSeqNum(), enabled);
				runOnUiThreadUnlessDestroyed(this::loadChannel);
			} catch (ChannelRequestQueuedException queued) {
				runOnUiThreadUnlessDestroyed(() -> {
					Toast.makeText(this, queued.isBehindEarlierItems()
							? R.string.channels_queued_behind_toast
							: R.string.channels_change_queued_toast,
							Toast.LENGTH_SHORT).show();
					loadChannel();
				});
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> Toast.makeText(this,
						R.string.channels_discussions_toggle_failed_toast,
						Toast.LENGTH_LONG).show());
			}
		});
	}

	private void openComments(ChannelPost post) {
		if (!commentsOn(post) && !weArePublisher) {
			Toast.makeText(this, discussionsEnabledCached
							? R.string.channels_post_comments_off
							: R.string.channels_comments_disabled_notice,
					Toast.LENGTH_SHORT).show();
			return;
		}
		startActivity(ChannelCommentsActivity.intent(this, channelId,
				post.getSeqNum()));
	}

	private static final String[] REACTION_EMOJIS = {
			"👍", "❤️", "😂",
			"😮", "😢", "🔥"};

	private void showReactionPicker(ChannelPost post) {
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this)
				.setItems(REACTION_EMOJIS, (d, which) ->
						sendReaction(post, REACTION_EMOJIS[which]))
				.show();
	}

	private void sendReaction(ChannelPost post, String emoji) {
		ioExecutor.execute(() -> {
			try {
				channelManager.reactToPost(channelId, post.getSeqNum(),
						emoji);
				runOnUiThreadUnlessDestroyed(this::loadChannel);
			} catch (ChannelRequestQueuedException queued) {
				runOnUiThreadUnlessDestroyed(() -> {
					Toast.makeText(this, queued.isBehindEarlierItems()
							? R.string.channels_queued_behind_toast
							: R.string.channels_react_queued_toast,
							Toast.LENGTH_SHORT).show();
					loadChannel();
				});
			} catch (org.zerionproject.app.api.channel
					.ChannelReactionTooSoonException tooSoon) {
				runOnUiThreadUnlessDestroyed(() -> Toast.makeText(this,
						R.string.channels_react_too_soon,
						Toast.LENGTH_SHORT).show());
			} catch (org.zerionproject.app.api.channel
					.ChannelNoAccessException noAccess) {
				runOnUiThreadUnlessDestroyed(() -> Toast.makeText(this,
						R.string.channels_no_access_toast,
						Toast.LENGTH_LONG).show());
			} catch (DbException ignored) {
				runOnUiThreadUnlessDestroyed(() -> Toast.makeText(this,
						R.string.channels_react_failed,
						Toast.LENGTH_SHORT).show());
			}
		});
	}

	private void confirmDelete(ChannelPost post) {
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this)
				.setMessage(R.string.channels_post_delete_confirm)
				.setPositiveButton(R.string.channels_action_delete,
						(d, w) -> handleDelete(post))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void handleDelete(ChannelPost post) {
		ioExecutor.execute(() -> {
			try {
				channelManager.deletePost(channelId, post.getSeqNum());
				runOnUiThreadUnlessDestroyed(this::loadChannel);
			} catch (ChannelRequestQueuedException queued) {
				runOnUiThreadUnlessDestroyed(() -> {
					Toast.makeText(this, queued.isBehindEarlierItems()
							? R.string.channels_queued_behind_toast
							: R.string.channels_change_queued_toast,
							Toast.LENGTH_SHORT).show();
					loadChannel();
				});
			} catch (DbException ignored) {
				runOnUiThreadUnlessDestroyed(() -> Toast.makeText(this,
						R.string.channels_post_delete_failed,
						Toast.LENGTH_SHORT).show());
			}
		});
	}

	private void copyPostText(ChannelPost post) {
		com.professor.zerion.android.util.SecureClipboard.copy(this,
				"zerion-channel-post", post.getBody());
		Toast.makeText(this, R.string.channels_post_copied,
				Toast.LENGTH_SHORT).show();
	}

	private void addSaveActions(ChannelPost post, List<CharSequence> labels,
			List<Runnable> actions) {
		List<ChannelPost.ChannelAttachment> savable = new ArrayList<>();
		for (ChannelPost.ChannelAttachment att : post.getAttachments()) {
			if (SaveCleanCopy.canSave(att.getMimeType())) savable.add(att);
		}
		for (int n = 0; n < savable.size(); n++) {
			ChannelPost.ChannelAttachment att = savable.get(n);
			String label = getString(att.getMimeType()
					.toLowerCase(Locale.ROOT).startsWith("image/")
					? R.string.save_image : R.string.save_file);
			if (savable.size() > 1) label = label + " " + (n + 1);
			labels.add(label);
			actions.add(() -> SaveCleanCopy.confirm(this, att.getMimeType(),
					() -> saveAttachment(post.getSeqNum(),
							att.getBlobHash())));
		}
	}

	private void saveAttachment(long seq, byte[] hash) {
		ioExecutor.execute(() -> {
			AttachmentBlob blob;
			try {
				blob = channelManager.fetchAttachment(channelId, seq, hash);
			} catch (DbException | java.io.IOException ex) {
				blob = null;
			}
			AttachmentBlob found = blob;
			runOnUiThreadUnlessDestroyed(() -> {
				if (found == null) {
					Toast.makeText(this, R.string.save_file_error,
							Toast.LENGTH_LONG).show();
				} else {
					SaveCleanCopy.start(this, found.getPlaintextBytes(),
							found.getMimeType());
				}
			});
		});
	}

	private long readSelectedTtlSeconds() {
		return selectedTtlSeconds;
	}

	private void showTtlPicker() {
		android.widget.PopupMenu popup = new android.widget.PopupMenu(this,
				ttlButton);
		popup.getMenu().add(0, 0, 0, R.string.channels_ttl_off);
		popup.getMenu().add(0, 1, 1, R.string.channels_ttl_one_hour);
		popup.getMenu().add(0, 2, 2, R.string.channels_ttl_one_day);
		popup.getMenu().add(0, 3, 3, R.string.channels_ttl_one_week);
		popup.getMenu().add(0, 4, 4, R.string.channels_ttl_thirty_days);
		popup.setOnMenuItemClickListener(item -> {
			switch (item.getItemId()) {
				case 0: selectedTtlSeconds = 0L; break;
				case 1: selectedTtlSeconds = 60L * 60L; break;
				case 2: selectedTtlSeconds = 24L * 60L * 60L; break;
				case 3: selectedTtlSeconds = 7L * 24L * 60L * 60L; break;
				case 4: selectedTtlSeconds = 30L * 24L * 60L * 60L; break;
				default: return false;
			}
			updateTtlButtonTint();
			return true;
		});
		popup.show();
	}

	private void updateTtlButtonTint() {
		int color = selectedTtlSeconds > 0L
				? androidx.core.content.ContextCompat.getColor(this,
						R.color.zerion_accent_icon)
				: androidx.core.content.ContextCompat.getColor(this,
						R.color.zerion_text_secondary);
		androidx.core.widget.ImageViewCompat.setImageTintList(ttlButton,
				android.content.res.ColorStateList.valueOf(color));
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
				clearDraft();
				runOnUiThreadUnlessDestroyed(this::loadChannel);
			} catch (ChannelRequestQueuedException queued) {
				clearDraft();
				runOnUiThreadUnlessDestroyed(() -> {
					Toast.makeText(this, queued.isBehindEarlierItems()
							? R.string.channels_queued_behind_toast
							: R.string.channels_post_queued_toast,
							Toast.LENGTH_SHORT).show();
					loadChannel();
				});
			} catch (DbException ex) {
				int message = ex instanceof org.zerionproject.app.api.channel
						.ChannelPostTooLongException
						? R.string.text_too_long
						: weArePublisher
						? R.string.channels_compose_error_publish
						: R.string.channels_editor_post_failed;
				runOnUiThreadUnlessDestroyed(() -> {
					composeInput.setText(body);
					Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
				});
			}
		});
	}

	private void handleAttachmentTap(ChannelPost post,
			ChannelPost.ChannelAttachment att,
			android.widget.ImageView thumbView,
			android.widget.ProgressBar spinner) {
		spinner.setVisibility(View.VISIBLE);
		ioExecutor.execute(() -> {
			AttachmentBlob blob;
			try {
				blob = channelManager.fetchAttachment(channelId,
						post.getSeqNum(), att.getBlobHash());
			} catch (DbException | java.io.IOException ex) {
				blob = null;
			}
			AttachmentBlob finalBlob = blob;
			runOnUiThreadUnlessDestroyed(() -> {
				spinner.setVisibility(View.GONE);
				if (finalBlob == null) {
					Toast.makeText(this,
							R.string.channels_attach_download_failed,
							Toast.LENGTH_LONG).show();
					return;
				}
				presentAttachment(att, finalBlob, thumbView);
			});
		});
	}

	private static final java.util.concurrent.Executor THUMB_DECODE_EXECUTOR =
			java.util.concurrent.Executors.newSingleThreadExecutor();

	private static final android.util.LruCache<String, android.graphics.Bitmap>
			FEED_THUMB_CACHE = new android.util.LruCache<String,
					android.graphics.Bitmap>(
							(int) (Runtime.getRuntime().maxMemory() / 8)) {
				@Override
				protected int sizeOf(String key,
						android.graphics.Bitmap value) {
					return value.getByteCount();
				}
			};

	private static final android.util.LruCache<String, byte[]>
			FEED_THUMB_BYTES_CACHE = new android.util.LruCache<String, byte[]>(
					8 * 1024 * 1024) {
				@Override
				protected int sizeOf(String key, byte[] value) {
					return value.length;
				}
			};

	public static void clearFeedThumbCache() {
		FEED_THUMB_CACHE.evictAll();
		FEED_THUMB_BYTES_CACHE.evictAll();
	}

	private void presentAttachment(ChannelPost.ChannelAttachment att,
			AttachmentBlob blob, android.widget.ImageView thumbView) {
		String mime = blob.getMimeType();
		ioExecutor.execute(() -> {
			if (mime.startsWith("image/")) {
				String ck = bytesToHex(att.getBlobHash());
				android.graphics.Bitmap bmp = FEED_THUMB_CACHE.get(ck);
				if (bmp == null || bmp.isRecycled()) {
					bmp = com.professor.zerion.android.util.SafeImageDecoder
							.decode(blob.getPlaintextBytes(), 1280);
					if (bmp != null) FEED_THUMB_CACHE.put(ck, bmp);
				}
				if (bmp != null) {
					android.graphics.Bitmap finalBmp = bmp;
					runOnUiThreadUnlessDestroyed(() -> {
						thumbView.setImageBitmap(finalBmp);
						thumbView.setVisibility(View.VISIBLE);
					});
					return;
				}
			}
			String viewType = com.professor.zerion.android.util
					.ExternalViewerTypes.typeForContent(mime,
							blob.getPlaintextBytes());
			if (viewType == null) {
				runOnUiThreadUnlessDestroyed(() -> Toast.makeText(this,
						R.string.channels_attach_open_failed,
						Toast.LENGTH_LONG).show());
				return;
			}
			runOnUiThreadUnlessDestroyed(() -> com.professor.zerion.android
					.util.ExternalHandoff.open(this, ioExecutor,
							blob.getPlaintextBytes(), viewType,
							() -> Toast.makeText(this,
									R.string.channels_attach_open_failed,
									Toast.LENGTH_LONG).show()));
		});
	}

	private void wipeAttachmentStagingDir() {
		SecureMemory.secureDeleteDir(
				new java.io.File(getCacheDir(), "channel_attach_view"), 0L);
		com.professor.zerion.android.util.CacheSweeper.sweepNoBackupDirs(this);
	}

	private static String bytesToHex(byte[] b) {
		StringBuilder sb = new StringBuilder(b.length * 2);
		for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
		return sb.toString();
	}

	private void handleAttachmentsPicked(
			@Nullable java.util.List<android.net.Uri> uris) {
		uris = com.professor.zerion.android.util.PickedUris.accept(this,
				uris);
		if (uris.isEmpty()) return;
		if (uris.size() > ChannelConstants.MAX_ATTACHMENTS_PER_POST) {
			Toast.makeText(this,
					R.string.channels_attach_too_many,
					Toast.LENGTH_LONG).show();
			return;
		}
		java.util.List<android.net.Uri> picked = uris;
		com.professor.zerion.android.attachment.PdfSendNotice.confirm(this,
				picked, () -> publishPicked(picked));
	}

	private void publishPicked(java.util.List<android.net.Uri> uris) {
		String body = composeInput.getText() == null
				? "" : composeInput.getText().toString().trim();
		String composedBody = body.isEmpty() ? " " : body;
		long ttlSeconds = readSelectedTtlSeconds();
		java.util.List<android.net.Uri> snapshot =
				new java.util.ArrayList<>(uris);
		setComposeBusy(true);
		ioExecutor.execute(() -> {
			com.professor.zerion.android.attachment.SharedMediaSanitizer
					sanitizer = new com.professor.zerion.android.attachment
							.SharedMediaSanitizer(getApplicationContext());
			java.util.List<AttachmentSpec> specs =
					new java.util.ArrayList<>(snapshot.size());
			for (android.net.Uri uri : snapshot) {
				String mime = getContentResolver().getType(uri);
				if (mime == null) mime = "application/octet-stream";
				byte[] bytes;
				try (java.io.InputStream in =
							getContentResolver().openInputStream(uri)) {
					if (in == null) {
						runOnUiThreadUnlessDestroyed(() -> {
							setComposeBusy(false);
							Toast.makeText(this,
									R.string.channels_attach_read_failed,
									Toast.LENGTH_LONG).show();
						});
						return;
					}
					java.io.ByteArrayOutputStream buf =
							new java.io.ByteArrayOutputStream();
					byte[] chunk = new byte[8192];
					int read;
					while ((read = in.read(chunk)) > 0) {
						buf.write(chunk, 0, read);
						if (buf.size()
								> ChannelConstants.MAX_ATTACHMENT_BYTES) {
							runOnUiThreadUnlessDestroyed(() -> {
								setComposeBusy(false);
								Toast.makeText(this,
										R.string.channels_attach_too_large,
										Toast.LENGTH_LONG).show();
							});
							return;
						}
					}
					bytes = buf.toByteArray();
				} catch (java.io.IOException ex) {
					runOnUiThreadUnlessDestroyed(() -> {
						setComposeBusy(false);
						Toast.makeText(this,
								R.string.channels_attach_read_failed,
								Toast.LENGTH_LONG).show();
					});
					return;
				}
				com.professor.zerion.android.attachment.SharedMediaSanitizer
						.Cleaned cleaned;
				try {
					cleaned = sanitizer.sanitize(uri, mime, bytes,
							ChannelConstants.MAX_ATTACHMENT_BYTES);
				} catch (java.io.IOException | RuntimeException ex) {
					runOnUiThreadUnlessDestroyed(() -> {
						setComposeBusy(false);
						if (ex instanceof com.professor.zerion.android
								.attachment.MediaRefusedException) {
							com.professor.zerion.android.attachment
									.MediaRefusalDialog.show(this,
											(com.professor.zerion.android
													.attachment
													.MediaRefusedException) ex);
						} else {
							Toast.makeText(this,
									R.string.channels_attach_read_failed,
									Toast.LENGTH_LONG).show();
						}
					});
					return;
				}
				byte[] thumb = null;
				if (cleaned.getMimeType().startsWith("video/")) {
					thumb = extractVideoThumbnail(uri);
				}
				specs.add(new AttachmentSpec(cleaned.getMimeType(),
						cleaned.getData(), null, thumb));
			}
			try {
				channelManager.publishPostWithAttachments(channelId,
						composedBody, ttlSeconds, specs);
				runOnUiThreadUnlessDestroyed(() -> {
					setComposeBusy(false);
					composeInput.setText("");
					loadChannel();
				});
			} catch (DbException ex) {
				int message = ex instanceof org.zerionproject.app.api.channel
						.ChannelPostTooLongException
						? R.string.text_too_long
						: R.string.channels_attach_send_failed;
				runOnUiThreadUnlessDestroyed(() -> {
					setComposeBusy(false);
					Toast.makeText(this, message, Toast.LENGTH_LONG).show();
				});
			}
		});
	}

	@Nullable
	private byte[] extractVideoThumbnail(android.net.Uri uri) {
		android.media.MediaMetadataRetriever mmr =
				new android.media.MediaMetadataRetriever();
		try {
			mmr.setDataSource(this, uri);
			android.graphics.Bitmap frame = mmr.getFrameAtTime(
					1_000_000L,
					android.media.MediaMetadataRetriever
							.OPTION_CLOSEST_SYNC);
			if (frame == null) return null;
			android.graphics.Bitmap scaled = scaleForThumbnail(frame);
			java.io.ByteArrayOutputStream out =
					new java.io.ByteArrayOutputStream();
			scaled.compress(
					android.graphics.Bitmap.CompressFormat.JPEG, 70,
					out);
			if (scaled != frame) scaled.recycle();
			frame.recycle();
			return com.professor.zerion.android.attachment.ImageScrubber
					.scrub(out.toByteArray());
		} catch (java.io.IOException | RuntimeException ex) {
			return null;
		} finally {
			try {
				mmr.release();
			} catch (java.io.IOException ignored) {
			}
		}
	}

	private static android.graphics.Bitmap scaleForThumbnail(
			android.graphics.Bitmap src) {
		int maxDim = 320;
		int w = src.getWidth();
		int h = src.getHeight();
		if (w <= maxDim && h <= maxDim) return src;
		float scale = (float) maxDim / Math.max(w, h);
		int newW = Math.round(w * scale);
		int newH = Math.round(h * scale);
		return android.graphics.Bitmap.createScaledBitmap(src, newW,
				newH, true);
	}

	private void setComposeBusy(boolean busy) {
		composeProgress.setVisibility(busy ? View.VISIBLE : View.GONE);
		composeSendButton.setEnabled(!busy);
		attachButton.setEnabled(!busy);
		composeInput.setEnabled(!busy);
	}

	public static boolean isChannelMuted(android.content.Context context,
			byte[] channelId) {
		return com.professor.zerion.android.AppModule
				.getAndroidComponent(context).profilePreferences()
				.getBoolean(muteKey(channelId), false);
	}

	private static String muteKey(byte[] channelId) {
		return "mute_channel_" + org.zerionproject.core.util.StringUtils
				.toHexString(channelId);
	}

	private String draftKey() {
		return "channel_draft_" + org.zerionproject.core.util.StringUtils
				.toHexString(channelId);
	}

	private void clearDraft() {
		com.professor.zerion.android.AppModule.getAndroidComponent(this)
				.profilePreferences().edit().remove(draftKey()).apply();
	}

	@Override
	public boolean onCreateOptionsMenu(android.view.Menu menu) {
		menu.add(0, MENU_ITEM_DISCUSSIONS, 0,
				discussionsEnabledCached
						? R.string.channels_discussions_menu_disable
						: R.string.channels_discussions_menu_enable)
				.setShowAsAction(
						android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		android.view.MenuItem mute = menu.add(0, MENU_ITEM_MUTE, 1,
				R.string.channels_mute_notifications);
		mute.setCheckable(true);
		mute.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		menu.add(0, MENU_ITEM_COPY_KEY, 2, R.string.channels_action_copy_my_key)
				.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(android.view.Menu menu) {
		android.view.MenuItem item = menu.findItem(MENU_ITEM_DISCUSSIONS);
		if (item != null) {
			item.setVisible(weArePublisher);
			item.setTitle(discussionsEnabledCached
					? R.string.channels_discussions_menu_disable
					: R.string.channels_discussions_menu_enable);
		}
		android.view.MenuItem muteItem = menu.findItem(MENU_ITEM_MUTE);
		if (muteItem != null) {
			muteItem.setChecked(isChannelMuted(this, channelId));
		}
		android.view.MenuItem keyItem = menu.findItem(MENU_ITEM_COPY_KEY);
		if (keyItem != null) keyItem.setVisible(!weArePublisher);
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(android.view.MenuItem item) {
		if (item.getItemId() == MENU_ITEM_DISCUSSIONS) {
			toggleDiscussionsEnabled();
			return true;
		}
		if (item.getItemId() == MENU_ITEM_COPY_KEY) {
			copyMyChannelKey();
			return true;
		}
		if (item.getItemId() == MENU_ITEM_MUTE) {
			boolean muted = !isChannelMuted(this, channelId);
			com.professor.zerion.android.AppModule.getAndroidComponent(this)
					.profilePreferences().edit()
					.putBoolean(muteKey(channelId), muted).apply();
			item.setChecked(muted);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void copyMyChannelKey() {
		ioExecutor.execute(() -> {
			byte[] key;
			try {
				key = channelManager.getMyChannelPublicKey(channelId);
			} catch (DbException e) {
				return;
			}
			String text = base32(key);
			runOnUiThreadUnlessDestroyed(() -> {
				com.professor.zerion.android.util.SecureClipboard.copy(this,
						"zerion-channel-key", text);
				Toast.makeText(this, R.string.channels_my_key_copied,
						Toast.LENGTH_LONG).show();
			});
		});
	}

	private static String base32(byte[] data) {
		char[] alphabet = "abcdefghijklmnopqrstuvwxyz234567".toCharArray();
		StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
		int buffer = 0;
		int bitsLeft = 0;
		for (byte b : data) {
			buffer = (buffer << 8) | (b & 0xFF);
			bitsLeft += 8;
			while (bitsLeft >= 5) {
				sb.append(alphabet[(buffer >> (bitsLeft - 5)) & 0x1F]);
				bitsLeft -= 5;
			}
		}
		if (bitsLeft > 0) {
			sb.append(alphabet[(buffer << (5 - bitsLeft)) & 0x1F]);
		}
		return sb.toString();
	}

	private void toggleDiscussionsEnabled() {
		if (!weArePublisher) return;
		final boolean target = !discussionsEnabledCached;
		int title = target
				? R.string.channels_discussions_confirm_enable_title
				: R.string.channels_discussions_confirm_disable_title;
		int message = target
				? R.string.channels_discussions_confirm_enable_message
				: R.string.channels_discussions_confirm_disable_message;
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(this)
				.setTitle(title)
				.setMessage(message)
				.setPositiveButton(android.R.string.ok,
						(d, w) -> applyDiscussionsEnabled(target))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void applyDiscussionsEnabled(boolean target) {
		ioExecutor.execute(() -> {
			try {
				channelManager.setDiscussionsEnabled(channelId, target);
				runOnUiThreadUnlessDestroyed(() -> {
					discussionsEnabledCached = target;
					Toast.makeText(this,
							target
									? R.string.channels_discussions_enabled_toast
									: R.string.channels_discussions_disabled_toast,
							Toast.LENGTH_SHORT).show();
					invalidateOptionsMenu();
					loadChannel();
				});
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> {
					Toast.makeText(this,
							R.string.channels_discussions_toggle_failed_toast,
							Toast.LENGTH_LONG).show();
				});
			}
		});
	}

	private static class PostAdapter
			extends RecyclerView.Adapter<PostViewHolder> {

		interface OnPostLongPress {
			void onPostLongPress(ChannelPost post);
		}

		interface OnAttachmentTap {
			void onAttachmentTap(ChannelPost post,
					ChannelPost.ChannelAttachment attachment,
					android.widget.ImageView thumbView,
					android.widget.ProgressBar spinner);
		}

		interface OnCommentTap {
			void onCommentTap(ChannelPost post);
		}

		interface OnNotSentReactionTap {
			void onTap(ChannelQueuedItem item);
		}

		private List<ChannelPost> posts = new ArrayList<>();
		private java.util.Map<String, byte[]> thumbnails =
				java.util.Collections.emptyMap();
		private java.util.Map<Long, java.util.List<org.zerionproject.app
				.api.channel.ChannelReaction>> reactions =
				java.util.Collections.emptyMap();
		private java.util.Map<Long, Integer> commentCounts =
				java.util.Collections.emptyMap();
		private boolean discussionsEnabled = true;
		private java.util.Set<Long> commentExceptions =
				java.util.Collections.emptySet();
		private java.util.Map<Long, ChannelQueuedItem> queuedReactions =
				java.util.Collections.emptyMap();
		private final OnPostLongPress longPressListener;
		private final OnAttachmentTap attachmentTapListener;
		private final OnCommentTap commentTapListener;
		private final OnNotSentReactionTap notSentReactionTap;

		PostAdapter(OnPostLongPress longPressListener,
				OnAttachmentTap attachmentTapListener,
				OnCommentTap commentTapListener,
				OnNotSentReactionTap notSentReactionTap) {
			this.longPressListener = longPressListener;
			this.attachmentTapListener = attachmentTapListener;
			this.commentTapListener = commentTapListener;
			this.notSentReactionTap = notSentReactionTap;
		}

		void setQueuedReactions(
				java.util.Map<Long, ChannelQueuedItem> queued) {
			this.queuedReactions = queued;
		}

		void setPosts(List<ChannelPost> p,
				java.util.Map<String, byte[]> thumbnails,
				java.util.Map<Long, java.util.List<org.zerionproject.app
						.api.channel.ChannelReaction>> reactions,
				java.util.Map<Long, Integer> commentCounts,
				boolean discussionsEnabled,
				java.util.Set<Long> commentExceptions) {
			this.posts = p;
			this.thumbnails = thumbnails;
			this.reactions = reactions;
			this.commentCounts = commentCounts;
			this.discussionsEnabled = discussionsEnabled;
			this.commentExceptions = commentExceptions;
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
			int count = commentCounts.containsKey(p.getSeqNum())
					? commentCounts.get(p.getSeqNum()) : 0;
			holder.bind(p, attachmentTapListener, thumbnails, reactions,
					count, discussionsEnabled
							!= commentExceptions.contains(p.getSeqNum()),
					commentTapListener);
			holder.bindQueuedReaction(p, queuedReactions.get(p.getSeqNum()),
					notSentReactionTap);
			if (p.isWithheld()) {
				holder.itemView.setOnLongClickListener(null);
				holder.itemView.setLongClickable(false);
			} else {
				View.OnLongClickListener menu = v -> {
					longPressListener.onPostLongPress(p);
					return true;
				};
				holder.itemView.setOnLongClickListener(menu);
				holder.setAttachmentLongPress(menu);
			}
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
		private final TextView commentBadge;
		private final LinearLayout attachments;
		private final TextView reactionsView;
		private final android.graphics.Typeface bodyTypeface;

		PostViewHolder(@NonNull View itemView) {
			super(itemView);
			body = itemView.findViewById(R.id.channelPostBodyView);
			bodyTypeface = body.getTypeface();
			timestamp = itemView.findViewById(R.id.channelPostTimestampView);
			signerBadge =
					itemView.findViewById(R.id.channelPostSignerBadge);
			ttlLabel = itemView.findViewById(R.id.channelPostTtlLabel);
			commentBadge =
					itemView.findViewById(R.id.channelPostCommentBadge);
			attachments = itemView.findViewById(
					R.id.channelPostAttachments);
			reactionsView = itemView.findViewById(
					R.id.channelPostReactionsView);
		}

		void setAttachmentLongPress(View.OnLongClickListener listener) {
			for (int n = 0; n < attachments.getChildCount(); n++) {
				attachments.getChildAt(n).setOnLongClickListener(listener);
			}
		}

		void bind(ChannelPost p,
				PostAdapter.OnAttachmentTap attachmentTapListener,
				java.util.Map<String, byte[]> thumbnails,
				java.util.Map<Long, java.util.List<org.zerionproject.app
						.api.channel.ChannelReaction>> reactions,
				int commentCount, boolean discussionsEnabled,
				PostAdapter.OnCommentTap commentTapListener) {
			if (p.isWithheld()) {
				bindWithheld(p);
				return;
			}
			body.setTypeface(bodyTypeface);
			if (discussionsEnabled) {
				commentBadge.setVisibility(View.VISIBLE);
				String label = commentCount > 0
						? "💬 " + commentCount
						: "💬 " + itemView.getContext()
								.getString(R.string.channels_comments_action);
				commentBadge.setText(label);
				String action = itemView.getContext()
						.getString(R.string.channels_action_comments);
				commentBadge.setContentDescription(commentCount > 0
						? action + ", " + commentCount : action);
				commentBadge.setOnClickListener(v ->
						commentTapListener.onCommentTap(p));
			} else {
				commentBadge.setVisibility(View.GONE);
				commentBadge.setOnClickListener(null);
			}
			if (ChannelConstants.DELETED_POST_PLACEHOLDER.equals(p.getBody())) {
				body.setText(R.string.channels_post_deleted);
			} else {
				body.setText(p.getBody());
			}
			String bodyText = p.getBody();
			if (bodyText.indexOf('.') >= 0 || bodyText.indexOf(':') >= 0) {
				android.text.util.Linkify.addLinks(body,
						android.text.util.Linkify.WEB_URLS);
				com.professor.zerion.android.util.UiUtils.makeLinksClickable(
						body, url -> {
							android.content.Context bc = body.getContext();
							if (!(bc instanceof
									androidx.fragment.app.FragmentActivity)) {
								return;
							}
							com.professor.zerion.android.widget
									.LinkDialogFragment f =
									com.professor.zerion.android.widget
											.LinkDialogFragment
											.newInstance(url);
							f.show(((androidx.fragment.app.FragmentActivity)
									bc).getSupportFragmentManager(),
									f.getUniqueTag());
						});
			}
			body.setVisibility(p.getBody().trim().isEmpty()
					? View.GONE : View.VISIBLE);
			bindAttachments(p, attachmentTapListener, thumbnails);
			bindReactions(p, reactions);
			String when = com.professor.zerion.android.util.UiUtils
					.formatChannelHour(itemView.getContext(),
							p.getTimestampHourMs());
			timestamp.setText(p.isEdited() ? when + " · " + itemView
					.getContext().getString(R.string.message_edited) : when);

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

		private void bindWithheld(ChannelPost p) {
			commentBadge.setVisibility(View.GONE);
			commentBadge.setOnClickListener(null);
			body.setText(R.string.channels_post_withheld);
			body.setTypeface(bodyTypeface, android.graphics.Typeface.ITALIC);
			body.setVisibility(View.VISIBLE);
			attachments.removeAllViews();
			attachments.setVisibility(View.GONE);
			reactionsView.setVisibility(View.GONE);
			timestamp.setText(
					com.professor.zerion.android.util.UiUtils.formatChannelHour(
							itemView.getContext(),
							p.getTimestampHourMs()));
			signerBadge.setVisibility(View.GONE);
			ttlLabel.setVisibility(View.GONE);
		}

		private void bindAttachments(ChannelPost p,
				PostAdapter.OnAttachmentTap listener,
				java.util.Map<String, byte[]> thumbnails) {
			attachments.removeAllViews();
			if (p.getAttachments().isEmpty()) {
				attachments.setVisibility(View.GONE);
				return;
			}
			attachments.setVisibility(View.VISIBLE);
			LayoutInflater inflater = LayoutInflater.from(
					itemView.getContext());
			for (ChannelPost.ChannelAttachment att : p.getAttachments()) {
				View row = inflater.inflate(
						R.layout.list_item_channel_attachment,
						attachments, false);
				TextView mime = row.findViewById(
						R.id.channelAttachmentMime);
				TextView size = row.findViewById(
						R.id.channelAttachmentSize);
				android.widget.ImageView thumb = row.findViewById(
						R.id.channelAttachmentThumb);
				android.widget.ProgressBar spinner = row.findViewById(
						R.id.channelAttachmentProgress);
				mime.setText(att.getMimeType());
				size.setText(android.text.format.Formatter
						.formatShortFileSize(itemView.getContext(),
								att.getSizeBytes()));
				thumb.setVisibility(View.GONE);
				spinner.setVisibility(View.GONE);
				if (att.getThumbnail() != null
						&& att.getMimeType().startsWith("video/")) {
					String ck = ChannelFeedActivity.thumbnailKey(
							p.getSeqNum(), att.getBlobHash());
					byte[] decrypted = thumbnails.get(ck);
					android.graphics.Bitmap cached = FEED_THUMB_CACHE.get(ck);
					if (cached != null && !cached.isRecycled()) {
						thumb.setTag(new Object());
						thumb.setImageBitmap(cached);
						thumb.setVisibility(View.VISIBLE);
					} else if (decrypted != null) {
						Object token = new Object();
						thumb.setTag(token);
						THUMB_DECODE_EXECUTOR.execute(() -> {
							android.graphics.Bitmap bmp =
									com.professor.zerion.android.util
											.SafeImageDecoder.decode(
													decrypted, 1280);
							if (bmp == null) return;
							FEED_THUMB_CACHE.put(ck, bmp);
							thumb.post(() -> {
								if (thumb.getTag() == token) {
									thumb.setImageBitmap(bmp);
									thumb.setVisibility(View.VISIBLE);
								}
							});
						});
					}
				}
				row.setOnClickListener(v -> listener.onAttachmentTap(
						p, att, thumb, spinner));
				attachments.addView(row);
			}
		}

		private void bindReactions(ChannelPost p,
				java.util.Map<Long, java.util.List<org.zerionproject.app
						.api.channel.ChannelReaction>> reactions) {
			java.util.List<org.zerionproject.app.api.channel
					.ChannelReaction> all = reactions.get(p.getSeqNum());
			if (all == null || all.isEmpty()) {
				reactionsView.setVisibility(View.GONE);
				return;
			}
			java.util.LinkedHashMap<String, Integer> counts =
					new java.util.LinkedHashMap<>();
			for (org.zerionproject.app.api.channel.ChannelReaction r
					: all) {
				Integer prev = counts.get(r.getEmoji());
				counts.put(r.getEmoji(),
						(prev == null ? 0 : prev) + 1);
			}
			StringBuilder sb = new StringBuilder();
			for (java.util.Map.Entry<String, Integer> entry
					: counts.entrySet()) {
				if (sb.length() > 0) sb.append("   ");
				sb.append(entry.getKey()).append(" ")
						.append(entry.getValue());
			}
			reactionsView.setText(sb.toString());
			reactionsView.setVisibility(View.VISIBLE);
		}

		void bindQueuedReaction(ChannelPost p,
				@Nullable ChannelQueuedItem q,
				PostAdapter.OnNotSentReactionTap tap) {
			if (q == null || p.isWithheld()) {
				reactionsView.setOnClickListener(null);
				reactionsView.setClickable(false);
				return;
			}
			android.content.Context ctx = itemView.getContext();
			CharSequence shown = reactionsView.getVisibility() == View.VISIBLE
					? reactionsView.getText() : "";
			String status = q.isNotSent()
					? q.getText() + " " + ctx.getString(
							R.string.channels_queued_not_sent)
					: ctx.getString(R.string.channels_queued_waiting);
			reactionsView.setText(shown.length() == 0 ? status
					: shown + "  ·  " + status);
			reactionsView.setVisibility(View.VISIBLE);
			if (q.isNotSent()) {
				reactionsView.setOnClickListener(v -> tap.onTap(q));
			} else {
				reactionsView.setOnClickListener(null);
				reactionsView.setClickable(false);
			}
		}

		private static String formatRemaining(android.content.Context ctx,
				long remainingMs) {
			long now = System.currentTimeMillis();
			CharSequence rel =
					android.text.format.DateUtils.getRelativeTimeSpanString(
							now + remainingMs, now,
							android.text.format.DateUtils.MINUTE_IN_MILLIS,
							android.text.format.DateUtils
									.FORMAT_ABBREV_RELATIVE);
			return rel.toString();
		}
	}
}

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
import androidx.annotation.StringRes;
import androidx.appcompat.widget.Toolbar;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.accessibility.Announcer;
import com.professor.zerion.android.accessibility.RowReading;
import com.professor.zerion.android.accessibility.RowStates;
import com.professor.zerion.android.accessibility.TouchTargets;
import com.professor.zerion.android.attachment.AttachmentKinds;
import com.professor.zerion.android.attachment.SaveCleanCopy;
import com.professor.zerion.android.conversation.MediaViewer;
import com.professor.zerion.android.message.MessageState;
import com.professor.zerion.android.message.MessageStates;
import com.professor.zerion.android.message.PendingSends;
import com.professor.zerion.android.selection.MessageInfo;
import com.professor.zerion.android.selection.SelectedFill;
import com.professor.zerion.android.selection.SelectionAction;
import com.professor.zerion.android.selection.SelectionBar;
import com.professor.zerion.android.selection.SelectionRows;
import com.professor.zerion.android.util.Feedback;

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
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Executor;

import javax.inject.Inject;

import static com.professor.zerion.android.util.UiUtils.resolveColorAttribute;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ChannelFeedActivity extends ZerionActivity
		implements EventListener, SelectionBar.Host {

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
	private android.widget.ImageButton composeSendButton;
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
	private final Announcer syncAnnouncer = new Announcer();
	private TextView readOnlyFooter;
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
	private List<ChannelPost> shownPosts = new ArrayList<>();
	private List<ChannelQueuedItem> shownQueuedPosts = new ArrayList<>();
	private boolean scrollNext = false;
	private long currentPinnedSeq = ChannelState.NO_PINNED_POST;
	private SelectionBar selectionBar;
	private final LinkedHashSet<Long> selectedSeqs = new LinkedHashSet<>();
	private static final int MENU_ITEM_SETTINGS = 7341;
	private static final int MENU_ITEM_MUTE = 7342;
	private static final int MENU_ITEM_SHARE = 7343;

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
		queueScope = ChannelCommentsActivity.queueScope(channelId);
		pendingScope = PendingSends.channelPostScope(channelId);
		pendingSends.addClearListener(clearListener);

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
		readOnlyFooter = findViewById(R.id.channelFeedReadOnlyFooter);

		setSupportActionBar(toolbar);
		if (getSupportActionBar() != null) {
			getSupportActionBar().setDisplayHomeAsUpEnabled(true);
		}
		toolbar.setNavigationOnClickListener(v -> finish());
		toolbar.setOnClickListener(v -> openSettings());
		androidx.core.view.ViewCompat.replaceAccessibilityAction(toolbar,
				androidx.core.view.accessibility.AccessibilityNodeInfoCompat
						.AccessibilityActionCompat.ACTION_CLICK,
				getString(R.string.channels_settings_title), null);

		adapter = new PostAdapter(
				new PostAdapter.OnPostSelection() {
					@Override
					public void onLongPress(ChannelPost post) {
						startOrToggle(post);
					}

					@Override
					public boolean onTap(ChannelPost post) {
						return tapWhileSelecting(post);
					}
				}, selectedSeqs,
				(post, att, thumb, spinner) -> {
					if (!tapWhileSelecting(post)) {
						handleAttachmentTap(post, att, thumb, spinner);
					}
				},
				post -> {
					if (!tapWhileSelecting(post)) openComments(post);
				},
				post -> {
					if (!tapWhileSelecting(post)) showReactionPicker(post);
				},
				(post, item) -> {
					if (!tapWhileSelecting(post)) {
						showNotSentDialog(
								java.util.Collections.singletonList(item));
					}
				},
				new PostAdapter.OnExtraRow() {
					@Override
					public void retry(FeedRow row) {
						retryRow(row);
					}

					@Override
					public void delete(FeedRow row) {
						deleteRow(row, false);
					}

					@Override
					public void edit(FeedRow row) {
						deleteRow(row, true);
					}

					@Override
					public boolean composerShown() {
						return ChannelFeedActivity.this.composerShown();
					}
				});
		noticeBanner.setOnClickListener(v -> {
			if (!notSentItems.isEmpty()) {
				showNotSentDialog(new ArrayList<>(notSentItems));
			}
		});
		recycler.setLayoutManager(new LinearLayoutManager(this));
		recycler.setAdapter(adapter);
		selectionBar = new SelectionBar(this, this);
		SelectionRows.clearOnEmptyTap(recycler, selectionBar);

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
		pendingSends.addListener(pendingListener, pendingScope, queueScope);
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
		pendingSends.removeListener(pendingListener);
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
		if (adapter != null) adapter.setMessageStyle(com.professor.zerion.android.settings.MessageStyle.read(this));
		ioExecutor.execute(this::wipeAttachmentStagingDir);
	}

	@Override
	public void onDestroy() {
		pendingSends.removeClearListener(clearListener);
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
			List<ChannelQueuedItem> queuedPosts = new ArrayList<>();
			if (state != null && !state.weArePublisher()) {
				try {
					for (ChannelQueuedItem q
							: channelManager.getQueuedItems(channelId)) {
						if (q.getKind() == ChannelQueuedItem.Kind.REACTION) {
							queuedReactions.put(q.getPostSeqNum(), q);
						} else if (q.getKind() == ChannelQueuedItem.Kind.POST) {
							queuedPosts.add(q);
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
			java.util.Map<String, String> editorNames =
					editorNames(state, posts);
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
				if (finalState != null) {
					weArePublisher = finalState.weArePublisher();
				}
				shownPosts = finalPosts;
				shownQueuedPosts = queuedPosts;
				pendingSends.settle(pendingScope, shown());
				takeBackReturned();
				adapter.setQueuedReactions(queuedReactions);
				adapter.setAuthors(finalState == null ? ""
						: finalState.getName(), "channel:"
						+ org.zerionproject.core.util.StringUtils.toHexString(
								channelId), editorNames, weArePublisher);
				render(finalState, finalPosts, finalStatus,
						thumbnails, reactions, commentCounts,
						finalDiscussionsEnabled);
				bindNotice(finalOutdated, finalGivenUp,
						!otherWaiting.isEmpty());
				showHeldNotices();
				bindSyncStatus(finalState, !finalPosts.isEmpty(), finalSync);
			});
		});
	}

	private java.util.Map<String, String> editorNames(
			@Nullable ChannelState state, List<ChannelPost> posts) {
		java.util.Map<String, String> names = new java.util.HashMap<>();
		if (state == null) return names;
		if (!state.weArePublisher()) {
			try {
				byte[] mine = channelManager.getMyChannelPublicKey(channelId);
				if (mine != null) {
					names.put(bytesToHex(Arrays.copyOf(mine,
							Math.min(32, mine.length))),
							getString(R.string.channels_comment_you));
				}
			} catch (DbException ignored) {
			}
			return names;
		}
		java.util.Set<String> wanted = new java.util.HashSet<>();
		for (ChannelPost p : posts) {
			byte[] signer = p.getDelegateSignerEd25519PubKey();
			if (signer != null) wanted.add(bytesToHex(signer));
		}
		if (wanted.isEmpty()) return names;
		try {
			for (org.zerionproject.app.api.channel.ChannelSubscriber sub
					: channelManager.getAnnouncedSubscribers(channelId)) {
				String key = bytesToHex(sub.getEd25519PubKey());
				String name = sub.getDisplayName().trim();
				if (wanted.contains(key) && !name.isEmpty()) {
					names.put(key, name);
				}
			}
		} catch (DbException ignored) {
		}
		return names;
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
					feedback(R.string.channels_queued_already_sent);
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
		toolbar.setSubtitle(ChannelAccessLabel.access(this, state));
		bindApprovalBanner(state, appStatus);

		List<FeedRow> extra = extraRows();
		if (posts.isEmpty() && extra.isEmpty()) {
			adapter.setPosts(posts, thumbnails, reactions, commentCounts,
					discussionsEnabled, commentExceptions);
			adapter.setExtraRows(extra);
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
			boolean pinToBottom = !feedRendered || scrollNext
					|| isFeedAtBottom();
			scrollNext = false;
			adapter.setPosts(posts, thumbnails, reactions,
					commentCounts, discussionsEnabled, commentExceptions);
			adapter.setExtraRows(extra);
			if (pinToBottom) {
				recycler.scrollToPosition(adapter.getItemCount() - 1);
			}
			feedRendered = true;
		}

		composeBar.setVisibility(canPost ? View.VISIBLE : View.GONE);
		readOnlyFooter.setVisibility(canPost ? View.GONE : View.VISIBLE);
		attachButton.setVisibility(weArePublisher ? View.VISIBLE : View.GONE);

		currentPinnedSeq = state.getPinnedPostSeq();
		bindPinnedBanner(state, posts);
		dropVanishedFromSelection();
	}

	static final class FeedRow {

		final String key;
		final String text;
		final long timeMs;
		final MessageState state;
		@Nullable
		final ChannelQueuedItem queued;
		@Nullable
		final PendingSends.Entry pending;
		final boolean canEdit;
		final boolean canRetry;
		final boolean canDelete;

		FeedRow(String key, String text, long timeMs, MessageState state,
				@Nullable ChannelQueuedItem queued,
				@Nullable PendingSends.Entry pending, boolean canEdit,
				boolean canRetry, boolean canDelete) {
			this.key = key;
			this.text = text;
			this.timeMs = timeMs;
			this.state = state;
			this.queued = queued;
			this.pending = pending;
			this.canEdit = canEdit;
			this.canRetry = canRetry;
			this.canDelete = canDelete;
		}
	}

	private List<FeedRow> extraRows() {
		List<FeedRow> rows = new ArrayList<>();
		for (ChannelQueuedItem q : shownQueuedPosts) {
			boolean retrying = pendingSends.isQueuedRetry(queueScope,
					q.getId());
			boolean notSent = q.isNotSent() && !retrying;
			MessageState state = retrying ? MessageState.SENDING
					: notSent ? MessageState.NOT_SENT : MessageState.WAITING;
			rows.add(new FeedRow("queued:" + q.getId(), q.getText(),
					q.getQueuedAtMs(), state, q, null, notSent, notSent,
					notSent));
		}
		for (PendingSends.Entry e : PendingSends.visible(
				pendingSends.entries(pendingScope), shown())) {
			rows.add(new FeedRow("pending:" + e.getId(), e.getText(),
					e.getCreatedAtMs(), e.getState(), null, e, e.canEdit(),
					e.canRetry(), e.canDiscard()));
		}
		return rows;
	}

	static String postIdentity(long seq) {
		return "post:" + seq;
	}

	static String queuedIdentity(long itemId) {
		return "queued:" + itemId;
	}

	private PendingSends.Shown shown() {
		java.util.Set<String> ids = new java.util.HashSet<>();
		for (ChannelPost p : shownPosts) ids.add(postIdentity(p.getSeqNum()));
		for (ChannelQueuedItem q : shownQueuedPosts) {
			ids.add(queuedIdentity(q.getId()));
		}
		return ids::contains;
	}

	private void showExtraRows() {
		List<FeedRow> extra = extraRows();
		if (extra.isEmpty() && shownPosts.isEmpty()) {
			adapter.setExtraRows(extra);
			loadChannel();
			return;
		}
		boolean pin = scrollNext || isFeedAtBottom()
				|| recycler.getVisibility() != View.VISIBLE;
		scrollNext = false;
		feedProgress.setVisibility(View.GONE);
		connectingText.setVisibility(View.GONE);
		cancelSlowConnectHint();
		emptyView.setVisibility(View.GONE);
		recycler.setVisibility(View.VISIBLE);
		adapter.setExtraRows(extra);
		if (pin) recycler.scrollToPosition(adapter.getItemCount() - 1);
	}

	static PendingSends.Attempt postAttempt(ChannelManager manager,
			byte[] channelId, String body, long ttlSeconds,
			boolean publisher) {
		return () -> {
			try {
				long seq = manager.publishPostWithSeq(channelId, body,
						ttlSeconds);
				return PendingSends.Result.stored(seq < 0L ? null
						: postIdentity(seq), null);
			} catch (ChannelRequestQueuedException queued) {
				return PendingSends.Result.handedOver(
						queued.getItemId() == -1L ? null
								: queuedIdentity(queued.getItemId()),
						PendingSends.Notice.of(queued.isBehindEarlierItems()
								? R.string.channels_queued_behind_toast
								: R.string.channels_post_queued_toast));
			} catch (org.zerionproject.app.api.channel
					.ChannelSentNotSavedException sent) {
				return PendingSends.Result.stored(postIdentity(
						sent.getItemId()), null);
			} catch (org.zerionproject.app.api.channel
					.ChannelPostTooLongException tooLong) {
				return PendingSends.Result.refused(PendingSends.Notice.of(
						R.string.text_too_long));
			} catch (org.zerionproject.app.api.channel
					.ChannelNoAccessException noAccess) {
				return PendingSends.Result.refused(PendingSends.Notice.of(
						R.string.channels_no_access_toast));
			} catch (org.zerionproject.app.api.channel
					.ChannelRefusedException refused) {
				return PendingSends.Result.refused(PendingSends.Notice.of(
						R.string.channels_editor_post_not_sent));
			} catch (DbException ex) {
				return PendingSends.Result.failed(PendingSends.Notice.of(
						publisher ? R.string.channels_compose_error_publish
								: R.string.channels_editor_post_not_sent));
			}
		};
	}

	private void onPendingChanged(PendingSends.Change change) {
		boolean mine = change.getScope().equals(pendingScope);
		if (!mine && !change.getScope().equals(queueScope)) return;
		runOnUiThreadUnlessDestroyed(() -> {
			PendingSends.Notice notice = change.getNotice();
			switch (change.getKind()) {
				case STORED:
				case HANDED_OVER:
					if (mine && composeInput.length() == 0) clearDraft();
					if (notice != null) feedback(notice.text(this));
					showExtraRows();
					loadChannel();
					break;
				case RETURNED:
					takeBackReturned();
					showExtraRows();
					if (notice != null) feedback(notice.text(this));
					break;
				case FAILED:
					showExtraRows();
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
				case REMOVED:
					showExtraRows();
					showHeldNotices();
					break;
				default:
					showExtraRows();
					break;
			}
		});
	}

	private void onPendingCleared() {
		if (undoBar != null) {
			undoBar.dismiss();
			undoBar = null;
		}
		showExtraRows();
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

	private void putInComposer(String text, long ttlSeconds) {
		composeInput.setText(text);
		composeInput.setSelection(text.length());
		selectedTtlSeconds = stricterTimer(selectedTtlSeconds, ttlSeconds);
		updateTtlButtonTint();
		if (selectedTtlSeconds != ttlSeconds) {
			feedback(R.string.channels_ttl_kept_shorter);
		}
	}

	static long stricterTimer(long picked, long post) {
		if (picked <= 0L) return post;
		if (post <= 0L) return picked;
		return Math.min(picked, post);
	}

	private static long ttlOf(PendingSends.Entry e) {
		Object payload = e.getPayload();
		return payload instanceof Long ? (Long) payload : 0L;
	}

	private void copy(String text) {
		com.professor.zerion.android.util.SecureClipboard.copy(this,
				"message", text);
		feedback(R.string.copied_to_clipboard);
	}

	private void takeBackReturned() {
		if (!composerFree()) return;
		for (PendingSends.Entry e : pendingSends.entries(pendingScope)) {
			if (e.isReturned() && e.canEdit() && pendingSends.discard(e)) {
				putInComposer(e.getText(), ttlOf(e));
				return;
			}
		}
	}

	private void retryRow(FeedRow row) {
		PendingSends.Entry e = row.pending;
		if (e != null) {
			pendingSends.retry(e, ioExecutor);
			showExtraRows();
		} else if (row.queued != null) {
			retryQueuedPost(row.queued);
		}
	}

	private void retryAllNotSent() {
		for (PendingSends.Entry e : pendingSends.retryable(pendingScope)) {
			pendingSends.retry(e, ioExecutor);
		}
		showExtraRows();
	}

	private void deleteRow(FeedRow row, boolean edit) {
		if (edit && !composerShown()) {
			copy(row.text);
			showExtraRows();
			return;
		}
		if (edit && composeInput.length() > 0) {
			feedback(R.string.message_state_edit_busy);
			showExtraRows();
			return;
		}
		PendingSends.Entry e = row.pending;
		if (e != null) {
			if (!pendingSends.discard(e)) {
				showExtraRows();
				return;
			}
			if (edit) {
				putInComposer(e.getText(), ttlOf(e));
			} else {
				undoBar = Feedback.show(composeBar, getText(
						R.string.message_state_deleted),
						R.string.message_state_undo, () -> {
							undoBar = null;
							pendingSends.restore(e);
							showExtraRows();
						});
			}
			showExtraRows();
		} else if (row.queued != null) {
			deleteQueuedPost(row.queued, edit);
		}
	}

	private void deleteQueuedPost(ChannelQueuedItem item, boolean edit) {
		if (!changingQueued.add(item.getId())) {
			showExtraRows();
			return;
		}
		boolean publisher = weArePublisher;
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
					feedback(R.string.channels_post_delete_failed);
				} else if (finalResult
						== ChannelQueuedItem.DeleteResult.SENT) {
					feedback(R.string.channels_queued_already_sent);
				} else if (finalResult
						== ChannelQueuedItem.DeleteResult.GONE) {
					feedback(R.string.message_state_maybe_sent);
				} else if (edit && composerFree()) {
					putInComposer(item.getText(), item.getTtlSeconds());
				} else {
					Runnable restore = () -> {
						pendingSends.addNotSent(pendingScope, item.getText(),
								item.getTtlSeconds(),
								item.getText().length(), true,
								postAttempt(channelManager, channelId,
										item.getText(), item.getTtlSeconds(),
										publisher));
						showExtraRows();
					};
					if (edit) {
						restore.run();
					} else {
						undoBar = Feedback.show(composeBar, getText(
								R.string.message_state_deleted),
								R.string.message_state_undo, () -> {
									undoBar = null;
									restore.run();
								});
					}
				}
				loadChannel();
			});
		});
	}

	private void retryQueuedPost(ChannelQueuedItem item) {
		if (!pendingSends.beginQueuedRetry(queueScope, item.getId())) {
			showExtraRows();
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
					feedback(R.string.channels_editor_post_not_sent);
				}
				loadChannel();
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

	private void feedback(CharSequence text) {
		Feedback.show(composeBar, text);
	}

	private void feedback(int res) {
		feedback(getText(res));
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
		syncAnnouncer.update(syncStatusLine, line == null ? null
				: syncStatusKind(sync, postsHeld), line == null ? "" : line);
	}

	@StringRes
	static int syncStatusKind(ChannelSyncStatus sync, boolean postsHeld) {
		long at = sync.getLastUpdatedMs();
		if (sync.isDormant() && at >= 0L) {
			return R.string.channels_sync_not_updated_since;
		}
		if (!postsHeld) return 0;
		if (at < 0L) {
			return sync.isLastAttemptFailed() || sync.isDormant()
					? R.string.channels_sync_saved_posts : 0;
		}
		return sync.isLastAttemptFailed()
				? R.string.channels_sync_saved_posts_last_updated
				: R.string.channels_sync_last_updated;
	}

	@Nullable
	static String syncStatusText(Context ctx, ChannelSyncStatus sync,
			boolean postsHeld) {
		int kind = syncStatusKind(sync, postsHeld);
		if (kind == 0) return null;
		long at = sync.getLastUpdatedMs();
		if (kind == R.string.channels_sync_not_updated_since) {
			return ctx.getString(kind,
					android.text.format.DateUtils.formatDateTime(ctx, at,
							android.text.format.DateUtils.FORMAT_SHOW_DATE
							| android.text.format.DateUtils
									.FORMAT_ABBREV_MONTH));
		}
		if (kind == R.string.channels_sync_saved_posts) {
			return ctx.getString(kind);
		}
		int flags = sameDay(at, System.currentTimeMillis())
				? android.text.format.DateUtils.FORMAT_SHOW_TIME
				: android.text.format.DateUtils.FORMAT_SHOW_DATE
				| android.text.format.DateUtils.FORMAT_ABBREV_MONTH;
		String when = android.text.format.DateUtils.formatDateTime(ctx, at,
				flags);
		return ctx.getString(kind, when);
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
					R.string.channels_waiting_for_approval_banner);
			approvalBanner.setVisibility(View.VISIBLE);
		} else if (appStatus == ApplicationStatus.DENIED) {
			approvalBanner.setText(
					R.string.channels_request_denied_banner);
			approvalBanner.setVisibility(View.VISIBLE);
		} else if (appStatus == ApplicationStatus.NOT_APPLIED
				&& state.getJoinCapability() == null) {
			approvalBanner.setText(R.string.channels_request_access_banner);
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
				.setTitle(R.string.channels_request_access_title)
				.setView(view)
				.setPositiveButton(R.string.channels_request_access_action,
						(d, w) -> applyAs(nameInput.getText() == null ? ""
								: nameInput.getText().toString().trim()))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void applyAs(String name) {
		if (name.isEmpty()) {
			Toast.makeText(this, R.string.channels_request_access_name_missing,
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
									? R.string.channels_request_access_unconfirmed
									: R.string.channels_request_access_failed,
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

	private void startOrToggle(ChannelPost post) {
		if (post.isWithheld()) return;
		if (!selectionBar.isShowing()) {
			selectedSeqs.clear();
			selectedSeqs.add(post.getSeqNum());
			adapter.notifyDataSetChanged();
			selectionBar.show(1);
			return;
		}
		toggle(post);
	}

	private boolean tapWhileSelecting(ChannelPost post) {
		if (!selectionBar.isShowing()) return false;
		toggle(post);
		return true;
	}

	private void toggle(ChannelPost post) {
		if (post.isWithheld()) return;
		long seq = post.getSeqNum();
		if (!selectedSeqs.remove(seq)) selectedSeqs.add(seq);
		if (selectedSeqs.isEmpty()) {
			adapter.notifyDataSetChanged();
		} else {
			adapter.refreshPost(seq);
		}
		updateSelectionBar();
	}

	private void updateSelectionBar() {
		if (selectedSeqs.isEmpty()) {
			selectionBar.close();
		} else {
			selectionBar.show(selectedSeqs.size());
		}
	}

	private void dropVanishedFromSelection() {
		if (selectedSeqs.isEmpty()) return;
		Set<Long> shown = new java.util.HashSet<>();
		for (ChannelPost p : shownPosts) {
			if (!p.isWithheld()) shown.add(p.getSeqNum());
		}
		if (selectedSeqs.retainAll(shown)) {
			adapter.notifyDataSetChanged();
			updateSelectionBar();
		} else {
			selectionBar.refresh();
		}
	}

	private List<ChannelPost> selectedPosts() {
		List<ChannelPost> out = new ArrayList<>();
		for (ChannelPost p : shownPosts) {
			if (selectedSeqs.contains(p.getSeqNum()) && !p.isWithheld()) {
				out.add(p);
			}
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

	private static boolean isDeleted(ChannelPost post) {
		return ChannelConstants.DELETED_POST_PLACEHOLDER.equals(
				post.getBody());
	}

	@Override
	public Set<SelectionAction> offeredActions() {
		List<ChannelPost> chosen = selectedPosts();
		EnumSet<SelectionAction> offered =
				EnumSet.noneOf(SelectionAction.class);
		if (chosen.isEmpty()) return offered;
		boolean copy = true;
		boolean delete = true;
		for (ChannelPost p : chosen) {
			boolean deleted = isDeleted(p);
			if (deleted || p.getBody().trim().isEmpty()) copy = false;
			if (deleted || !mayChange(p)) delete = false;
		}
		if (copy) offered.add(SelectionAction.COPY);
		if (delete) offered.add(SelectionAction.DELETE);
		if (chosen.size() != 1) return offered;
		ChannelPost p = chosen.get(0);
		boolean deleted = isDeleted(p);
		boolean manage = mayChange(p);
		if (!deleted && commentsOn(p)) offered.add(SelectionAction.REPLY);
		if (!deleted) offered.add(SelectionAction.REACT);
		if (!deleted && !savableAttachments(p).isEmpty()) {
			offered.add(SelectionAction.SAVE);
		}
		if (manage && !deleted
				&& p.isEditableAt(System.currentTimeMillis())) {
			offered.add(SelectionAction.EDIT);
		}
		if (weArePublisher && !deleted) {
			offered.add(p.getSeqNum() == currentPinnedSeq
					? SelectionAction.UNPIN : SelectionAction.PIN);
		}
		if (commentsOn(p) || weArePublisher) {
			offered.add(SelectionAction.COMMENTS);
		}
		if (manage && !deleted) {
			offered.add(commentsOn(p) ? SelectionAction.COMMENTS_OFF
					: SelectionAction.COMMENTS_ON);
		}
		offered.add(SelectionAction.INFO);
		return offered;
	}

	@Override
	public void onSelectionAction(SelectionAction action) {
		List<ChannelPost> chosen = selectedPosts();
		if (chosen.isEmpty()) {
			finishSelection();
			return;
		}
		if (action == SelectionAction.DELETE) {
			confirmDelete(chosen);
			return;
		}
		finishSelection();
		ChannelPost post = chosen.get(0);
		switch (action) {
			case COPY:
				copyPosts(chosen);
				break;
			case REPLY:
				startActivity(ChannelCommentsActivity.writeIntent(this,
						channelId, post.getSeqNum(), expiresAt(post)));
				break;
			case REACT:
				showReactionPicker(post);
				break;
			case SAVE:
				saveFrom(post);
				break;
			case EDIT:
				showEditDialog(post);
				break;
			case PIN:
				handlePin(post.getSeqNum());
				break;
			case UNPIN:
				handleUnpin();
				break;
			case COMMENTS:
				openComments(post);
				break;
			case COMMENTS_OFF:
				setPostComments(post, false);
				break;
			case COMMENTS_ON:
				setPostComments(post, true);
				break;
			case INFO:
				showInfo(post);
				break;
			default:
				break;
		}
	}

	@Override
	public void onSelectionClosed() {
		if (selectedSeqs.isEmpty()) return;
		selectedSeqs.clear();
		adapter.notifyDataSetChanged();
	}

	private void showInfo(ChannelPost post) {
		MessageInfo info = new MessageInfo(this).line(
				R.string.message_info_posted,
				com.professor.zerion.android.util.UiUtils.formatChannelHour(
						this, post.getTimestampHourMs()));
		if (post.isEphemeral()) {
			long remaining = post.getTimestampHourMs() + post.getTtlMs()
					- System.currentTimeMillis();
			if (remaining > 0) {
				info.line(R.string.message_info_disappears,
						PostViewHolder.formatRemaining(this, remaining));
			}
		}
		if (post.signedByDelegate()) info.note(R.string.message_info_by_editor);
		if (post.isEdited()) info.note(R.string.message_info_edited);
		info.show();
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
			} catch (org.zerionproject.app.api.channel
					.ChannelSentNotSavedException published) {
				runOnUiThreadUnlessDestroyed(this::loadChannel);
			} catch (ChannelRequestQueuedException queued) {
				runOnUiThreadUnlessDestroyed(() -> {
					feedback(queued.isBehindEarlierItems()
							? R.string.channels_queued_behind_toast
							: R.string.channels_change_queued_toast);
					loadChannel();
				});
			} catch (DbException ex) {
				int message = ex instanceof org.zerionproject.app.api.channel
						.ChannelPostTooLongException
						? R.string.text_too_long
						: R.string.channels_post_edit_failed;
				runOnUiThreadUnlessDestroyed(() -> feedback(message));
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
					feedback(queued.isBehindEarlierItems()
							? R.string.channels_queued_behind_toast
							: R.string.channels_change_queued_toast);
					loadChannel();
				});
			} catch (DbException ex) {
				runOnUiThreadUnlessDestroyed(() -> feedback(
						R.string.channels_discussions_toggle_failed_toast));
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
				post.getSeqNum(), expiresAt(post)));
	}

	static long expiresAt(ChannelPost post) {
		return post.isEphemeral() && post.getTtlMs() > 0L
				? post.getTimestampHourMs() + post.getTtlMs() : 0L;
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
					feedback(queued.isBehindEarlierItems()
							? R.string.channels_queued_behind_toast
							: R.string.channels_react_queued_toast);
					loadChannel();
				});
			} catch (org.zerionproject.app.api.channel
					.ChannelReactionTooSoonException tooSoon) {
				runOnUiThreadUnlessDestroyed(() -> feedback(
						R.string.channels_react_too_soon));
			} catch (org.zerionproject.app.api.channel
					.ChannelNoAccessException noAccess) {
				runOnUiThreadUnlessDestroyed(() -> feedback(
						R.string.channels_no_access_toast));
			} catch (DbException ignored) {
				runOnUiThreadUnlessDestroyed(() -> feedback(
						R.string.channels_react_failed));
			}
		});
	}

	private void confirmDelete(List<ChannelPost> posts) {
		String question = posts.size() == 1
				? getString(R.string.channels_post_delete_confirm)
				: getResources().getQuantityString(
						R.plurals.channels_posts_delete_confirm, posts.size(),
						posts.size());
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this)
				.setMessage(question)
				.setPositiveButton(R.string.channels_action_delete,
						(d, w) -> {
							finishSelection();
							deletePosts(posts);
						})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void deletePosts(List<ChannelPost> posts) {
		if (posts.size() == 1) {
			handleDelete(posts.get(0));
			return;
		}
		ioExecutor.execute(() -> {
			boolean failed = false;
			boolean queued = false;
			boolean behind = false;
			for (ChannelPost post : posts) {
				try {
					channelManager.deletePost(channelId, post.getSeqNum());
				} catch (org.zerionproject.app.api.channel
						.ChannelSentNotSavedException published) {
				} catch (ChannelRequestQueuedException q) {
					queued = true;
					if (q.isBehindEarlierItems()) behind = true;
				} catch (DbException ex) {
					failed = true;
				}
			}
			boolean finalFailed = failed;
			boolean finalQueued = queued;
			boolean finalBehind = behind;
			runOnUiThreadUnlessDestroyed(() -> {
				if (finalFailed) {
					feedback(R.string.channels_post_delete_failed);
				} else if (finalQueued) {
					feedback(finalBehind ? R.string.channels_queued_behind_toast
							: R.string.channels_change_queued_toast);
				}
				loadChannel();
			});
		});
	}

	private void handleDelete(ChannelPost post) {
		ioExecutor.execute(() -> {
			try {
				channelManager.deletePost(channelId, post.getSeqNum());
				runOnUiThreadUnlessDestroyed(this::loadChannel);
			} catch (org.zerionproject.app.api.channel
					.ChannelSentNotSavedException published) {
				runOnUiThreadUnlessDestroyed(this::loadChannel);
			} catch (ChannelRequestQueuedException queued) {
				runOnUiThreadUnlessDestroyed(() -> {
					feedback(queued.isBehindEarlierItems()
							? R.string.channels_queued_behind_toast
							: R.string.channels_change_queued_toast);
					loadChannel();
				});
			} catch (DbException ignored) {
				runOnUiThreadUnlessDestroyed(() -> feedback(
						R.string.channels_post_delete_failed));
			}
		});
	}

	private void copyPosts(List<ChannelPost> posts) {
		StringBuilder sb = new StringBuilder();
		for (ChannelPost post : posts) {
			if (sb.length() > 0) sb.append("\n\n");
			sb.append(post.getBody());
		}
		com.professor.zerion.android.util.SecureClipboard.copy(this,
				"zerion-channel-post", sb.toString());
		Toast.makeText(this, posts.size() == 1 ? R.string.channels_post_copied
				: R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
	}

	private static List<ChannelPost.ChannelAttachment> savableAttachments(
			ChannelPost post) {
		List<ChannelPost.ChannelAttachment> savable = new ArrayList<>();
		for (ChannelPost.ChannelAttachment att : post.getAttachments()) {
			if (SaveCleanCopy.canSave(att.getMimeType())) savable.add(att);
		}
		return savable;
	}

	private void saveFrom(ChannelPost post) {
		List<ChannelPost.ChannelAttachment> savable = savableAttachments(post);
		if (savable.isEmpty()) return;
		if (savable.size() == 1) {
			save(post, savable.get(0));
			return;
		}
		CharSequence[] labels = new CharSequence[savable.size()];
		for (int n = 0; n < savable.size(); n++) {
			String label = getString(savable.get(n).getMimeType()
					.toLowerCase(Locale.ROOT).startsWith("image/")
					? R.string.save_image : R.string.save_file);
			labels[n] = label + " " + (n + 1);
		}
		new com.professor.zerion.android.security.SecureAlertDialogBuilder(
				this)
				.setTitle(R.string.selection_save)
				.setItems(labels, (d, which) -> save(post,
						savable.get(which)))
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void save(ChannelPost post, ChannelPost.ChannelAttachment att) {
		SaveCleanCopy.confirm(this, att.getMimeType(),
				() -> saveAttachment(post.getSeqNum(), att.getBlobHash()));
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
				? resolveColorAttribute(this,
				R.attr.zerionColorAccentIcon)
				: resolveColorAttribute(this,
				R.attr.zerionColorOnSurfaceMuted);
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
			feedback(R.string.channels_compose_error_empty);
			return;
		}
		long ttlSeconds = readSelectedTtlSeconds();
		PendingSends.Entry pending = pendingSends.add(pendingScope, body,
				ttlSeconds, body.length(), true, postAttempt(channelManager,
						channelId, body, ttlSeconds, weArePublisher));
		scrollNext = true;
		showExtraRows();
		composeInput.setText("");
		pendingSends.start(pending, ioExecutor);
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
				presentAttachment(post, att, finalBlob, thumbView);
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

	private void presentAttachment(ChannelPost post,
			ChannelPost.ChannelAttachment att, AttachmentBlob blob,
			android.widget.ImageView thumbView) {
		String mime = blob.getMimeType();
		ioExecutor.execute(() -> {
			byte[] content = blob.getPlaintextBytes();
			if (MediaViewer.imageType(mime, content) != null) {
				String ck = bytesToHex(att.getBlobHash());
				android.graphics.Bitmap bmp = FEED_THUMB_CACHE.get(ck);
				if (bmp == null || bmp.isRecycled()) {
					bmp = com.professor.zerion.android.util.SafeImageDecoder
							.decode(content, 1280);
					if (bmp != null) FEED_THUMB_CACHE.put(ck, bmp);
				}
				if (bmp != null) {
					android.graphics.Bitmap finalBmp = bmp;
					runOnUiThreadUnlessDestroyed(() -> {
						thumbView.setImageBitmap(finalBmp);
						thumbView.setVisibility(View.VISIBLE);
						showInViewer(post, content, mime, false);
					});
					return;
				}
				refuse(R.string.grouptr_attach_read_failed);
				return;
			}
			if (MediaViewer.videoType(mime, content) != null) {
				runOnUiThreadUnlessDestroyed(() ->
						showInViewer(post, content, mime, true));
				return;
			}
			String viewType = com.professor.zerion.android.util
					.ExternalViewerTypes.typeForContent(mime,
							blob.getPlaintextBytes());
			if (viewType == null) {
				refuse(R.string.channels_attach_open_failed);
				return;
			}
			if (viewType.startsWith("image/")) {
				refuse(R.string.grouptr_attach_read_failed);
				return;
			}
			if (viewType.startsWith("video/")) {
				refuse(R.string.video_playback_error);
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

	private void refuse(int message) {
		runOnUiThreadUnlessDestroyed(() -> Toast.makeText(this, message,
				Toast.LENGTH_LONG).show());
	}

	private void showInViewer(ChannelPost post, byte[] content, String mime,
			boolean video) {
		CharSequence title = toolbar.getTitle();
		long ttl = post.getTtlMs();
		MediaViewer.Item item = new MediaViewer.Item(content, mime)
				.titled(title == null ? "" : title.toString(), 0L)
				.secure(false)
				.from(MediaViewer.channelScope(channelId),
						MediaViewer.channelPost(channelId, post.getSeqNum()))
				.expiresAt(ttl > 0 ? post.getTimestampHourMs() + ttl
						: Long.MAX_VALUE)
				.taken();
		boolean shown = video ? MediaViewer.playVideo(this, ioExecutor, item)
				: MediaViewer.showImage(this, ioExecutor, item);
		if (!shown) {
			Arrays.fill(content, (byte) 0);
			Toast.makeText(this, R.string.channels_attach_open_failed,
					Toast.LENGTH_LONG).show();
		}
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
			feedback(R.string.channels_attach_too_many);
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
							feedback(R.string.channels_attach_read_failed);
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
								feedback(R.string.channels_attach_too_large);
							});
							return;
						}
					}
					bytes = buf.toByteArray();
				} catch (java.io.IOException ex) {
					runOnUiThreadUnlessDestroyed(() -> {
						setComposeBusy(false);
						feedback(R.string.channels_attach_read_failed);
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
							feedback(R.string.channels_attach_read_failed);
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
				publishWithAttachments(composedBody, ttlSeconds, specs);
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
					feedback(message);
				});
			}
		});
	}

	private void publishWithAttachments(String body, long ttlSeconds,
			List<AttachmentSpec> specs) throws DbException {
		try {
			channelManager.publishPostWithAttachments(channelId, body,
					ttlSeconds, specs);
		} catch (org.zerionproject.app.api.channel
				.ChannelSentNotSavedException sent) {
		}
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

	static void setChannelMuted(android.content.Context context,
			byte[] channelId, boolean muted) {
		com.professor.zerion.android.AppModule.getAndroidComponent(context)
				.profilePreferences().edit()
				.putBoolean(muteKey(channelId), muted).apply();
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
		menu.add(0, MENU_ITEM_SETTINGS, 0, R.string.channels_settings_title)
				.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		menu.add(0, MENU_ITEM_SHARE, 1, R.string.channels_action_share_invite)
				.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		android.view.MenuItem mute = menu.add(0, MENU_ITEM_MUTE, 2,
				R.string.channels_mute_notifications);
		mute.setCheckable(true);
		mute.setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_NEVER);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onPrepareOptionsMenu(android.view.Menu menu) {
		android.view.MenuItem muteItem = menu.findItem(MENU_ITEM_MUTE);
		if (muteItem != null) {
			muteItem.setChecked(isChannelMuted(this, channelId));
		}
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(android.view.MenuItem item) {
		if (item.getItemId() == MENU_ITEM_SETTINGS) {
			openSettings();
			return true;
		}
		if (item.getItemId() == MENU_ITEM_SHARE) {
			ChannelShare.copyInvite(this, channelManager, ioExecutor,
					channelId);
			return true;
		}
		if (item.getItemId() == MENU_ITEM_MUTE) {
			boolean muted = !isChannelMuted(this, channelId);
			setChannelMuted(this, channelId, muted);
			item.setChecked(muted);
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void openSettings() {
		if (channelId.length == 0) return;
		startActivity(ChannelSettingsActivity.intent(this, channelId));
	}

	static final class Shown {

		@Nullable
		final ChannelPost post;
		@Nullable
		final FeedRow row;
		final String author;
		final String avatarKey;
		final boolean editorTag;
		final List<ChannelReaction> reactions;
		final int commentCount;
		final boolean commentsOn;
		@Nullable
		final ChannelQueuedItem queuedReaction;
		final List<byte[]> thumbnails;

		Shown(ChannelPost post, String author, String avatarKey,
				boolean editorTag, List<ChannelReaction> reactions,
				int commentCount, boolean commentsOn,
				@Nullable ChannelQueuedItem queuedReaction,
				List<byte[]> thumbnails) {
			this.post = post;
			this.row = null;
			this.author = author;
			this.avatarKey = avatarKey;
			this.editorTag = editorTag;
			this.reactions = reactions;
			this.commentCount = commentCount;
			this.commentsOn = commentsOn;
			this.queuedReaction = queuedReaction;
			this.thumbnails = thumbnails;
		}

		Shown(FeedRow row, String author, String avatarKey,
				boolean editorTag) {
			this.post = null;
			this.row = row;
			this.author = author;
			this.avatarKey = avatarKey;
			this.editorTag = editorTag;
			this.reactions = java.util.Collections.emptyList();
			this.commentCount = 0;
			this.commentsOn = false;
			this.queuedReaction = null;
			this.thumbnails = java.util.Collections.emptyList();
		}

		String key() {
			if (post != null) return postIdentity(post.getSeqNum());
			return row == null ? "" : row.key;
		}

		boolean showsTheSameAs(Shown o) {
			if (post == null || o.post == null) {
				return row != null && o.row != null && sameRow(row, o.row);
			}
			return author.equals(o.author) && editorTag == o.editorTag
					&& commentCount == o.commentCount
					&& commentsOn == o.commentsOn
					&& samePost(post, o.post)
					&& sameReactions(reactions, o.reactions)
					&& sameQueued(queuedReaction, o.queuedReaction)
					&& sameThumbnails(thumbnails, o.thumbnails);
		}

		private static boolean sameRow(FeedRow a, FeedRow b) {
			if (a.canEdit || a.canRetry || a.canDelete || b.canEdit
					|| b.canRetry || b.canDelete) {
				return false;
			}
			return a.key.equals(b.key) && a.text.equals(b.text)
					&& a.timeMs == b.timeMs && a.state == b.state
					&& a.canEdit == b.canEdit && a.canRetry == b.canRetry
					&& a.canDelete == b.canDelete && a.pending == b.pending
					&& sameQueued(a.queued, b.queued);
		}

		private static boolean samePost(ChannelPost a, ChannelPost b) {
			if (a == b) return true;
			if (a.getSeqNum() != b.getSeqNum()
					|| a.getTimestampHourMs() != b.getTimestampHourMs()
					|| a.getTtlMs() != b.getTtlMs()
					|| a.isEdited() != b.isEdited()
					|| a.isWithheld() != b.isWithheld()
					|| !a.getBody().equals(b.getBody())
					|| !Arrays.equals(a.getDelegateSignerEd25519PubKey(),
					b.getDelegateSignerEd25519PubKey())
					|| a.getAttachments().size() != b.getAttachments().size()) {
				return false;
			}
			for (int i = 0; i < a.getAttachments().size(); i++) {
				ChannelPost.ChannelAttachment x = a.getAttachments().get(i);
				ChannelPost.ChannelAttachment y = b.getAttachments().get(i);
				if (x.getSizeBytes() != y.getSizeBytes()
						|| !x.getMimeType().equals(y.getMimeType())
						|| !Arrays.equals(x.getBlobHash(), y.getBlobHash())) {
					return false;
				}
			}
			return true;
		}

		private static boolean sameReactions(List<ChannelReaction> a,
				List<ChannelReaction> b) {
			if (a.size() != b.size()) return false;
			for (int i = 0; i < a.size(); i++) {
				ChannelReaction x = a.get(i);
				ChannelReaction y = b.get(i);
				if (!x.getEmoji().equals(y.getEmoji())
						|| !Arrays.equals(x.getSignerEd25519PubKey(),
						y.getSignerEd25519PubKey())) {
					return false;
				}
			}
			return true;
		}

		private static boolean sameQueued(@Nullable ChannelQueuedItem a,
				@Nullable ChannelQueuedItem b) {
			if (a == null || b == null) return a == b;
			return a.getId() == b.getId() && a.isNotSent() == b.isNotSent()
					&& a.getText().equals(b.getText());
		}

		private static boolean sameThumbnails(List<byte[]> a,
				List<byte[]> b) {
			if (a.size() != b.size()) return false;
			for (int i = 0; i < a.size(); i++) {
				byte[] x = a.get(i);
				byte[] y = b.get(i);
				if (x != y && !Arrays.equals(x, y)) return false;
			}
			return true;
		}
	}

	private static class PostAdapter
			extends RecyclerView.Adapter<PostViewHolder> {

		interface OnPostSelection {
			void onLongPress(ChannelPost post);

			boolean onTap(ChannelPost post);
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

		interface OnReactTap {
			void onReactTap(ChannelPost post);
		}

		interface OnNotSentReactionTap {
			void onTap(ChannelPost post, ChannelQueuedItem item);
		}

		interface OnExtraRow {
			void retry(FeedRow row);

			void delete(FeedRow row);

			void edit(FeedRow row);

			boolean composerShown();
		}

		private List<ChannelPost> posts = new ArrayList<>();
		private List<FeedRow> extraRows = new ArrayList<>();
		private List<Shown> items = new ArrayList<>();
		private java.util.Map<String, byte[]> thumbnails =
				java.util.Collections.emptyMap();
		private java.util.Map<Long, java.util.List<ChannelReaction>>
				reactions = java.util.Collections.emptyMap();
		private java.util.Map<Long, Integer> commentCounts =
				java.util.Collections.emptyMap();
		private boolean discussionsEnabled = true;
		private java.util.Set<Long> commentExceptions =
				java.util.Collections.emptySet();
		private java.util.Map<Long, ChannelQueuedItem> queuedReactions =
				java.util.Collections.emptyMap();
		private String channelName = "";
		private String channelKey = "";
		private java.util.Map<String, String> editorNames =
				java.util.Collections.emptyMap();
		private boolean ownerView = false;
		private final OnPostSelection selection;
		private final Set<Long> selected;
		private final OnAttachmentTap attachmentTapListener;
		private final OnCommentTap commentTapListener;
		private final OnReactTap reactTapListener;
		private final OnNotSentReactionTap notSentReactionTap;
		private final OnExtraRow extraRowListener;

		private final com.professor.zerion.android.settings.MessageStyle.Source messageStyle =
				new com.professor.zerion.android.settings.MessageStyle.Source();

		PostAdapter(OnPostSelection selection, @Nullable Set<Long> selected,
				OnAttachmentTap attachmentTapListener,
				OnCommentTap commentTapListener,
				OnReactTap reactTapListener,
				OnNotSentReactionTap notSentReactionTap,
				OnExtraRow extraRowListener) {
			this.selection = selection;
			this.selected = selected == null
					? java.util.Collections.emptySet() : selected;
			this.attachmentTapListener = attachmentTapListener;
			this.commentTapListener = commentTapListener;
			this.reactTapListener = reactTapListener;
			this.notSentReactionTap = notSentReactionTap;
			this.extraRowListener = extraRowListener;
		}

		void setExtraRows(List<FeedRow> rows) {
			this.extraRows = rows;
			rebuild();
		}

		void refreshPost(long seq) {
			for (int i = 0; i < items.size(); i++) {
				ChannelPost p = items.get(i).post;
				if (p != null && p.getSeqNum() == seq) notifyItemChanged(i);
			}
		}

		void setMessageStyle(com.professor.zerion.android.settings.MessageStyle style) {
			if (messageStyle.update(style)) notifyDataSetChanged();
		}

		void setQueuedReactions(
				java.util.Map<Long, ChannelQueuedItem> queued) {
			this.queuedReactions = queued;
		}

		void setAuthors(String channelName, String channelKey,
				java.util.Map<String, String> editorNames, boolean ownerView) {
			this.channelName = channelName;
			this.channelKey = channelKey;
			this.editorNames = editorNames;
			this.ownerView = ownerView;
		}

		void setPosts(List<ChannelPost> p,
				java.util.Map<String, byte[]> thumbnails,
				java.util.Map<Long, java.util.List<ChannelReaction>> reactions,
				java.util.Map<Long, Integer> commentCounts,
				boolean discussionsEnabled,
				java.util.Set<Long> commentExceptions) {
			this.posts = p;
			this.thumbnails = thumbnails;
			this.reactions = reactions;
			this.commentCounts = commentCounts;
			this.discussionsEnabled = discussionsEnabled;
			this.commentExceptions = commentExceptions;
			rebuild();
		}

		private Shown shownPost(ChannelPost p) {
			byte[] signer = p.getDelegateSignerEd25519PubKey();
			String author = channelName;
			String avatarKey = channelKey;
			boolean editorTag = false;
			if (signer != null) {
				String hex = bytesToHex(signer);
				String name = editorNames.get(hex);
				editorTag = true;
				if (name != null) {
					author = name;
					avatarKey = hex;
				}
			}
			List<ChannelReaction> rl = reactions.get(p.getSeqNum());
			Integer cc = commentCounts.get(p.getSeqNum());
			List<byte[]> thumbs = new ArrayList<>(p.getAttachments().size());
			for (ChannelPost.ChannelAttachment att : p.getAttachments()) {
				thumbs.add(thumbnails.get(thumbnailKey(p.getSeqNum(),
						att.getBlobHash())));
			}
			return new Shown(p, author, avatarKey, editorTag,
					rl == null ? java.util.Collections.emptyList() : rl,
					cc == null ? 0 : cc,
					discussionsEnabled != commentExceptions.contains(
							p.getSeqNum()),
					queuedReactions.get(p.getSeqNum()), thumbs);
		}

		private void rebuild() {
			List<Shown> old = items;
			List<Shown> fresh = new ArrayList<>(posts.size()
					+ extraRows.size());
			for (ChannelPost p : posts) fresh.add(shownPost(p));
			for (FeedRow row : extraRows) {
				fresh.add(ownerView
						? new Shown(row, channelName, channelKey, false)
						: new Shown(row, "", channelKey, true));
			}
			items = fresh;
			androidx.recyclerview.widget.DiffUtil.calculateDiff(
					new androidx.recyclerview.widget.DiffUtil.Callback() {
						@Override
						public int getOldListSize() {
							return old.size();
						}

						@Override
						public int getNewListSize() {
							return fresh.size();
						}

						@Override
						public boolean areItemsTheSame(int o, int n) {
							return old.get(o).key().equals(fresh.get(n).key());
						}

						@Override
						public boolean areContentsTheSame(int o, int n) {
							return old.get(o).showsTheSameAs(fresh.get(n));
						}
					}, false).dispatchUpdatesTo(this);
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
			Shown shown = items.get(position);
			if (shown.row != null) {
				holder.bindExtra(shown, extraRowListener,
						messageStyle.get(holder.itemView.getContext()));
				notSelectable(holder);
				return;
			}
			ChannelPost p = shown.post;
			if (p == null) return;
			holder.bind(shown, attachmentTapListener, commentTapListener,
					reactTapListener,
					messageStyle.get(holder.itemView.getContext()));
			holder.bindQueuedReaction(p, shown.queuedReaction,
					notSentReactionTap);
			if (p.isWithheld()) {
				notSelectable(holder);
				return;
			}
			SelectionRows.mark(holder.itemView,
					selected.contains(p.getSeqNum()));
			View.OnLongClickListener press = v -> {
				selection.onLongPress(p);
				return true;
			};
			holder.itemView.setOnLongClickListener(press);
			SelectionRows.nameLongPress(holder.itemView);
			SelectionRows.passPresses(holder.body, holder.itemView,
					() -> !selected.isEmpty());
			RowReading.partOf(holder.body);
			RowReading.oneItem(holder.itemView);
			holder.setAttachmentLongPress(press);
			if (selected.isEmpty()) {
				holder.itemView.setOnClickListener(null);
				holder.itemView.setClickable(false);
			} else {
				holder.itemView.setOnClickListener(v -> selection.onTap(p));
			}
		}

		private static void notSelectable(PostViewHolder holder) {
			SelectionRows.mark(holder.itemView, false);
			SelectionRows.notSelectable(holder.itemView);
			SelectionRows.notSelectable(holder.body);
			holder.itemView.setOnClickListener(null);
			holder.itemView.setClickable(false);
		}

		@Override
		public int getItemCount() {
			return items.size();
		}
	}

	static class PostViewHolder extends RecyclerView.ViewHolder {

		private final TextView avatar;
		private final TextView author;
		private final TextView body;
		private final TextView timestamp;
		private final TextView signerBadge;
		private final TextView ttlLabel;
		private final TextView commentBadge;
		private final TextView reactButton;
		private final View footerDivider;
		private final LinearLayout attachments;
		private final TextView reactionsView;
		private final android.graphics.Typeface bodyTypeface;
		private final android.content.res.ColorStateList bodyColor;
		private final android.content.res.ColorStateList mutedColors;
		private final View bubble;
		private final View notSentActions;
		private final com.professor.zerion.android.homelist.HomeAvatarPalette
				palette;

		PostViewHolder(@NonNull View itemView) {
			super(itemView);
			bubble = itemView.findViewById(R.id.layout);
			SelectedFill.card(bubble);
			notSentActions = itemView.findViewById(
					R.id.messageNotSentActions);
			avatar = itemView.findViewById(R.id.channelPostAvatar);
			author = itemView.findViewById(R.id.channelPostAuthor);
			body = itemView.findViewById(R.id.channelPostBodyView);
			bodyTypeface = body.getTypeface();
			bodyColor = body.getTextColors();
			mutedColors = androidx.appcompat.content.res.AppCompatResources
					.getColorStateList(itemView.getContext(),
							R.color.selectable_on_surface_muted);
			timestamp = itemView.findViewById(R.id.channelPostTimestampView);
			signerBadge =
					itemView.findViewById(R.id.channelPostSignerBadge);
			ttlLabel = itemView.findViewById(R.id.channelPostTtlLabel);
			commentBadge =
					itemView.findViewById(R.id.channelPostCommentBadge);
			reactButton = itemView.findViewById(R.id.channelPostReactButton);
			footerDivider =
					itemView.findViewById(R.id.channelPostFooterDivider);
			attachments = itemView.findViewById(
					R.id.channelPostAttachments);
			reactionsView = itemView.findViewById(
					R.id.channelPostReactionsView);
			SelectedFill.card(reactionsView);
			palette = com.professor.zerion.android.homelist.HomeAvatarPalette
					.load(itemView.getContext());
		}

		void setAttachmentLongPress(View.OnLongClickListener listener) {
			for (int n = 0; n < attachments.getChildCount(); n++) {
				attachments.getChildAt(n).setOnLongClickListener(listener);
			}
		}

		private void bindHeader(String name, String avatarKey,
				boolean editorTag) {
			android.content.Context ctx = itemView.getContext();
			String shown = name.trim().isEmpty() && editorTag
					? ctx.getString(R.string.channels_comment_you) : name;
			author.setText(shown);
			avatar.setText(com.professor.zerion.android.channel
					.ChannelSettingsActivity.initial(shown));
			avatar.setBackgroundTintList(android.content.res.ColorStateList
					.valueOf(palette.fillFor(avatarKey)));
			avatar.setTextColor(palette.getLabel());
			if (editorTag) {
				signerBadge.setText(ctx
						.getString(R.string.channels_post_signer_editor)
						.toUpperCase(Locale.ROOT));
				signerBadge.setVisibility(View.VISIBLE);
			} else {
				signerBadge.setVisibility(View.GONE);
			}
		}

		private void bindFooter(boolean comments, boolean react,
				boolean ttl) {
			footerDivider.setVisibility(comments || react || ttl
					? View.VISIBLE : View.GONE);
		}

		void bind(Shown shown,
				PostAdapter.OnAttachmentTap attachmentTapListener,
				PostAdapter.OnCommentTap commentTapListener,
				PostAdapter.OnReactTap reactTapListener,
				com.professor.zerion.android.settings.MessageStyle style) {
			ChannelPost p = shown.post;
			if (p == null) return;
			android.content.Context ctx = itemView.getContext();
			style.applyTextSize(body);
			MessageStates.outline(bubble, false,
					R.drawable.bg_message_not_sent_card);
			MessageStates.hideActions(notSentActions);
			bindHeader(shown.author, shown.avatarKey, shown.editorTag);
			if (p.isWithheld()) {
				bindWithheld(p);
				return;
			}
			boolean deleted =
					ChannelConstants.DELETED_POST_PLACEHOLDER.equals(p.getBody());
			String commentsLabel = null;
			if (shown.commentsOn) {
				commentBadge.setVisibility(View.VISIBLE);
				commentsLabel = shown.commentCount > 0
						? ctx.getString(R.string.channels_post_comments_count,
						shown.commentCount)
						: ctx.getString(R.string.channels_action_comments);
				commentBadge.setText(commentsLabel);
				String action = ctx.getString(R.string.channels_action_comments);
				commentBadge.setContentDescription(shown.commentCount > 0
						? action + ", " + shown.commentCount : action);
				commentBadge.setOnClickListener(v ->
						commentTapListener.onCommentTap(p));
			} else {
				commentBadge.setVisibility(View.GONE);
				commentBadge.setOnClickListener(null);
			}
			if (deleted) {
				body.setText(R.string.channels_post_deleted);
				body.setTypeface(bodyTypeface, android.graphics.Typeface.ITALIC);
				body.setTextColor(mutedColors);
				reactButton.setVisibility(View.GONE);
				reactButton.setOnClickListener(null);
			} else {
				body.setTypeface(bodyTypeface);
				body.setTextColor(bodyColor);
				body.setText(p.getBody());
				reactButton.setVisibility(View.VISIBLE);
				reactButton.setOnClickListener(v ->
						reactTapListener.onReactTap(p));
			}
			String bodyText = p.getBody();
			if (!deleted && (bodyText.indexOf('.') >= 0
					|| bodyText.indexOf(':') >= 0)) {
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
			body.setVisibility(!deleted && p.getBody().trim().isEmpty()
					? View.GONE : View.VISIBLE);
			bindAttachments(p, attachmentTapListener, shown.thumbnails);
			bindReactions(shown.reactions);
			String when = com.professor.zerion.android.util.UiUtils
					.formatChannelHour(ctx, p.getTimestampHourMs());
			String time = p.isEdited() ? when + " · "
					+ ctx.getString(R.string.message_edited) : when;
			MessageStates.bind(timestamp, time, null);

			boolean ttlShown = false;
			if (p.isEphemeral()) {
				long expiresAt = p.getTimestampHourMs() + p.getTtlMs();
				long remaining = expiresAt - System.currentTimeMillis();
				if (remaining > 0) {
					ttlLabel.setText(ctx.getString(
							R.string.channels_post_disappears,
							formatRemaining(ctx, remaining)));
					ttlShown = true;
				}
			}
			ttlLabel.setVisibility(ttlShown ? View.VISIBLE : View.GONE);
			bindFooter(shown.commentsOn, !deleted, ttlShown);
			bubble.setContentDescription(describe(ctx, shown, time,
					deleted ? ctx.getString(R.string.channels_post_deleted)
							: p.getBody(), commentsLabel));
		}

		static String describe(android.content.Context ctx, Shown shown,
				String time, String body, @Nullable String comments) {
			String out = shown.author;
			if (shown.editorTag) {
				out = join(ctx, out,
						ctx.getString(R.string.channels_post_signer_editor));
			}
			out = join(ctx, out, time);
			if (!body.trim().isEmpty()) out = join(ctx, out, body.trim());
			if (shown.post != null) {
				for (ChannelPost.ChannelAttachment att
						: shown.post.getAttachments()) {
					out = join(ctx, out, AttachmentKinds.describe(ctx,
							att.getMimeType(), att.getSizeBytes()));
				}
			}
			if (comments != null) out = join(ctx, out, comments);
			return out;
		}

		private static String join(android.content.Context ctx, String a,
				String b) {
			return ctx.getString(R.string.channels_post_card_parts, a, b);
		}

		private void bindWithheld(ChannelPost p) {
			commentBadge.setVisibility(View.GONE);
			commentBadge.setOnClickListener(null);
			reactButton.setVisibility(View.GONE);
			reactButton.setOnClickListener(null);
			body.setText(R.string.channels_post_withheld);
			body.setTypeface(bodyTypeface, android.graphics.Typeface.ITALIC);
			body.setTextColor(mutedColors);
			body.setVisibility(View.VISIBLE);
			attachments.removeAllViews();
			attachments.setVisibility(View.GONE);
			reactionsView.setVisibility(View.GONE);
			String time = com.professor.zerion.android.util.UiUtils
					.formatChannelHour(itemView.getContext(),
							p.getTimestampHourMs());
			MessageStates.bind(timestamp, time, null);
			ttlLabel.setVisibility(View.GONE);
			bindFooter(false, false, false);
			bubble.setContentDescription(join(itemView.getContext(), time,
					itemView.getContext().getString(
							R.string.channels_post_withheld)));
		}

		void bindExtra(Shown shown, PostAdapter.OnExtraRow listener,
				com.professor.zerion.android.settings.MessageStyle style) {
			FeedRow row = shown.row;
			if (row == null) return;
			style.applyTextSize(body);
			bindHeader(shown.author, shown.avatarKey, shown.editorTag);
			body.setTypeface(bodyTypeface);
			body.setTextColor(bodyColor);
			body.setText(row.text);
			body.setVisibility(View.VISIBLE);
			attachments.removeAllViews();
			attachments.setVisibility(View.GONE);
			reactionsView.setVisibility(View.GONE);
			reactionsView.setOnClickListener(null);
			reactionsView.setClickable(false);
			commentBadge.setVisibility(View.GONE);
			commentBadge.setOnClickListener(null);
			reactButton.setVisibility(View.GONE);
			reactButton.setOnClickListener(null);
			ttlLabel.setVisibility(View.GONE);
			bindFooter(false, false, false);
			String time = com.professor.zerion.android.util.UiUtils
					.formatChannelHour(itemView.getContext(), row.timeMs);
			MessageStates.bind(timestamp, time, row.state);
			RowStates.of(timestamp).saw(timestamp, row.key, row.state,
					() -> row.text);
			MessageStates.outline(bubble, row.state == MessageState.NOT_SENT,
					R.drawable.bg_message_not_sent_card);
			MessageStates.actions(notSentActions, row.text,
					row.canEdit ? () -> listener.edit(row) : null,
					!listener.composerShown(),
					row.canRetry ? () -> listener.retry(row) : null,
					row.canDelete ? () -> listener.delete(row) : null);
			bubble.setContentDescription(describe(itemView.getContext(),
					shown, String.valueOf(MessageStates.meta(
							itemView.getContext(), time, row.state)),
					row.text, null));
		}

		private void bindAttachments(ChannelPost p,
				PostAdapter.OnAttachmentTap listener,
				List<byte[]> thumbnails) {
			attachments.removeAllViews();
			if (p.getAttachments().isEmpty()) {
				attachments.setVisibility(View.GONE);
				return;
			}
			attachments.setVisibility(View.VISIBLE);
			LayoutInflater inflater = LayoutInflater.from(
					itemView.getContext());
			for (int n = 0; n < p.getAttachments().size(); n++) {
				ChannelPost.ChannelAttachment att = p.getAttachments().get(n);
				View row = inflater.inflate(
						R.layout.list_item_channel_attachment,
						attachments, false);
				SelectedFill.card(row);
				TextView mime = row.findViewById(
						R.id.channelAttachmentMime);
				TextView size = row.findViewById(
						R.id.channelAttachmentSize);
				android.widget.ImageView thumb = row.findViewById(
						R.id.channelAttachmentThumb);
				android.widget.ProgressBar spinner = row.findViewById(
						R.id.channelAttachmentProgress);
				android.widget.ImageView icon = row.findViewById(
						R.id.channelAttachmentIcon);
				android.content.Context ctx = itemView.getContext();
				mime.setText(AttachmentKinds.describe(ctx,
						att.getMimeType()));
				size.setText(android.text.format.Formatter
						.formatShortFileSize(ctx, att.getSizeBytes()));
				icon.setImageResource(AttachmentKinds.icon(
						att.getMimeType()));
				row.setContentDescription(AttachmentKinds.describe(ctx,
						att.getMimeType(), att.getSizeBytes()));
				thumb.setVisibility(View.GONE);
				spinner.setVisibility(View.GONE);
				byte[] decrypted = n < thumbnails.size()
						? thumbnails.get(n) : null;
				if (att.getThumbnail() != null
						&& att.getMimeType().startsWith("video/")) {
					String ck = ChannelFeedActivity.thumbnailKey(
							p.getSeqNum(), att.getBlobHash());
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

		private void bindReactions(List<ChannelReaction> all) {
			if (all.isEmpty()) {
				reactionsView.setVisibility(View.GONE);
				return;
			}
			java.util.LinkedHashMap<String, Integer> counts =
					new java.util.LinkedHashMap<>();
			for (ChannelReaction r : all) {
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
				TouchTargets.drop(reactionsView);
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
				reactionsView.setOnClickListener(v -> tap.onTap(p, q));
				TouchTargets.atLeast48dp(reactionsView, null);
			} else {
				reactionsView.setOnClickListener(null);
				reactionsView.setClickable(false);
				TouchTargets.drop(reactionsView);
			}
		}

		static String formatRemaining(android.content.Context ctx,
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

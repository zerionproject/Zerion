package com.professor.zerion.android.conversation;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcelable;
import android.transition.Slide;
import android.transition.Transition;
import android.util.SparseArray;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.snackbar.Snackbar;

import org.briarproject.bramble.api.FeatureFlags;
import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.connection.ConnectionRegistry;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.lifecycle.IoExecutor;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.AttachmentRetriever;
import com.professor.zerion.android.attachment.media.VideoThumbnailExtractor;
import com.professor.zerion.android.conversation.ConversationVisitor.AttachmentCache;
import com.professor.zerion.android.conversation.ConversationVisitor.TextCache;
import com.professor.zerion.android.fragment.BaseFragment.BaseFragmentListener;
import com.professor.zerion.android.introduction.IntroductionActivity;
import com.professor.zerion.android.vault.ui.VaultActivity;
import com.professor.zerion.android.util.ActivityLaunchers.GetMultipleImagesAdvanced;
import com.professor.zerion.android.util.ActivityLaunchers.GetMultipleMediaAdvanced;
import com.professor.zerion.android.util.ActivityLaunchers.OpenMultipleImageDocumentsAdvanced;
import com.professor.zerion.android.conversation.voice.VoiceRecordingController;
import com.professor.zerion.android.util.UiUtils;
import com.professor.zerion.android.util.ZerionSnackbarBuilder;
import com.professor.zerion.android.view.ZerionRecyclerView;
import com.professor.zerion.android.view.ImagePreview;
import com.professor.zerion.android.view.TextAttachmentController;
import com.professor.zerion.android.view.TextAttachmentController.AttachmentListener;
import com.professor.zerion.android.view.TextInputView;
import com.professor.zerion.android.view.TextSendController;
import com.professor.zerion.android.view.TextSendController.SendState;
import com.professor.zerion.android.widget.LinkDialogFragment;
import com.professor.zerion.android.api.AndroidNotificationManager;
import java.util.concurrent.Executor;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.identity.AuthorInfo;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.conversation.ConversationMessageVisitor;
import org.briarproject.briar.api.conversation.ConversationRequest;
import org.briarproject.briar.api.conversation.ConversationResponse;
import org.briarproject.briar.api.conversation.event.ConversationMessageReceivedEvent;
import org.briarproject.briar.api.client.SessionId;
import org.briarproject.briar.api.introduction.IntroductionManager;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.event.ReactionReceivedEvent;
import org.briarproject.briar.api.messaging.PrivateMessageFormat;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.selection.Selection;
import androidx.recyclerview.selection.SelectionPredicates;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.selection.SelectionTracker.SelectionObserver;
import androidx.recyclerview.selection.StorageStrategy;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.imageview.ShapeableImageView;

import static android.view.Gravity.RIGHT;
import static androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation;
import static androidx.lifecycle.Lifecycle.State.STARTED;
import static androidx.recyclerview.widget.SortedList.INVALID_POSITION;
import static java.util.Collections.sort;
import static java.util.Objects.requireNonNull;
import static org.briarproject.bramble.util.StringUtils.fromHexString;
import static org.briarproject.bramble.util.StringUtils.join;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static com.professor.zerion.android.activity.RequestCodes.REQUEST_INTRODUCTION;
import static com.professor.zerion.android.conversation.ImageActivity.ATTACHMENTS;
import static com.professor.zerion.android.conversation.ImageActivity.ATTACHMENT_POSITION;
import static com.professor.zerion.android.conversation.ImageActivity.DATE;
import static com.professor.zerion.android.conversation.ImageActivity.ITEM_ID;
import static com.professor.zerion.android.conversation.ImageActivity.NAME;
import static com.professor.zerion.android.util.UiUtils.launchActivityToOpenFile;
import static com.professor.zerion.android.util.UiUtils.observeOnce;
import static com.professor.zerion.android.view.AuthorView.setAvatar;
import static org.briarproject.briar.api.messaging.MessagingConstants.MAX_ATTACHMENTS_PER_MESSAGE;
import static org.briarproject.briar.api.messaging.MessagingConstants.MAX_PRIVATE_MESSAGE_TEXT_LENGTH;
import static org.briarproject.briar.api.messaging.PrivateMessageFormat.TEXT_IMAGES_AUTO_DELETE;
import static org.briarproject.briar.api.messaging.PrivateMessageFormat.TEXT_ONLY;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
public class ConversationActivity extends ZerionActivity
		implements BaseFragmentListener, ConversationListener,
		TextCache, AttachmentCache, AttachmentListener, ActionMode.Callback,
		AttachmentPickerDialog.AttachmentPickerListener,
		com.professor.zerion.android.sticker.StickerPickerDialog.StickerPickerListener,
		VoiceRecordingController.VoiceRecordingHost {

	public static final String CONTACT_ID = "zerion.CONTACT_ID";

	private static final int TRANSITION_DURATION_MS = 500;
	private static final int ONBOARDING_DELAY_MS = 250;
	private static final java.util.regex.Pattern CALL_KEY_PATTERN =
		java.util.regex.Pattern.compile("^[A-Za-z0-9+/=]{40,}$");

	@Inject
	AndroidNotificationManager notificationManager;
	@Inject
	ConnectionRegistry connectionRegistry;
	@Inject
	ViewModelProvider.Factory viewModelFactory;
	@Inject
	FeatureFlags featureFlags;

	@Inject
	@DatabaseExecutor
	Executor dbExecutor;

	@Inject
	org.briarproject.briar.api.grouptr.GroupTrManager groupTrManager;

	@Inject
	IntroductionManager introductionManager;

	@Inject
	@IoExecutor
	Executor ioExecutor;

	@Inject
	MessagingManager messagingManager;

	@Inject
	org.briarproject.briar.api.conversation.ConversationManager conversationManager;

	@Inject
	@com.professor.zerion.android.AppModule.UiPrefs
	android.content.SharedPreferences uiPrefs;

	private final Map<MessageId, String> textCache = new ConcurrentHashMap<>();

	private final ActivityResultLauncher<String[]> docLauncher =
			registerForActivityResult(new OpenMultipleImageDocumentsAdvanced(),
					this::onImagesChosen);
	private final ActivityResultLauncher<String> contentLauncher =
			registerForActivityResult(new GetMultipleImagesAdvanced(),
					this::onImagesChosen);
	private final ActivityResultLauncher<String> mediaLauncher =
			registerForActivityResult(new GetMultipleMediaAdvanced(),
					this::onImagesChosen);

	private AttachmentRetriever attachmentRetriever;
	private ConversationViewModel viewModel;
	private ConversationVisitor visitor;
	private ConversationAdapter adapter;
	private Toolbar toolbar;
	private ShapeableImageView toolbarAvatar;
	private ImageView toolbarStatus;
	private TextView toolbarTitle;
	private TextView toolbarSubtitle;
	private ZerionRecyclerView list;
	private LinearLayoutManager layoutManager;
	private TextInputView textInputView;
	private TextSendController sendController;
	private SelectionTracker<String> tracker;
	@Nullable
	private Parcelable layoutManagerState;
	@Nullable
	private ActionMode actionMode;
	private final Set<String> voiceMemoRebuildsRequested = new HashSet<>();
	@Nullable
	private MessageId pendingSecretBurnId;
	@Nullable
	private com.google.android.material.floatingactionbutton.FloatingActionButton scrollToBottomButton;

	private TextView typingIndicator;
	private final TypingIndicatorManager typingManager =
			new TypingIndicatorManager();

	private final List<Integer> searchMatchPositions = new ArrayList<>();
	private int searchMatchIndex = -1;

	private volatile ContactId contactId;
	private volatile boolean contactVerified = false;

	@Override
	public void injectActivity(ActivityComponent component) {
		component.inject(this);
		viewModel = new ViewModelProvider(this, viewModelFactory)
				.get(ConversationViewModel.class);
	}

	@Override
	public void onCreate(@Nullable Bundle state) {
		@SuppressLint("RtlHardcoded")
		Transition slide = new Slide(RIGHT);
		slide.setDuration(TRANSITION_DURATION_MS);
		setSceneTransitionAnimation(slide, null, slide);
		super.onCreate(state);
		getWindow().setFlags(
				android.view.WindowManager.LayoutParams.FLAG_SECURE,
				android.view.WindowManager.LayoutParams.FLAG_SECURE);

		Intent i = getIntent();
		int id = i.getIntExtra(CONTACT_ID, -1);
		if (id == -1) throw new IllegalStateException();
		contactId = new ContactId(id);

		viewModel.setContactId(contactId);
		attachmentRetriever = viewModel.getAttachmentRetriever();

		voiceRecordingController = new VoiceRecordingController(this, dbExecutor);
		voiceRecordingController.initRecorder(this);
		getLifecycle().addObserver(voiceRecordingController);

		setContentView(R.layout.activity_conversation);

		toolbar = requireNonNull(setUpCustomToolbar(true));
		toolbarAvatar = toolbar.findViewById(R.id.contactAvatar);
		toolbarStatus = toolbar.findViewById(R.id.contactStatus);
		toolbarTitle = toolbar.findViewById(R.id.contactName);
		toolbarSubtitle = toolbar.findViewById(R.id.contactSubtitle);
		if (toolbarSubtitle != null) {
			toolbarSubtitle.setText(R.string.conversation_subtitle_encrypted);
		}
		View titleBlock = toolbar.findViewById(R.id.toolbarTitleBlock);
		if (titleBlock != null) {
			titleBlock.setOnClickListener(v -> {
				Intent intent = new Intent(this, ChatSettingsActivity.class);
				intent.putExtra(CONTACT_ID, contactId.getInt());
				startActivity(intent);
			});
		}

		viewModel.getContactItem().observe(this, contactItem -> {
			requireNonNull(contactItem);
			setAvatar(toolbarAvatar, contactItem);
			contactVerified = contactItem.getAuthorInfo().getStatus()
					== AuthorInfo.Status.VERIFIED;
			updateVerifyBanner();
		});
		viewModel.getContactDisplayName().observe(this, contactName -> {
			requireNonNull(contactName);
			toolbarTitle.setText(contactName);
		});
		viewModel.isContactDeleted().observe(this, deleted -> {
			requireNonNull(deleted);
			if (deleted) finish();
		});
		viewModel.getAddedPrivateMessage().observeEvent(this,
				this::onAddedPrivateMessage);
		viewModel.getSecretNoteAdded().observeEvent(this, pair -> {
			if (pair != null) {
				PrivateMessageHeader h = pair.getFirst();
				String text = pair.getSecond();
				textCache.put(h.getId(), text);
				ConversationSecretNoteItem item =
						new ConversationSecretNoteItem(h,
								viewModel.getContactDisplayName());
				item.setText(text);
				adapter.add(item);
				scrollToBottom();
			}
		});

		visitor = new ConversationVisitor(this, this, this,
				viewModel.getContactDisplayName(), viewModel, groupTrManager);
		adapter = new ConversationAdapter(this, this,
				attachmentRetriever.getAttachmentReader(), dbExecutor);
		list = findViewById(R.id.conversationView);
		layoutManager = new LinearLayoutManager(this);
		list.setLayoutManager(layoutManager);
		list.setAdapter(adapter);
		list.setEmptyImage(R.drawable.il_empty_state_contact_list);
		list.setEmptyText(getString(R.string.no_private_messages));
		list.setEmptyAction(getString(R.string.no_private_messages_action));
		ConversationScrollListener scrollListener =
				new ConversationScrollListener(adapter, viewModel);
		list.getRecyclerView().addOnScrollListener(scrollListener);

		SwipeToReplyCallback swipeCallback = new SwipeToReplyCallback(
				position -> {
					ConversationItem item = adapter.getItemAt(position);
					if (item != null) {
						textInputView.showReplyPreview(item);
						textInputView.showSoftKeyboard();
					}
				});
		new ItemTouchHelper(swipeCallback)
				.attachToRecyclerView(list.getRecyclerView());

		textInputView = findViewById(R.id.text_input_container);
		if (featureFlags.shouldEnableImageAttachments()) {
			ImagePreview imagePreview = findViewById(R.id.imagePreview);
			VideoThumbnailExtractor videoThumbnailExtractor =
					new VideoThumbnailExtractor(this);
			imagePreview.setVideoThumbnailExtractor(videoThumbnailExtractor, ioExecutor);
			sendController = new TextAttachmentController(textInputView,
					imagePreview, this, viewModel);
			observeOnce(viewModel.getPrivateMessageFormat(), this, format -> {
				if (format != TEXT_ONLY) {
					((TextAttachmentController) sendController)
							.setImagesSupported();
				}
			});
		} else {
			sendController = new TextSendController(textInputView, this, false);
		}
		textInputView.setSendController(sendController);
		textInputView.setMaxTextLength(MAX_PRIVATE_MESSAGE_TEXT_LENGTH);
		textInputView.setReady(false);
		textInputView.setOnKeyboardShownListener(this::scrollToBottom);

		setupCameraAndAttachmentButtons();

		scrollToBottomButton = findViewById(R.id.scrollToBottomButton);
		if (scrollToBottomButton != null) {
			scrollToBottomButton.setOnClickListener(v -> scrollToBottom());
			list.getRecyclerView().addOnScrollListener(new RecyclerView.OnScrollListener() {
				@Override
				public void onScrolled(RecyclerView recyclerView, int dx, int dy) {
					super.onScrolled(recyclerView, dx, dy);
					updateScrollToBottomButtonVisibility();
				}
			});
		}

		voiceRecordingController.bindViews(
				findViewById(R.id.voiceRecordingOverlay),
				findViewById(R.id.recording_time),
				findViewById(R.id.recording_pulse),
				findViewById(R.id.cancel_recording_button),
				findViewById(R.id.send_voice_button),
				textInputView);

		typingIndicator = findViewById(R.id.typingIndicator);
		setupTypingIndicator();
		viewModel.getAutoDeleteTimer().observe(this, timer ->
				sendController.setAutoDeleteTimer(timer));

		viewModel.getMessageTexts().observe(this, texts -> {
			if (texts != null) {
				textCache.putAll(texts);
			}
		});

		viewModel.getMessageHeaders().observe(this, this::onMessageHeadersLoaded);

		viewModel.getMessageTextLoaded().observeEvent(this, pair -> {
			if (pair != null) {
				displayMessageText(pair.getFirst(), pair.getSecond());
			}
		});

		viewModel.getVoiceMemoRebuilt().observeEvent(this, memoId -> {
			if (memoId != null) {
				voiceMemoRebuildsRequested.remove(memoId);
				refreshVoiceMemoAnchor(memoId);
			}
		});

		viewModel.getChatCleared().observeEvent(this, cleared -> {
			if (cleared != null && cleared) {
				textCache.clear();
				adapter.clear();
			}
		});

		viewModel.getMessagesDeleted().observeEvent(this, messageIds -> {
			if (messageIds != null) {
				for (MessageId msgId : messageIds) {
					textCache.remove(msgId);
				}
				adapter.incrementRevision();
				adapter.removeItems(messageIds);
			}
		});

		viewModel.getMessagesMarked().observeEvent(this, event -> {
			if (event != null) {
				onMessagesMarked(event.messageIds, event.sent, event.seen);
			}
		});

		viewModel.isContactConnected().observe(this, connected -> {
			if (connected != null) {
				updateConnectionStatusUI(connected);
			}
		});

		viewModel.getNewMessageReceived().observeEvent(this, header -> {
			if (header != null) {
				onNewConversationMessage(header);
				viewModel.loadLinkPreviews();
			}
		});

		viewModel.getClientVersionUpdated().observeEvent(this, clientId -> {
			if (clientId != null && clientId.equals(MessagingManager.CLIENT_ID)) {
				viewModel.recheckFeaturesAndOnboarding(contactId);
			}
		});

		viewModel.isContactTyping().observe(this, typing -> {
			boolean typingEnabled = uiPrefs.getBoolean(
					com.professor.zerion.android.settings.SecurityFragment
							.PREF_TYPING_INDICATORS, true);
			if (!typingEnabled) {
				typingManager.onTypingIndicatorReceived(false);
				return;
			}
			if (typing != null && typing) {
				typingManager.onTypingIndicatorReceived(true);
			} else {
				typingManager.onTypingIndicatorReceived(false);
			}
		});

		viewModel.getReactionsMap().observe(this, reactions -> {
			if (reactions != null) {
				applyReactionsToItems(reactions);
			}
		});

		viewModel.getReactionReceived().observeEvent(this, event -> {
			if (event != null) {

				viewModel.loadReactions();
			}
		});

		viewModel.getLinkPreviewsMap().observe(this, previews -> {
			if (previews != null) {
				applyLinkPreviewsToItems(previews);
			}
		});
	}

	private void setupCameraAndAttachmentButtons() {
		if (sendController instanceof TextAttachmentController) {
			TextAttachmentController attachmentController = (TextAttachmentController) sendController;

			attachmentController.setOnAttachmentClickListener(v -> {
				if (!requireVerifiedToSendMedia()) return;
				AttachmentPickerDialog dialog = AttachmentPickerDialog.newInstance();
				dialog.show(getSupportFragmentManager(), "attachment_picker");
			});

			attachmentController.setOnVoiceClickListener(v -> {
				if (!requireVerifiedToSendMedia()) return;
				if (checkVoicePermission()) {
					startVoiceRecording();
				} else {
					requestVoicePermission();
				}
			});
		}

		android.widget.ImageButton secretNoteButton =
				textInputView.findViewById(R.id.secretNoteButton);
		if (secretNoteButton != null) {
			secretNoteButton.setOnClickListener(v -> {
				String text = textInputView.getInputText();
				if (text == null || text.isEmpty()) {
					Toast.makeText(this, R.string.secret_note_hint,
							Toast.LENGTH_SHORT).show();
					return;
				}
				showSecretNoteTimerPicker(text);
			});
		}

		TextView banner = findViewById(R.id.verifyMediaBanner);
		if (banner != null) {
			banner.setOnClickListener(v -> openContactInfo());
		}
	}

	private boolean requireVerifiedToSendMedia() {
		if (contactVerified) return true;
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.verify_to_send_media_title)
				.setMessage(R.string.verify_to_send_media_message)
				.setPositiveButton(R.string.verify_to_send_media_open_info,
						(d, w) -> openContactInfo())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
		return false;
	}

	private void openContactInfo() {
		Intent intent = new Intent(this, ChatSettingsActivity.class);
		intent.putExtra(CONTACT_ID, contactId.getInt());
		startActivity(intent);
	}

	@UiThread
	private void updateVerifyBanner() {
		TextView banner = findViewById(R.id.verifyMediaBanner);
		if (banner == null) return;
		banner.setVisibility(contactVerified ? View.GONE : View.VISIBLE);
	}

	private void setupTypingIndicator() {
		typingManager.setListener(new TypingIndicatorManager.TypingListener() {
			@Override
			public void onSendTypingIndicator(boolean isTyping) {
				if (uiPrefs.getBoolean(
						com.professor.zerion.android.settings.SecurityFragment
								.PREF_TYPING_INDICATORS, true)) {
					viewModel.sendTypingIndicator(isTyping);
				}
			}

			@Override
			public void onContactTypingChanged(boolean isTyping) {
				runOnUiThread(() -> {
					if (typingIndicator != null) {
						if (isTyping) {
							typingIndicator.setText(R.string.typing_indicator);
							typingIndicator.setVisibility(View.VISIBLE);
						} else {
							typingIndicator.setVisibility(View.GONE);
						}
					}
				});
			}
		});

		android.widget.EditText editText = findViewById(R.id.input_text);
		if (editText != null) {
			editText.addTextChangedListener(new android.text.TextWatcher() {
				@Override
				public void beforeTextChanged(CharSequence s, int start,
						int count, int after) {}

				@Override
				public void onTextChanged(CharSequence s, int start,
						int before, int count) {
					if (count > 0) {
						typingManager.onTextChanged();
					}
				}

				@Override
				public void afterTextChanged(android.text.Editable s) {}
			});
		}
	}

	private static final int REQUEST_CAMERA_PERMISSION = 1002;
	private static final int REQUEST_TAKE_PHOTO = 1003;
	private static final int REQUEST_VAULT_GALLERY = 1004;
	private static final int REQUEST_VAULT_DOCUMENTS = 1005;
	private static final int REQUEST_RECORD_AUDIO = 1006;
	private static final int REQUEST_VOICE_CALL = 1007;
	private static final int REQUEST_RECORD_VIDEO = 1008;
	private static final int REQUEST_VIDEO_CAMERA_PERMISSION = 1009;
	private static final int REQUEST_VIDEO_CALL = 1010;
	private Uri photoUri;
	private Uri videoUri;
	private java.io.File recordedVideoFile;

	private VoiceRecordingController voiceRecordingController;

	private void launchCamera() {
		Intent takePictureIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
		try {
			java.io.File photoFile = new java.io.File(new java.io.File(getFilesDir(), "camera"),
					"temp_" + System.currentTimeMillis() + ".jpg");
			if (!photoFile.getParentFile().exists()) {
				photoFile.getParentFile().mkdirs();
			}
			photoUri = androidx.core.content.FileProvider.getUriForFile(this,
					"com.professor.zerion.fileprovider", photoFile);
			takePictureIntent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri);
			takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
			startActivityForResult(takePictureIntent, REQUEST_TAKE_PHOTO);
		} catch (android.content.ActivityNotFoundException e) {
			Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			handleSecurityException(e);
		}
	}

	private void launchVideoRecorder() {
		Intent takeVideoIntent = new Intent(android.provider.MediaStore.ACTION_VIDEO_CAPTURE);
		try {
			recordedVideoFile = new java.io.File(new java.io.File(getFilesDir(), "camera"),
					"temp_" + System.currentTimeMillis() + ".mp4");
			if (!recordedVideoFile.getParentFile().exists()) {
				recordedVideoFile.getParentFile().mkdirs();
			}
			videoUri = androidx.core.content.FileProvider.getUriForFile(this,
					"com.professor.zerion.fileprovider", recordedVideoFile);
			takeVideoIntent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, videoUri);
			takeVideoIntent.putExtra(android.provider.MediaStore.EXTRA_VIDEO_QUALITY, 0);
			takeVideoIntent.putExtra(android.provider.MediaStore.EXTRA_DURATION_LIMIT, 60);
			takeVideoIntent.putExtra(android.provider.MediaStore.EXTRA_SIZE_LIMIT, 10L * 1024 * 1024);
			takeVideoIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
			startActivityForResult(takeVideoIntent, REQUEST_RECORD_VIDEO);
		} catch (android.content.ActivityNotFoundException e) {
			Toast.makeText(this, R.string.no_camera_app, Toast.LENGTH_SHORT).show();
		} catch (Exception e) {
			handleSecurityException(e);
		}
	}

	private void processRecordedVideo() {
		ioExecutor.execute(() -> {
			try {
				if (recordedVideoFile == null || !recordedVideoFile.exists()) {
					runOnUiThread(() -> Toast.makeText(this,
							R.string.video_attach_error, Toast.LENGTH_SHORT).show());
					return;
				}

				java.io.File cameraDir = new java.io.File(getFilesDir(), "camera");
				if (!cameraDir.exists()) {
					cameraDir.mkdirs();
				}
				java.io.File finalizedFile = new java.io.File(cameraDir,
						"final_" + System.currentTimeMillis() + ".mp4");

				try (java.io.FileInputStream fis = new java.io.FileInputStream(recordedVideoFile);
					 java.io.FileOutputStream fos = new java.io.FileOutputStream(finalizedFile)) {
					byte[] buffer = new byte[8192];
					int bytesRead;
					while ((bytesRead = fis.read(buffer)) != -1) {
						fos.write(buffer, 0, bytesRead);
					}
					fos.flush();
					fos.getFD().sync();
				}

				if (!finalizedFile.exists() || finalizedFile.length() == 0) {
					runOnUiThread(() -> Toast.makeText(this,
							R.string.video_attach_error, Toast.LENGTH_SHORT).show());
					return;
				}

				Uri finalizedUri = androidx.core.content.FileProvider.getUriForFile(this,
						"com.professor.zerion.fileprovider", finalizedFile);

				runOnUiThread(() -> {
					if (sendController instanceof TextAttachmentController) {
						List<Uri> uris = new ArrayList<>();
						uris.add(finalizedUri);
						((TextAttachmentController) sendController).onImageReceived(uris);
					}
				});

				if (recordedVideoFile.exists()) {
					recordedVideoFile.delete();
				}
				recordedVideoFile = null;

			} catch (Exception e) {
				runOnUiThread(() -> Toast.makeText(this,
						R.string.video_attach_error, Toast.LENGTH_SHORT).show());
			}
		});
	}

	private void scrollToBottom() {
		int items = adapter.getItemCount();
		if (items > 0) list.scrollToPosition(items - 1);
		if (scrollToBottomButton != null) {
			scrollToBottomButton.hide();
		}
	}

	private void updateScrollToBottomButtonVisibility() {
		if (scrollToBottomButton == null || layoutManager == null) return;

		int lastVisiblePosition = layoutManager.findLastCompletelyVisibleItemPosition();
		int itemCount = adapter.getItemCount();

		if (itemCount > 0 && lastVisiblePosition < itemCount - 1) {
			scrollToBottomButton.show();
		} else {
			scrollToBottomButton.hide();
		}
	}

	private boolean checkVoicePermission() {
		boolean hasRecordAudio = ContextCompat.checkSelfPermission(this,
				android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;

		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			boolean hasForegroundMic = ContextCompat.checkSelfPermission(this,
					android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE) == PackageManager.PERMISSION_GRANTED;
			return hasRecordAudio && hasForegroundMic;
		}

		return hasRecordAudio;
	}

	private void requestVoicePermission() {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			ActivityCompat.requestPermissions(this,
					new String[]{
							android.Manifest.permission.RECORD_AUDIO,
							android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
					},
					REQUEST_RECORD_AUDIO);
		} else {
			ActivityCompat.requestPermissions(this,
					new String[]{android.Manifest.permission.RECORD_AUDIO},
					REQUEST_RECORD_AUDIO);
		}
	}

	private void startVoiceCall() {
		if (!checkVoicePermission()) {
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				ActivityCompat.requestPermissions(this,
						new String[]{
								android.Manifest.permission.RECORD_AUDIO,
								android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
						},
						REQUEST_VOICE_CALL);
			} else {
				ActivityCompat.requestPermissions(this,
						new String[]{android.Manifest.permission.RECORD_AUDIO},
						REQUEST_VOICE_CALL);
			}
			return;
		}

		Intent intent = new Intent(this,
				com.professor.zerion.android.conversation.voice.VoiceCallActivity.class);
		intent.putExtra(com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_CONTACT_ID,
				contactId.getInt());
		intent.putExtra(com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_IS_INCOMING,
				false);
		startActivity(intent);
	}

	private void startVideoCall() {

		boolean hasAudio = ContextCompat.checkSelfPermission(this,
				android.Manifest.permission.RECORD_AUDIO)
				== android.content.pm.PackageManager.PERMISSION_GRANTED;
		boolean hasCamera = ContextCompat.checkSelfPermission(this,
				android.Manifest.permission.CAMERA)
				== android.content.pm.PackageManager.PERMISSION_GRANTED;

		if (!hasAudio || !hasCamera) {
			java.util.List<String> perms = new java.util.ArrayList<>();
			if (!hasAudio) {
				perms.add(android.Manifest.permission.RECORD_AUDIO);
			}
			if (!hasCamera) {
				perms.add(android.Manifest.permission.CAMERA);
			}
			if (android.os.Build.VERSION.SDK_INT >=
					android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				if (!hasAudio) {
					perms.add(android.Manifest.permission
							.FOREGROUND_SERVICE_MICROPHONE);
				}
				perms.add(android.Manifest.permission
						.FOREGROUND_SERVICE_CAMERA);
			}
			ActivityCompat.requestPermissions(this,
					perms.toArray(new String[0]), REQUEST_VIDEO_CALL);
			return;
		}

		Intent intent = new Intent(this,
				com.professor.zerion.android.conversation.voice
						.VoiceCallActivity.class);
		intent.putExtra(
				com.professor.zerion.android.conversation.voice
						.VoiceCallActivity.EXTRA_CONTACT_ID,
				contactId.getInt());
		intent.putExtra(
				com.professor.zerion.android.conversation.voice
						.VoiceCallActivity.EXTRA_IS_INCOMING, false);
		intent.putExtra("auto_video", true);
		startActivity(intent);
	}

	private void handleIncomingVoiceCallSignal(String signalMessage) {
		com.professor.zerion.android.conversation.voice.VoiceCallSignal signal =
				com.professor.zerion.android.conversation.voice.VoiceCallSignal.fromWireFormat(signalMessage);

		if (signal == null) {
			return;
		}

		switch (signal.getType()) {
			case CALL_OFFER:
				handleCallOffer(signal);
				break;

			case CALL_ANSWER:
			case CALL_REJECT:
			case CALL_END:
				forwardSignalToService(signalMessage);
				break;

			default:
				break;
		}
	}

	private void handleCallOffer(com.professor.zerion.android.conversation.voice.VoiceCallSignal signal) {
		String remoteCallId = signal.getCallId();
		String voiceCallKey = signal.getVoiceCallKey();
		String remoteEphemeralHex = signal.getEphemeralSecret();

		if (!checkVoicePermission()) {
			if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
				ActivityCompat.requestPermissions(this,
						new String[]{
								android.Manifest.permission.RECORD_AUDIO,
								android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE
						},
						REQUEST_VOICE_CALL);
			} else {
				ActivityCompat.requestPermissions(this,
						new String[]{android.Manifest.permission.RECORD_AUDIO},
						REQUEST_VOICE_CALL);
			}
			return;
		}

		Intent intent = new Intent(this,
				com.professor.zerion.android.conversation.voice.VoiceCallActivity.class);
		intent.putExtra(com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_CONTACT_ID,
				contactId.getInt());
		intent.putExtra(com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_IS_INCOMING,
				true);
		intent.putExtra(com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_CALL_ID,
				remoteCallId);
		if (voiceCallKey != null) {
			try {
				org.briarproject.bramble.api.crypto.SecretKey key =
						new org.briarproject.bramble.api.crypto.SecretKey(
								org.briarproject.bramble.util.StringUtils.fromHexString(voiceCallKey));
				com.professor.zerion.android.conversation.voice.VoiceCallKeyHolder.setKey(key);
			} catch (Exception ignored) {
			}
		}
		if (remoteEphemeralHex != null) {
			try {
				com.professor.zerion.android.conversation.voice.VoiceCallKeyHolder
						.setRemoteEphemeral(fromHexString(remoteEphemeralHex));
			} catch (Exception ignored) {
			}
		}
		intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
		startActivity(intent);
	}

	private void forwardSignalToService(String signalMessage) {
		Intent serviceIntent = new Intent(this,
				com.professor.zerion.android.conversation.voice.VoiceCallService.class);
		serviceIntent.setAction("com.professor.zerion.VOICE_CALL_SIGNALING");
		serviceIntent.putExtra("signaling_message", signalMessage);
		try {
			startService(serviceIntent);
		} catch (Exception e) {
			handleSecurityException(e);
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode,
			String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == REQUEST_CAMERA_PERMISSION) {
			if (grantResults.length > 0 &&
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				launchCamera();
			}
		} else if (requestCode == REQUEST_VIDEO_CAMERA_PERMISSION) {
			if (grantResults.length > 0 &&
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				launchVideoRecorder();
			}
		} else if (requestCode == REQUEST_RECORD_AUDIO) {
			if (grantResults.length > 0 &&
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				if (checkVoicePermission()) {
					startVoiceRecording();
				}
			}
		} else if (requestCode == REQUEST_VOICE_CALL) {
			if (grantResults.length > 0 &&
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				if (checkVoicePermission()) {
					startVoiceCall();
				}
			}
		} else if (requestCode == REQUEST_VIDEO_CALL) {
			boolean allGranted = true;
			for (int result : grantResults) {
				if (result != PackageManager.PERMISSION_GRANTED) {
					allGranted = false;
					break;
				}
			}
			if (allGranted) {
				startVideoCall();
			}
		}
	}

	@Override
	protected void onActivityResult(int request, int result,
			@Nullable Intent data) {
		super.onActivityResult(request, result, data);

		if (request == REQUEST_INTRODUCTION && result == RESULT_OK) {
			new ZerionSnackbarBuilder()
					.make(list, R.string.introduction_sent,
							Snackbar.LENGTH_SHORT)
					.show();
		} else if (request == REQUEST_TAKE_PHOTO && result == RESULT_OK) {
			if (photoUri != null && sendController instanceof TextAttachmentController) {
				List<Uri> uris = new ArrayList<>();
				uris.add(photoUri);
				((TextAttachmentController) sendController).onImageReceived(uris);
			}
		} else if (request == REQUEST_RECORD_VIDEO && result == RESULT_OK) {
			if (recordedVideoFile != null && sendController instanceof TextAttachmentController) {
				processRecordedVideo();
			}
		} else if ((request == REQUEST_VAULT_GALLERY || request == REQUEST_VAULT_DOCUMENTS)
				&& result == RESULT_OK && data != null) {
			ArrayList<Uri> uris = data.getParcelableArrayListExtra(VaultActivity.RESULT_SELECTED_URIS);
			if (uris != null && !uris.isEmpty() && sendController instanceof TextAttachmentController) {
				((TextAttachmentController) sendController).onImageReceived(uris);
			}
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		notificationManager.blockContactNotification(contactId);
		notificationManager.clearContactNotification(contactId);
		displayContactOnlineStatus();
		list.startPeriodicUpdate();
		loadMessages();
		voiceCallsEnabled = uiPrefs.getBoolean(
				com.professor.zerion.android.settings.SecurityFragment
						.PREF_VOICE_CALLS_ENABLED, true);
		videoCallsEnabled = uiPrefs.getBoolean(
				com.professor.zerion.android.settings.SecurityFragment
						.PREF_VIDEO_CALLS_ENABLED, false);
		invalidateOptionsMenu();
	}

	@Override
	public void onStop() {
		super.onStop();

		try {
			Intent serviceIntent = new Intent(this,
					com.professor.zerion.android.conversation.voice.VoiceCallService.class);
			stopService(serviceIntent);
		} catch (Exception e) {
		}

		notificationManager.unblockContactNotification(contactId);
		list.stopPeriodicUpdate();

		if (pendingSecretBurnId != null) {
			viewModel.deleteMessages(
					java.util.Collections.singleton(pendingSecretBurnId));
			pendingSecretBurnId = null;
		}
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);
		if (layoutManager != null) {
			layoutManagerState = layoutManager.onSaveInstanceState();
			outState.putParcelable("layoutManager", layoutManagerState);
		}
	}

	@Override
	protected void onRestoreInstanceState(Bundle savedInstanceState) {
		super.onRestoreInstanceState(savedInstanceState);
		layoutManagerState = savedInstanceState.getParcelable("layoutManager");
	}

	@Override
	protected void onDestroy() {
		super.onDestroy();
		typingManager.destroy();
		shredCameraDir();
	}

	private void shredCameraDir() {
		java.io.File dir = new java.io.File(getFilesDir(), "camera");
		java.io.File[] kids = dir.listFiles();
		if (kids == null) return;
		for (java.io.File f : kids) {
			try {
				long len = f.length();
				if (len > 0) {
					try (java.io.RandomAccessFile raf =
								new java.io.RandomAccessFile(f, "rw")) {
						byte[] zeros = new byte[(int) Math.min(len,
								64L * 1024L)];
						long remaining = len;
						raf.seek(0);
						while (remaining > 0) {
							int n = (int) Math.min(zeros.length, remaining);
							raf.write(zeros, 0, n);
							remaining -= n;
						}
						raf.getFD().sync();
					}
				}
			} catch (java.io.IOException ignored) {
			}
			f.delete();
		}
	}

	private boolean voiceCallsEnabled = true;
	private boolean videoCallsEnabled = false;

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.conversation_actions, menu);

		MenuItem searchItem = menu.findItem(R.id.action_search);
		SearchView searchView = (SearchView) searchItem.getActionView();
		if (searchView != null) {
			searchView.setQueryHint(getString(R.string.search_hint));
			searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
				@Override
				public boolean onQueryTextSubmit(String query) {
					navigateSearchNext();
					return true;
				}

				@Override
				public boolean onQueryTextChange(String newText) {
					performMessageSearch(newText);
					return true;
				}
			});
			searchItem.setOnActionExpandListener(
					new MenuItem.OnActionExpandListener() {
				@Override
				public boolean onMenuItemActionExpand(MenuItem item) {
					return true;
				}

				@Override
				public boolean onMenuItemActionCollapse(MenuItem item) {
					clearSearchHighlight();
					return true;
				}
			});
		}

		boolean voiceEnabled = voiceCallsEnabled;
		boolean videoEnabled = videoCallsEnabled;
		MenuItem voiceCallItem = menu.findItem(R.id.action_voice_call);
		MenuItem videoCallItem = menu.findItem(R.id.action_video_call);
		if (voiceCallItem != null) voiceCallItem.setVisible(voiceEnabled);
		if (videoCallItem != null) videoCallItem.setVisible(videoEnabled);

		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == android.R.id.home) {
			onBackPressed();
			return true;
		} else if (itemId == R.id.action_voice_call) {
			if (!uiPrefs.getBoolean(
					com.professor.zerion.android.settings.SecurityFragment
							.PREF_VOICE_CALLS_ENABLED, true)) {
				return true;
			}
			startVoiceCall();
			return true;
		} else if (itemId == R.id.action_video_call) {
			if (!uiPrefs.getBoolean(
					com.professor.zerion.android.settings.SecurityFragment
							.PREF_VIDEO_CALLS_ENABLED, false)) {
				return true;
			}
			startVideoCall();
			return true;
		} else if (itemId == R.id.action_all_media) {
			Intent intent = new Intent(this, AllMediaActivity.class);
			intent.putExtra(CONTACT_ID, contactId.getInt());
			startActivity(intent);
			return true;
		} else if (itemId == R.id.action_chat_settings) {
			Intent intent = new Intent(this, ChatSettingsActivity.class);
			intent.putExtra(CONTACT_ID, contactId.getInt());
			startActivity(intent);
			return true;
		} else if (itemId == R.id.action_disappearing_messages) {
			showDisappearingMessagesDialog();
			return true;
		} else if (itemId == R.id.action_clear_chat) {
			askToClearChat();
			return true;
		} else if (itemId == R.id.action_social_remove_person) {
			askToRemoveContact();
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	@Override
	public boolean onCreateActionMode(ActionMode mode, Menu menu) {
		MenuInflater inflater = mode.getMenuInflater();
		inflater.inflate(R.menu.conversation_message_actions, menu);
		return true;
	}

	@Override
	public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
		MenuItem reactItem = menu.findItem(R.id.action_react);
		if (reactItem != null) {
			boolean showReact = false;
			if (tracker.getSelection().size() == 1) {
				String key = tracker.getSelection().iterator().next();
				int pos = adapter.getPositionOfKey(key);
				if (pos >= 0) {
					ConversationItem item = adapter.getItemAt(pos);
					showReact = item instanceof ConversationMessageItem;
				}
			}
			reactItem.setVisible(showReact);
		}
		return true;
	}

	@Override
	public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
		if (item.getItemId() == R.id.action_react) {
			reactToSelectedMessage();
			return true;
		} else if (item.getItemId() == R.id.action_copy) {
			copySelectedMessages();
			return true;
		} else if (item.getItemId() == R.id.action_forward) {
			forwardSelectedMessages();
			return true;
		} else if (item.getItemId() == R.id.action_delete) {
			deleteSelectedMessages();
			return true;
		}
		return false;
	}

	@Override
	public void onDestroyActionMode(ActionMode mode) {
		actionMode = null;
	}

	@Override
	public void onLinkClick(String url) {
		LinkDialogFragment f = LinkDialogFragment.newInstance(url);
		f.show(getSupportFragmentManager(), f.getUniqueTag());
	}

	private void addSelectionTracker() {
		RecyclerView recyclerView = list.getRecyclerView();
		if (recyclerView.getAdapter() != adapter)
			throw new IllegalStateException();

		tracker = new SelectionTracker.Builder<>(
				"conversationSelection",
				recyclerView,
				new ConversationItemKeyProvider(adapter),
				new ConversationItemDetailsLookup(recyclerView),
				StorageStrategy.createStringStorage()
		).withSelectionPredicate(
				SelectionPredicates.createSelectAnything()
		).build();

		SelectionObserver<String> observer = new SelectionObserver<String>() {
			@Override
			public void onItemStateChanged(String key, boolean selected) {
				if (selected && actionMode == null) {
					actionMode = startActionMode(ConversationActivity.this);
					updateActionModeTitle();
				} else if (actionMode != null) {
					if (selected || tracker.hasSelection()) {
						updateActionModeTitle();
					} else {
						actionMode.finish();
					}
				}
			}
		};
		tracker.addObserver(observer);
		adapter.setSelectionTracker(tracker);
	}

	private void updateActionModeTitle() {
		if (actionMode == null) throw new IllegalStateException();
		String title = String.valueOf(tracker.getSelection().size());
		actionMode.setTitle(title);
		actionMode.invalidate();
	}

	private Collection<MessageId> getSelection() {
		Selection<String> selection = tracker.getSelection();
		List<MessageId> messages = new ArrayList<>(selection.size());
		for (String str : selection) {
			try {
				MessageId id = new MessageId(fromHexString(str));
				messages.add(id);
			} catch (FormatException e) {
				handleSecurityException(e);
			}
		}
		return messages;
	}

	@UiThread
	private void displayContactOnlineStatus() {
		viewModel.checkConnectionStatus(connectionRegistry);
	}

	@UiThread
	private void updateConnectionStatusUI(boolean connected) {
		toolbarStatus.setImageDrawable(null);
		if (connected) {
			toolbarStatus.setBackgroundResource(R.drawable.bg_presence_online);
			toolbarStatus.setContentDescription(getString(R.string.online));
			if (toolbarSubtitle != null) {
				toolbarSubtitle.setText(
						R.string.conversation_subtitle_online);
			}
		} else {
			toolbarStatus.setBackgroundResource(R.drawable.bg_presence_offline);
			toolbarStatus.setContentDescription(getString(R.string.offline));
			if (toolbarSubtitle != null) {
				toolbarSubtitle.setText(
						R.string.conversation_subtitle_encrypted);
			}
		}
	}

	private void loadMessages() {
		viewModel.loadMessageHeaders();
	}

	private void onMessageHeadersLoaded(Collection<ConversationMessageHeader> headers) {
		if (headers == null) return;
		List<ConversationMessageHeader> sorted = new ArrayList<>(headers);
		sort(sorted, (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
		displayMessages(sorted);

		viewModel.loadReactions();
		viewModel.loadLinkPreviews();
	}

	private void displayMessages(Collection<ConversationMessageHeader> headers) {
		runOnUiThreadUnlessDestroyed(() -> {
			adapter.incrementRevision();
			textInputView.setReady(true);
			if (featureFlags.shouldEnableImageAttachments()) {
				viewModel.showImageOnboarding().observeEvent(this,
						show -> { if (show) showImageOnboarding(); });
			}
			List<ConversationItem> items = createItems(headers);
			adapter.replaceAll(items);
			list.showData();
			if (layoutManagerState == null) {
				scrollToBottom();
			} else {
				layoutManager.onRestoreInstanceState(layoutManagerState);
			}
		});
	}

	private List<ConversationItem> createItems(
			Collection<ConversationMessageHeader> headers) {
		List<ConversationItem> items = new ArrayList<>(headers.size());
		for (ConversationMessageHeader h : headers) {
			ConversationItem item = h.accept(visitor);
			if (item != null) {
				items.add(item);
			}
		}
		return items;
	}

	private void loadMessageText(MessageId m) {
		viewModel.loadMessageText(m);
	}

	private void displayMessageText(MessageId m, String text) {
		runOnUiThreadUnlessDestroyed(() -> {
			textCache.put(m, text);
			com.professor.zerion.android.conversation.voice.VoiceMessageChunkFormat.Part part =
					com.professor.zerion.android.conversation.voice.VoiceMessageChunkFormat
							.parse(text);
			if (part != null) {
				viewModel.feedVoicePart(text);
				voiceMemoRebuildsRequested.remove(part.memoId);
				if (part.seq > 0) {
					Pair<Integer, ConversationMessageItem> partItem =
							adapter.getMessageItem(m);
					if (partItem != null && !partItem.getSecond().isRead()) {
						viewModel.markMessageRead(
								partItem.getSecond().getGroupId(), m);
						partItem.getSecond().markRead();
					}
				}
				if (viewModel.getReassembledVoiceMessage(part.memoId) != null) {
					refreshVoiceMemoAnchor(part.memoId);
				}
			}
			if (ConversationSecretNoteItem.isSecretNoteText(text)) {
				Pair<Integer, ConversationMessageItem> pair =
						adapter.getMessageItem(m);
				if (pair != null) {
					ConversationMessageItem old = pair.getSecond();
					ConversationSecretNoteItem secretItem =
							new ConversationSecretNoteItem(
									old.getHeader(), viewModel.getContactDisplayName());
					secretItem.setText(text);
					boolean scroll = shouldScrollWhenUpdatingMessage();
					adapter.remove(old);
					adapter.add(secretItem);
					if (scroll) scrollToBottom();
				}
				return;
			}
			Pair<Integer, ConversationMessageItem> pair =
					adapter.getMessageItem(m);
			if (pair != null) {
				boolean scroll = shouldScrollWhenUpdatingMessage();
				pair.getSecond().setText(text);
				adapter.notifyItemChanged(pair.getFirst());
				if (scroll) scrollToBottom();
			}
		});
	}

	private boolean shouldScrollWhenUpdatingMessage() {
		return getLifecycle().getCurrentState().isAtLeast(STARTED)
				&& adapter.isScrolledToBottom(layoutManager);
	}

	@UiThread
	private void refreshVoiceMemoAnchor(String memoId) {
		for (int i = 0; i < adapter.getItemCount(); i++) {
			ConversationItem item = adapter.getItemAt(i);
			if (!(item instanceof ConversationMessageItem)) continue;
			String t = ((ConversationMessageItem) item).getText();
			com.professor.zerion.android.conversation.voice.VoiceMessageChunkFormat.Part anchor =
					com.professor.zerion.android.conversation.voice.VoiceMessageChunkFormat
							.parse(t);
			if (anchor != null && anchor.seq == 0 &&
					anchor.memoId.equals(memoId)) {
				adapter.notifyItemChanged(i);
				return;
			}
		}
	}

	@Override
	@Nullable
	public String getReassembledVoiceMessage(String memoId) {
		String reassembled = viewModel.getReassembledVoiceMessage(memoId);
		if (reassembled == null && !viewModel.isVoiceMemoFailed(memoId)
				&& voiceMemoRebuildsRequested.add(memoId)) {
			viewModel.rebuildVoiceMemo(memoId);
		}
		return reassembled;
	}

	@Override
	public boolean isVoiceMemoFailed(String memoId) {
		return viewModel.isVoiceMemoFailed(memoId);
	}

	@UiThread
	private void updateMessageAttachment(MessageId m, AttachmentItem item) {
		Pair<Integer, ConversationMessageItem> pair = adapter.getMessageItem(m);
		if (pair != null && pair.getSecond().updateAttachments(item)) {
			boolean scroll = shouldScrollWhenUpdatingMessage();
			adapter.notifyItemChanged(pair.getFirst());
			if (scroll) scrollToBottom();
		}
	}

	@UiThread
	private void addConversationItem(ConversationItem item) {
		adapter.incrementRevision();
		adapter.add(item);
		if (getLifecycle().getCurrentState().isAtLeast(STARTED))
			scrollToBottom();
	}

	@UiThread
	private void onNewConversationMessage(ConversationMessageHeader h) {
		if (h instanceof ConversationRequest ||
				h instanceof ConversationResponse) {
			observeOnce(viewModel.getContactDisplayName(), this,
					name -> addConversationItem(h.accept(visitor)));
		} else {
			addConversationItem(h.accept(visitor));
		}
	}

	@UiThread
	private void onMessagesMarked(Collection<MessageId> messageIds, boolean sent,
			boolean seen) {
		adapter.incrementRevision();
		Set<MessageId> messages = new HashSet<>(messageIds);
		List<MessageId> secretNotesToDelete = new ArrayList<>();
		SparseArray<ConversationItem> list = adapter.getOutgoingMessages();
		for (int i = 0; i < list.size(); i++) {
			ConversationItem item = list.valueAt(i);
			if (messages.contains(item.getId())) {
				item.setSent(sent);
				item.setSeen(seen);
				adapter.notifyItemChanged(list.keyAt(i));
				if (seen && item instanceof ConversationSecretNoteItem) {
					secretNotesToDelete.add(item.getId());
				}
			}
		}
		if (!secretNotesToDelete.isEmpty()) {
			viewModel.deleteMessages(secretNotesToDelete);
		}
	}

	@Override
	public LiveData<SendState> onSendClick(@Nullable String text,
			List<AttachmentHeader> headers, long expectedAutoDeleteTimer) {
		typingManager.onMessageSent();
		ConversationItem replyToItem = textInputView.getReplyingToItem();
		LiveData<SendState> result = viewModel.sendMessage(text, headers, expectedAutoDeleteTimer, replyToItem);
		textInputView.hideReplyPreview();
		return result;
	}

	@Override
	public void onAttachImageClicked() {
		launchActivityToOpenFile(this, docLauncher, contentLauncher, "image/*");
	}

	@Override
	public void onDisappearingMessagesTimerChanged(long timer) {
		viewModel.setAutoDeleteTimer(timer);
	}

	@Override
	public void onTooManyAttachments() {
		new ZerionSnackbarBuilder()
				.make(list, "Maximum " + MAX_ATTACHMENTS_PER_MESSAGE + " attachments allowed",
						Snackbar.LENGTH_SHORT)
				.show();
	}

	@Override
	@Nullable
	public String getText(MessageId m) {
		String text = textCache.get(m);
		if (text == null) loadMessageText(m);
		return text;
	}

	@Override
	public List<AttachmentItem> getAttachmentItems(PrivateMessageHeader h) {
		List<LiveData<AttachmentItem>> liveDataList =
				attachmentRetriever.getAttachmentItems(h);
		List<AttachmentItem> items = new ArrayList<>(liveDataList.size());
		for (LiveData<AttachmentItem> liveData : liveDataList) {
			liveData.removeObservers(this);
			liveData.observe(this, new AttachmentObserver(h.getId(), liveData));
			items.add(requireNonNull(liveData.getValue()));
		}
		return items;
	}

	private class AttachmentObserver implements Observer<AttachmentItem> {
		private final MessageId conversationMessageId;
		private final LiveData<AttachmentItem> liveData;

		private AttachmentObserver(MessageId conversationMessageId,
				LiveData<AttachmentItem> liveData) {
			this.conversationMessageId = conversationMessageId;
			this.liveData = liveData;
		}

		@Override
		public void onChanged(AttachmentItem attachmentItem) {
			updateMessageAttachment(conversationMessageId, attachmentItem);
			if (attachmentItem.getState().isFinal())
				liveData.removeObserver(this);
		}
	}

	@Override
	public void onCameraSelected() {
		if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
				!= PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this,
					new String[]{android.Manifest.permission.CAMERA},
					REQUEST_CAMERA_PERMISSION);
		} else {
			launchCamera();
		}
	}

	@Override
	public void onVideoSelected() {
		PrivateMessageFormat format = viewModel.getPrivateMessageFormat().getValue();
		if (format == null || !format.supportsChunkedAttachments()) {
			Toast.makeText(this, R.string.video_not_supported_by_contact,
					Toast.LENGTH_LONG).show();
			return;
		}

		if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
				!= PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this,
					new String[]{android.Manifest.permission.CAMERA},
					REQUEST_VIDEO_CAMERA_PERMISSION);
		} else {
			launchVideoRecorder();
		}
	}

	@Override
	public void onPhoneGallerySelected() {
		PrivateMessageFormat format = viewModel.getPrivateMessageFormat().getValue();
		if (format != null && format.supportsChunkedAttachments()) {
			mediaLauncher.launch("*/*");
		} else {
			contentLauncher.launch("image/*");
		}
	}

	@Override
	public void onVaultGallerySelected() {
		Intent intent = new Intent(this, VaultActivity.class);
		intent.putExtra(VaultActivity.EXTRA_PICKER_MODE, true);
		intent.putExtra(VaultActivity.EXTRA_PICKER_TYPE, VaultActivity.PICKER_TYPE_GALLERY);
		startActivityForResult(intent, REQUEST_VAULT_GALLERY);
	}

	@Override
	public void onPhoneDocumentsSelected() {
		String[] mimeTypes = new String[]{"image/*", "application/pdf"};
		docLauncher.launch(mimeTypes);
	}

	@Override
	public void onVaultDocumentsSelected() {
		Intent intent = new Intent(this, VaultActivity.class);
		intent.putExtra(VaultActivity.EXTRA_PICKER_MODE, true);
		intent.putExtra(VaultActivity.EXTRA_PICKER_TYPE, VaultActivity.PICKER_TYPE_DOCUMENTS);
		startActivityForResult(intent, REQUEST_VAULT_DOCUMENTS);
	}

	@Override
	public void onStickersSelected() {
		if (!requireVerifiedToSendMedia()) return;
		com.professor.zerion.android.sticker.StickerPickerDialog dialog =
				com.professor.zerion.android.sticker.StickerPickerDialog
						.newInstance();
		dialog.show(getSupportFragmentManager(), "sticker_picker");
	}

	@Override
	public void onStickerEmojiPicked(String emoji) {

		Long timerObj = viewModel.getAutoDeleteTimer().getValue();
		long timer = timerObj == null ? NO_AUTO_DELETE_TIMER : timerObj;
		viewModel.sendMessage(emoji, java.util.Collections.emptyList(),
				timer, null);
	}

	@Override
	public void onCustomStickerPicked(byte[] pngBytes) {

		org.briarproject.bramble.api.sync.GroupId gid =
				viewModel.getMessagingGroupId().getValue();
		if (gid == null) {
			Toast.makeText(this, R.string.sticker_import_failed,
					Toast.LENGTH_SHORT).show();
			return;
		}
		com.professor.zerion.android.sticker.StickerSendTask task =
				new com.professor.zerion.android.sticker.StickerSendTask(
						ioExecutor, messagingManager);
		task.send(gid, pngBytes,
				new com.professor.zerion.android.sticker.StickerSendTask.Callback() {
					@Override
					public void onStickerHeaderReady(
							org.briarproject.briar.api.attachment.AttachmentHeader h) {
						Long t = viewModel.getAutoDeleteTimer().getValue();
						long timer = t == null ? NO_AUTO_DELETE_TIMER : t;
						viewModel.sendMessage(null,
								java.util.Collections.singletonList(h),
								timer, null);
					}

					@Override
					public void onStickerSendFailed(Exception e) {
						Toast.makeText(ConversationActivity.this,
								R.string.sticker_import_failed,
								Toast.LENGTH_SHORT).show();
					}
				});
	}

	@Override
	public List<AttachmentItem> loadAttachmentsForItem(ConversationMessageItem item) {
		if (!item.needsAttachmentLoading()) {
			return item.getAttachments();
		}

		PrivateMessageHeader header = item.getHeader();
		if (header != null) {
			List<AttachmentItem> attachments = getAttachmentItems(header);
			item.setAttachments(attachments);
			return attachments;
		}
		return item.getAttachments();
	}

	private void showAvatarFullScreen(com.professor.zerion.android.contact.ContactItem contactItem) {
		android.app.Dialog dialog = new android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
		dialog.setContentView(R.layout.dialog_avatar_fullscreen);

		ImageView fullScreenAvatar = dialog.findViewById(R.id.fullscreen_avatar);
		ImageView closeButton = dialog.findViewById(R.id.close_button);

		setAvatar((com.google.android.material.imageview.ShapeableImageView) fullScreenAvatar, contactItem);

		closeButton.setOnClickListener(v -> dialog.dismiss());

		dialog.findViewById(R.id.dialog_background).setOnClickListener(v -> dialog.dismiss());

		dialog.setOnDismissListener(d -> {
			if (fullScreenAvatar != null) {
				fullScreenAvatar.setImageBitmap(null);
				fullScreenAvatar.setImageDrawable(null);
			}
		});

		dialog.show();
	}

	private void startVoiceRecording() {
		voiceRecordingController.startRecording();
	}

	@Override
	public void onRecordingComplete() {
	}

	@Override
	public void onRecordingCancelled() {
	}

	@Override
	public void onRecordingError(Exception e) {
		runOnUiThread(() -> {
			String errorMessage = e.getMessage();
			if (errorMessage == null || errorMessage.isEmpty()) {
				errorMessage = getString(R.string.voice_message_error);
			}
			Snackbar.make(list, errorMessage, Snackbar.LENGTH_LONG).show();
		});
	}

	@Override
	public org.briarproject.bramble.api.sync.GroupId getGroupIdForRecording() {
		return viewModel.prepareVoiceRecording();
	}

	@Override
	public void onEncryptionInit(byte[] iv, byte[] sessionKey) {
		viewModel.onEncryptionInit(iv, sessionKey);
	}

	@Override
	public void onEncryptedChunk(byte[] encrypted, int len, byte[] tagPart) {
		viewModel.appendEncryptedAudioChunk(encrypted, len, tagPart);
	}

	@Override
	public void onEncryptionFinal(byte[] globalMAC, int totalDurationMs, int chunkCount) {
		viewModel.finalizeEncryptedVoiceMessage(globalMAC, totalDurationMs, chunkCount);
	}

	@Override
	public void cancelVoiceRecordingInViewModel() {
		viewModel.cancelVoiceRecording();
	}

	@Override
	public void onAttachmentRecordingComplete(java.io.File audioFile, int durationMs, String mimeType) {
		int seconds = durationMs / 1000;
		String message = getString(R.string.voice_message) + " (" + seconds + "s)";
		Snackbar.make(list, message, Snackbar.LENGTH_SHORT).show();
	}

	@Override
	public void storeVoiceAttachment(android.net.Uri audioUri) {
		viewModel.storeVoiceAttachment(audioUri).observe(this, result -> {
			if (result != null && result.isFinished()) {
				boolean hasErrors = false;
				for (com.professor.zerion.android.attachment.AttachmentItemResult itemResult : result.getItemResults()) {
					if (itemResult.hasError()) {
						hasErrors = true;
						String errorMsg = itemResult.getErrorMsg();
						if (errorMsg != null) {
							Snackbar.make(list, errorMsg, Snackbar.LENGTH_LONG).show();
						}
						break;
					}
				}

				if (!hasErrors) {
					Long timerValue = viewModel.getAutoDeleteTimer().getValue();
					long expectedTimer = timerValue != null ? timerValue : 0L;
					viewModel.sendVoiceAttachment(expectedTimer).observe(this, sendState -> {
						if (sendState == com.professor.zerion.android.view.TextSendController.SendState.SENT) {
						} else if (sendState == com.professor.zerion.android.view.TextSendController.SendState.ERROR) {
							Snackbar.make(list, R.string.voice_message_error, Snackbar.LENGTH_LONG).show();
						}
					});
				}
			}
		});
	}

	private void showDisappearingMessagesDialog() {
		View dialogView = getLayoutInflater().inflate(
				R.layout.dialog_disappearing_messages, null);
		android.widget.RadioGroup radioGroup = dialogView.findViewById(
				R.id.disappearing_messages_radio_group);

		Long currentTimer = viewModel.getAutoDeleteTimer().getValue();
		if (currentTimer != null) {
			radioGroup.check(getRadioIdForTimer(currentTimer));
		}

		new MaterialAlertDialogBuilder(this)
				.setView(dialogView)
				.setPositiveButton(android.R.string.ok, (dialog, which) -> {
					viewModel.setAutoDeleteTimer(getTimerForRadioId(
							radioGroup.getCheckedRadioButtonId()));
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private int getRadioIdForTimer(long timer) {
		if (timer <= 0) return R.id.timer_off;
		long seconds = timer / 1000;
		long minutes = seconds / 60;
		long hours = minutes / 60;
		long weeks = hours / 24 / 7;
		if (seconds <= 30) return R.id.timer_30_seconds;
		if (minutes <= 5) return R.id.timer_5_minutes;
		if (minutes <= 30) return R.id.timer_30_minutes;
		if (hours <= 1) return R.id.timer_1_hour;
		if (hours <= 8) return R.id.timer_8_hours;
		if (hours <= 12) return R.id.timer_12_hours;
		if (hours <= 24) return R.id.timer_24_hours;
		if (weeks <= 1) return R.id.timer_1_week;
		return R.id.timer_4_weeks;
	}

	private long getTimerForRadioId(int radioId) {
		if (radioId == R.id.timer_30_seconds) return 30 * 1000L;
		if (radioId == R.id.timer_5_minutes) return 5 * 60 * 1000L;
		if (radioId == R.id.timer_30_minutes) return 30 * 60 * 1000L;
		if (radioId == R.id.timer_1_hour) return 60 * 60 * 1000L;
		if (radioId == R.id.timer_8_hours) return 8 * 60 * 60 * 1000L;
		if (radioId == R.id.timer_12_hours) return 12 * 60 * 60 * 1000L;
		if (radioId == R.id.timer_24_hours) return 24 * 60 * 60 * 1000L;
		if (radioId == R.id.timer_1_week) return 7 * 24 * 60 * 60 * 1000L;
		if (radioId == R.id.timer_4_weeks) return 4 * 7 * 24 * 60 * 60 * 1000L;
		return -1L;
	}

	private void askToClearChat() {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.clear_chat_dialog_title)
				.setMessage(R.string.clear_chat_dialog_message)
				.setPositiveButton(R.string.clear_chat,
						(dialog, which) -> viewModel.clearChat())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void askToRemoveContact() {
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.dialog_title_delete_contact)
				.setMessage(R.string.dialog_message_delete_contact)
				.setPositiveButton(R.string.delete_contact,
						(dialog, which) -> viewModel.removeContact())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	@UiThread
	private void copySelectedMessages() {
		Selection<String> selection = tracker.getSelection();
		StringBuilder sb = new StringBuilder();
		for (String key : selection) {
			int pos = adapter.getPositionOfKey(key);
			if (pos < 0) continue;
			ConversationItem item = adapter.getItemAt(pos);
			if (item == null) continue;
			String text = item.getText();
			if (text != null && !text.isEmpty()) {
				if (sb.length() > 0) sb.append("\n");
				sb.append(text);
			}
		}
		if (sb.length() > 0) {
			com.professor.zerion.android.util.SecureClipboard.copy(this,
					"message", sb.toString());
			Snackbar.make(list, R.string.copied_to_clipboard,
					Snackbar.LENGTH_SHORT).show();
		}
		if (actionMode != null) actionMode.finish();
	}

	@UiThread
	private void reactToSelectedMessage() {
		Selection<String> selection = tracker.getSelection();
		if (selection.size() != 1) return;
		String key = selection.iterator().next();
		int pos = adapter.getPositionOfKey(key);
		if (pos < 0) return;
		ConversationItem item = adapter.getItemAt(pos);
		if (item == null) return;
		if (!(item instanceof ConversationMessageItem)) return;
		if (actionMode != null) actionMode.finish();
		onReactionClicked(item);
	}

	@UiThread
	private void forwardSelectedMessages() {
		Selection<String> selection = tracker.getSelection();
		StringBuilder sb = new StringBuilder();
		for (String key : selection) {
			int pos = adapter.getPositionOfKey(key);
			if (pos < 0) continue;
			ConversationItem item = adapter.getItemAt(pos);
			if (item == null) continue;

			if (!(item instanceof ConversationMessageItem)) continue;
			String text = item.getText();
			if (text != null && !text.isEmpty()) {
				if (sb.length() > 0) sb.append("\n");
				sb.append(text);
			}
		}
		if (sb.length() == 0) {
			if (actionMode != null) actionMode.finish();
			return;
		}
		String rawText = sb.toString();

		int maxLen = org.briarproject.briar.api.messaging
				.MessagingConstants.MAX_PRIVATE_MESSAGE_TEXT_LENGTH;
		String forwardText = rawText.length() > maxLen
				? rawText.substring(0, maxLen) : rawText;
		if (actionMode != null) actionMode.finish();

		viewModel.loadContactsForForward(contacts -> {
			if (contacts == null || contacts.isEmpty()) {
				Toast.makeText(this, R.string.no_other_contacts,
						Toast.LENGTH_SHORT).show();
				return null;
			}
			String[] names = new String[contacts.size()];
			for (int i = 0; i < contacts.size(); i++) {
				names[i] = UiUtils.getContactDisplayName(
						contacts.get(i));
			}
			new MaterialAlertDialogBuilder(this)
					.setTitle(R.string.forward_to)
					.setItems(names, (dialog, which) -> {
						ContactId recipientId =
								contacts.get(which).getId();
						viewModel.forwardMessage(recipientId,
								forwardText,
								() -> Toast.makeText(this,
										R.string.message_forwarded,
										Toast.LENGTH_SHORT).show(),
								() -> Toast.makeText(this,
										R.string.forward_failed,
										Toast.LENGTH_SHORT).show());
					})
					.setNegativeButton(android.R.string.cancel, null)
					.show();
			return null;
		});
	}

	private void deleteSelectedMessages() {
		Collection<MessageId> selected = getSelection();
		if (selected.isEmpty()) return;

		new MaterialAlertDialogBuilder(this)
				.setTitle("Delete Messages")
				.setMessage("Delete " + selected.size() + " message(s)?")
				.setPositiveButton("Delete", (dialog, which) -> {
					if (actionMode != null) actionMode.finish();
					viewModel.deleteMessages(selected);
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void onImagesChosen(@Nullable List<Uri> uris) {
		if (uris == null || uris.isEmpty()) return;
		if (!(sendController instanceof TextAttachmentController)) return;

		TextAttachmentController controller = (TextAttachmentController) sendController;

		if (uris.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
			new ZerionSnackbarBuilder()
					.make(list, "Maximum " + MAX_ATTACHMENTS_PER_MESSAGE + " attachments allowed",
							Snackbar.LENGTH_SHORT)
					.show();
			uris = uris.subList(0, MAX_ATTACHMENTS_PER_MESSAGE);
		}

		controller.onImageReceived(uris);
	}

	private void onAddedPrivateMessage(PrivateMessageHeader h) {
		ConversationItem item = h.accept(visitor);
		if (item != null) {
			runOnUiThreadUnlessDestroyed(() -> {
				adapter.add(item);
				scrollToBottom();
			});
		}
	}

	private void showImageOnboarding() {
		if (sendController instanceof TextAttachmentController) {
			((TextAttachmentController) sendController).showImageOnboarding(this);
		}
	}

	@Override
	public void onMessageLongClick(ConversationItem item) {
		if (tracker == null) addSelectionTracker();
		if (tracker != null) {
			int position = adapter.findItemPosition(item);
			if (position != INVALID_POSITION) {
				String key = adapter.getItemKey(position);
				if (key != null) {
					tracker.select(key);
				}
			}
		}
	}

	private static final String[] REACTION_EMOJIS = {
			"\uD83D\uDC4D", "\u2764\uFE0F", "\uD83D\uDE02",
			"\uD83D\uDE2E", "\uD83D\uDE22", "\uD83D\uDE21"
	};

	@Override
	public void onReactionClicked(ConversationItem item) {
		new MaterialAlertDialogBuilder(this)
				.setItems(REACTION_EMOJIS, (dialog, which) ->
						viewModel.sendReaction(item.getId(),
								REACTION_EMOJIS[which]))
				.show();
	}

	@Override
	public void onSecretNoteRevealing(boolean revealing) {
		if (revealing) {
			getWindow().addFlags(
					android.view.WindowManager.LayoutParams.FLAG_SECURE);
		}
	}

	@Override
	public void onSecretNoteRevealed(MessageId messageId) {
		if (pendingSecretBurnId != null
				&& !pendingSecretBurnId.equals(messageId)) {
			viewModel.deleteMessages(
					java.util.Collections.singleton(pendingSecretBurnId));
		}
		pendingSecretBurnId = messageId;
	}

	@Override
	public void onSecretNoteOpened(MessageId messageId) {
		if (messageId.equals(pendingSecretBurnId)) pendingSecretBurnId = null;
		viewModel.deleteMessages(java.util.Collections.singleton(messageId));
	}

	private void showSecretNoteTimerPicker(String text) {
		String[] labels = {
				getString(R.string.secret_note_timer_direct),
				getString(R.string.secret_note_timer_1m),
				getString(R.string.secret_note_timer_5m),
				getString(R.string.secret_note_timer_10m),
				getString(R.string.secret_note_timer_1h),
				getString(R.string.secret_note_timer_12h)
		};
		int[] countdowns = { 10, 60, 300, 600, 3600, 43200 };
		new MaterialAlertDialogBuilder(this)
				.setTitle(R.string.secret_note_timer_title)
				.setItems(labels, (dialog, which) -> {
					viewModel.sendSecretNote(text, countdowns[which]);
					textInputView.clearText();
				})
				.show();
	}

	@UiThread
	private void applyReactionsToItems(
			Map<MessageId, Map<String, Integer>> reactions) {
		for (int i = 0; i < adapter.getItemCount(); i++) {
			ConversationItem item = adapter.getItemAt(i);
			if (item == null) continue;
			Map<String, Integer> msgReactions = reactions.get(item.getId());
			if (msgReactions != null) {
				item.getReactions().clear();
				item.getReactions().putAll(msgReactions);
				adapter.notifyItemChanged(i);
			} else if (item.hasReactions()) {

				item.getReactions().clear();
				adapter.notifyItemChanged(i);
			}
		}
	}

	@UiThread
	private void applyLinkPreviewsToItems(
			Map<MessageId, org.briarproject.briar.api.messaging.LinkPreview> previews) {
		for (int i = 0; i < adapter.getItemCount(); i++) {
			ConversationItem item = adapter.getItemAt(i);
			if (item == null) continue;
			org.briarproject.briar.api.messaging.LinkPreview preview =
					previews.get(item.getId());
			if (preview != null) {
				item.setLinkPreview(preview);
				adapter.notifyItemChanged(i);
			}
		}
	}

	@Override
	public void respondToRequest(ConversationRequestItem item, boolean accept) {
		item.setAnswered();
		SessionId sessionId = item.getSessionId();
		ConversationRequestItem.RequestType type = item.getRequestType();
		final byte[] grouptrGid = item.getGrouptrGid();
		final GroupId messageGroupId = item.getGroupId();
		final MessageId messageId = item.getId();

		dbExecutor.execute(() -> {
			try {
				if (type == ConversationRequestItem.RequestType.GROUPTR) {
					if (grouptrGid == null) return;
					if (accept) {
						groupTrManager.acceptInvite(grouptrGid);
					} else {
						groupTrManager.declineInvite(grouptrGid);
					}
					try {
						conversationManager.setReadFlag(messageGroupId,
								messageId, true);
					} catch (DbException ignored) {
					}
				} else if (type == ConversationRequestItem.RequestType.INTRODUCTION) {
					if (sessionId == null) return;
					introductionManager.respondToIntroduction(contactId, sessionId, accept);
				}
				runOnUiThread(() -> {
					if (type == ConversationRequestItem.RequestType.GROUPTR) {
						Snackbar.make(list,
								accept ? R.string.groups_invitations_joined
										: R.string.groups_invitations_declined,
								Snackbar.LENGTH_SHORT).show();
					}
					adapter.notifyDataSetChanged();
				});
			} catch (DbException e) {
				runOnUiThread(() -> handleException(e));
			}
		});
	}

	@Override
	public void openRequestedShareable(ConversationRequestItem item) {
		if (item.getRequestType() == ConversationRequestItem.RequestType.GROUPTR) {
			byte[] grouptrGid = item.getGrouptrGid();
			if (grouptrGid != null) {
				startActivity(com.professor.zerion.android.grouptr
						.GroupTrConversationActivity
						.intent(this, grouptrGid));
			}
		}
	}

	@Override
	public void onAttachmentClicked(View view, ConversationMessageItem messageItem,
			AttachmentItem attachmentItem) {
		if (attachmentItem.getState() != AttachmentItem.State.ERROR) {
			if (attachmentItem.isVideo()) {
				Intent intent = new Intent(this, VideoPlayerActivity.class);
				intent.putExtra(VideoPlayerActivity.ATTACHMENT, attachmentItem);
				intent.putExtra(VideoPlayerActivity.ITEM_ID, messageItem.getId().getBytes());
				startActivity(intent);
			} else {
				Intent intent = new Intent(this, ImageActivity.class);
				intent.putExtra(CONTACT_ID, contactId.getInt());
				String imgName = viewModel.getContactDisplayName().getValue();
				intent.putExtra(NAME, imgName != null ? imgName : "");
				intent.putExtra(ITEM_ID, messageItem.getId().getBytes());
				intent.putExtra(DATE, messageItem.getTime());
				intent.putExtra(ATTACHMENT_POSITION, messageItem.getAttachments().indexOf(attachmentItem));
				intent.putParcelableArrayListExtra(ATTACHMENTS,
						new ArrayList<>(messageItem.getAttachments()));

				ActivityOptionsCompat options =
						makeSceneTransitionAnimation(this, view, "image");
				startActivity(intent, options.toBundle());
			}
		}
	}

	@Override
	public void onAutoDeleteTimerNoticeClicked() {
		Intent intent = new Intent(this, ChatSettingsActivity.class);
		intent.putExtra(CONTACT_ID, contactId.getInt());
		startActivity(intent);
	}

	private String encodeSignal(String signal) {
		try {
			return android.util.Base64.encodeToString(
				signal.getBytes(java.nio.charset.StandardCharsets.UTF_8),
				android.util.Base64.NO_WRAP);
		} catch (Exception e) {
			return "";
		}
	}

	private String decodeSignal(String encoded) {
		try {
			byte[] decoded = android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP);
			return new String(decoded, java.nio.charset.StandardCharsets.UTF_8);
		} catch (Exception e) {
			return null;
		}
	}

	private boolean isVoiceCallSignal(String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		return com.professor.zerion.android.conversation.voice.VoiceCallSignal.isSignal(text);
	}

	private boolean isValidCallKey(String key) {
		if (key == null || key.isEmpty()) {
			return false;
		}
		return CALL_KEY_PATTERN.matcher(key).matches();
	}

	private void handleSecurityException(Exception e) {
		Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
		if (handler != null && e instanceof RuntimeException) {
			handler.uncaughtException(Thread.currentThread(), e);
		}
	}

	@UiThread
	private void performMessageSearch(String query) {
		searchMatchPositions.clear();
		searchMatchIndex = -1;
		adapter.setHighlightedPosition(-1);

		if (query.isEmpty()) return;

		String lowerQuery = query.toLowerCase(java.util.Locale.ROOT);
		for (int i = 0; i < adapter.getItemCount(); i++) {
			ConversationItem item = adapter.getItemAt(i);
			if (item == null) continue;
			String text = item.getText();
			if (text != null &&
					text.toLowerCase(java.util.Locale.ROOT)
							.contains(lowerQuery)) {
				searchMatchPositions.add(i);
			}
		}

		if (!searchMatchPositions.isEmpty()) {
			searchMatchIndex = 0;
			scrollToSearchMatch();
		} else {
			Toast.makeText(this, R.string.search_no_results,
					Toast.LENGTH_SHORT).show();
		}
	}

	@UiThread
	private void navigateSearchNext() {
		if (searchMatchPositions.isEmpty()) return;
		searchMatchIndex = (searchMatchIndex + 1)
				% searchMatchPositions.size();
		scrollToSearchMatch();
	}

	@UiThread
	private void scrollToSearchMatch() {
		if (searchMatchIndex < 0 ||
				searchMatchIndex >= searchMatchPositions.size()) return;
		int position = searchMatchPositions.get(searchMatchIndex);
		if (position >= adapter.getItemCount()) {

			clearSearchHighlight();
			return;
		}
		adapter.setHighlightedPosition(position);
		layoutManager.scrollToPositionWithOffset(position,
				list.getRecyclerView().getHeight() / 3);
	}

	@UiThread
	private void clearSearchHighlight() {
		searchMatchPositions.clear();
		searchMatchIndex = -1;
		adapter.setHighlightedPosition(-1);
	}
}
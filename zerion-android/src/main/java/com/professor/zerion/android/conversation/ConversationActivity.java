package com.professor.zerion.android.conversation;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
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
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import com.professor.zerion.R;
import com.professor.zerion.android.activity.ActivityComponent;
import com.professor.zerion.android.activity.ZerionActivity;
import com.professor.zerion.android.attachment.AttachmentItem;
import com.professor.zerion.android.attachment.AttachmentRetriever;
import com.professor.zerion.android.conversation.ConversationVisitor.AttachmentCache;
import com.professor.zerion.android.conversation.ConversationVisitor.TextCache;
import com.professor.zerion.android.fragment.BaseFragment.BaseFragmentListener;
import com.professor.zerion.android.introduction.IntroductionActivity;
import com.professor.zerion.android.privategroup.conversation.GroupActivity;
import com.professor.zerion.android.removabledrive.RemovableDriveActivity;
import com.professor.zerion.android.util.ActivityLaunchers.GetMultipleImagesAdvanced;
import com.professor.zerion.android.util.ActivityLaunchers.OpenMultipleImageDocumentsAdvanced;
import com.professor.zerion.android.conversation.voice.VoiceRecordingController;
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
import org.briarproject.bramble.api.db.DatabaseExecutor;
import java.util.concurrent.Executor;
import org.briarproject.briar.api.attachment.AttachmentHeader;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.conversation.ConversationMessageVisitor;
import org.briarproject.briar.api.conversation.ConversationRequest;
import org.briarproject.briar.api.conversation.ConversationResponse;
import org.briarproject.briar.api.conversation.event.ConversationMessageReceivedEvent;
import org.briarproject.briar.api.client.SessionId;
import org.briarproject.briar.api.introduction.IntroductionManager;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager;
import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.widget.ActionMenuView;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.selection.Selection;
import androidx.recyclerview.selection.SelectionPredicates;
import androidx.recyclerview.selection.SelectionTracker;
import androidx.recyclerview.selection.SelectionTracker.SelectionObserver;
import androidx.recyclerview.selection.StorageStrategy;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import de.hdodenhof.circleimageview.CircleImageView;

import java.util.Arrays;

import static android.view.Gravity.RIGHT;
import static androidx.core.app.ActivityOptionsCompat.makeSceneTransitionAnimation;
import static androidx.lifecycle.Lifecycle.State.STARTED;
import static androidx.recyclerview.widget.SortedList.INVALID_POSITION;
import static java.util.Collections.sort;
import static java.util.Objects.requireNonNull;
import static org.briarproject.bramble.util.StringUtils.fromHexString;
import static org.briarproject.bramble.util.StringUtils.isNullOrEmpty;
import static org.briarproject.bramble.util.StringUtils.join;
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
	GroupInvitationManager groupInvitationManager;

	@Inject
	IntroductionManager introductionManager;

	private final Map<MessageId, String> textCache = new ConcurrentHashMap<>();

	private final ActivityResultLauncher<String[]> docLauncher =
			registerForActivityResult(new OpenMultipleImageDocumentsAdvanced(),
					this::onImagesChosen);
	private final ActivityResultLauncher<String> contentLauncher =
			registerForActivityResult(new GetMultipleImagesAdvanced(),
					this::onImagesChosen);
	private final ActivityResultLauncher<Uri> cameraLauncher =
			registerForActivityResult(new ActivityResultContracts.TakePicture(),
					this::onPhotoTaken);

	@Nullable
	private Uri cameraPhotoUri;
	private AttachmentRetriever attachmentRetriever;
	private ConversationViewModel viewModel;
	private ConversationVisitor visitor;
	private ConversationAdapter adapter;
	private Toolbar toolbar;
	private CircleImageView toolbarAvatar;
	private ImageView toolbarStatus;
	private TextView toolbarTitle;
	private ZerionRecyclerView list;
	private LinearLayoutManager layoutManager;
	private TextInputView textInputView;
	private TextSendController sendController;
	private SelectionTracker<String> tracker;
	@Nullable
	private Parcelable layoutManagerState;
	@Nullable
	private ActionMode actionMode;
	@Nullable
	private com.google.android.material.floatingactionbutton.FloatingActionButton scrollToBottomButton;

	private volatile ContactId contactId;

	private BroadcastReceiver voiceCallCleanupReceiver = new BroadcastReceiver() {
		@Override
		public void onReceive(Context context, Intent intent) {
			String callId = intent.getStringExtra("call_id");
			if (callId != null) {
				viewModel.cleanupVoiceCallMessages(callId);
			}
		}
	};

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

		Intent i = getIntent();
		int id = i.getIntExtra(CONTACT_ID, -1);
		if (id == -1) throw new IllegalStateException();
		contactId = new ContactId(id);

		viewModel.setContactId(contactId);
		attachmentRetriever = viewModel.getAttachmentRetriever();

		// Initialize voice recording controller
		voiceRecordingController = new VoiceRecordingController(this, dbExecutor);
		voiceRecordingController.initRecorder(this);
		getLifecycle().addObserver(voiceRecordingController);

		setContentView(R.layout.activity_conversation);

		toolbar = requireNonNull(setUpCustomToolbar(true));
		toolbarAvatar = toolbar.findViewById(R.id.contactAvatar);
		toolbarStatus = toolbar.findViewById(R.id.contactStatus);
		toolbarTitle = toolbar.findViewById(R.id.contactName);

		viewModel.getContactItem().observe(this, contactItem -> {
			requireNonNull(contactItem);
			setAvatar(toolbarAvatar, contactItem);
			toolbarAvatar.setOnClickListener(v -> showAvatarFullScreen(contactItem));
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

		visitor = new ConversationVisitor(this, this, this,
				viewModel.getContactDisplayName(), viewModel);
		adapter = new ConversationAdapter(this, this,
				attachmentRetriever.getAttachmentReader(), dbExecutor);
		list = findViewById(R.id.conversationView);
		layoutManager = new LinearLayoutManager(this);
		list.setLayoutManager(layoutManager);
		list.setAdapter(adapter);
		list.setEmptyText(getString(R.string.no_private_messages));
		ConversationScrollListener scrollListener =
				new ConversationScrollListener(adapter, viewModel);
		list.getRecyclerView().addOnScrollListener(scrollListener);

		textInputView = findViewById(R.id.text_input_container);
		if (featureFlags.shouldEnableImageAttachments()) {
			ImagePreview imagePreview = findViewById(R.id.imagePreview);
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

		// Bind voice recording UI views to controller
		voiceRecordingController.bindViews(
				findViewById(R.id.voiceRecordingOverlay),
				findViewById(R.id.recording_time),
				findViewById(R.id.recording_pulse),
				findViewById(R.id.cancel_recording_button),
				findViewById(R.id.send_voice_button),
				textInputView);

		viewModel.getAutoDeleteTimer().observe(this, timer ->
				sendController.setAutoDeleteTimer(timer));

		// Observe message texts - populate cache before headers arrive
		viewModel.getMessageTexts().observe(this, texts -> {
			if (texts != null) {
				textCache.putAll(texts);
			}
		});

		// Observe message headers from ViewModel
		viewModel.getMessageHeaders().observe(this, this::onMessageHeadersLoaded);

		// Observe individual message text loading (for newly sent messages)
		viewModel.getMessageTextLoaded().observeEvent(this, pair -> {
			if (pair != null) {
				displayMessageText(pair.getFirst(), pair.getSecond());
			}
		});

		// Observe chat cleared event
		viewModel.getChatCleared().observeEvent(this, cleared -> {
			if (cleared != null && cleared) {
				adapter.clear();
			}
		});

		// Observe message deletion events
		viewModel.getMessagesDeleted().observeEvent(this, messageIds -> {
			if (messageIds != null) {
				for (MessageId msgId : messageIds) {
					textCache.remove(msgId);
				}
				adapter.incrementRevision();
				adapter.removeItems(messageIds);
			}
		});

		// Observe message marking events (sent/seen)
		viewModel.getMessagesMarked().observeEvent(this, event -> {
			if (event != null) {
				onMessagesMarked(event.messageIds, event.sent, event.seen);
			}
		});

		// Observe connection status changes (moved from EventBus)
		viewModel.isContactConnected().observe(this, connected -> {
			if (connected != null) {
				updateConnectionStatusUI(connected);
			}
		});

		// Observe new messages received (moved from EventBus)
		viewModel.getNewMessageReceived().observeEvent(this, header -> {
			if (header != null) {
				onNewConversationMessage(header);
			}
		});

		// Observe client version updates (moved from EventBus)
		viewModel.getClientVersionUpdated().observeEvent(this, clientId -> {
			if (clientId != null && clientId.equals(MessagingManager.CLIENT_ID)) {
				viewModel.recheckFeaturesAndOnboarding(contactId);
			}
		});
	}

	private void setupCameraAndAttachmentButtons() {
		if (sendController instanceof TextAttachmentController) {
			TextAttachmentController attachmentController = (TextAttachmentController) sendController;

			attachmentController.setOnAttachmentClickListener(v -> {
				AttachmentPickerDialog dialog = AttachmentPickerDialog.newInstance();
				dialog.show(getSupportFragmentManager(), "attachment_picker");
			});

			attachmentController.setOnVoiceClickListener(v -> {
				if (checkVoicePermission()) {
					startVoiceRecording();
				} else {
					requestVoicePermission();
				}
			});
		}
	}

	private static final int REQUEST_CAMERA_PERMISSION = 1002;
	private static final int REQUEST_TAKE_PHOTO = 1003;
	private static final int REQUEST_VAULT_GALLERY = 1004;
	private static final int REQUEST_VAULT_DOCUMENTS = 1005;
	private static final int REQUEST_RECORD_AUDIO = 1006;
	private static final int REQUEST_VOICE_CALL = 1007;
	private Uri photoUri;

	// Voice recording controller (manages recording UI and state)
	private VoiceRecordingController voiceRecordingController;

	private void launchCamera() {
		Intent takePictureIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
		if (takePictureIntent.resolveActivity(getPackageManager()) != null) {
			try {
				java.io.File photoFile = new java.io.File(new java.io.File(getFilesDir(), "camera"),
						"temp_" + System.currentTimeMillis() + ".jpg");
				if (!photoFile.getParentFile().exists()) {
					photoFile.getParentFile().mkdirs();
				}
				photoUri = androidx.core.content.FileProvider.getUriForFile(this,
						"com.professor.zerion.fileprovider", photoFile);
				takePictureIntent.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, photoUri);
				startActivityForResult(takePictureIntent, REQUEST_TAKE_PHOTO);
			} catch (Exception e) {
				handleSecurityException(e);
			}
		}
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

		// Android 14+ requires FOREGROUND_SERVICE_MICROPHONE for foreground services using microphone
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
			boolean hasForegroundMic = ContextCompat.checkSelfPermission(this,
					android.Manifest.permission.FOREGROUND_SERVICE_MICROPHONE) == PackageManager.PERMISSION_GRANTED;
			return hasRecordAudio && hasForegroundMic;
		}

		return hasRecordAudio;
	}

	private void requestVoicePermission() {
		// Android 14+ requires FOREGROUND_SERVICE_MICROPHONE for foreground services using microphone
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
			// Request both RECORD_AUDIO and FOREGROUND_SERVICE_MICROPHONE (Android 14+)
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
		intent.putExtra(com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_CONTACT_NAME,
				viewModel.getContactDisplayName().getValue());
		intent.putExtra(com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_IS_INCOMING,
				false);
		startActivity(intent);
	}

	private void handleIncomingVoiceCallSignal(String signalMessage) {
		// Parse structured signal format with full validation
		com.professor.zerion.android.conversation.voice.VoiceCallSignal signal =
				com.professor.zerion.android.conversation.voice.VoiceCallSignal.fromWireFormat(signalMessage);

		if (signal == null) {
			// Signal failed validation - ignore silently
			return;
		}

		switch (signal.getType()) {
			case CALL_OFFER:
				handleCallOffer(signal);
				break;

			case CALL_ANSWER:
			case CALL_REJECT:
			case CALL_END:
				// Forward to VoiceCallService for handling
				forwardSignalToService(signalMessage);
				break;

			default:
				// Unknown signal type - ignore
				break;
		}
	}

	private void handleCallOffer(com.professor.zerion.android.conversation.voice.VoiceCallSignal signal) {
		String remoteCallId = signal.getCallId();
		String voiceCallKey = signal.getVoiceCallKey();

		// Voice call key is already validated by VoiceCallSignal.fromWireFormat()

		if (!checkVoicePermission()) {
			// Request both RECORD_AUDIO and FOREGROUND_SERVICE_MICROPHONE (Android 14+)
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
		intent.putExtra(com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_CONTACT_NAME,
				viewModel.getContactDisplayName().getValue());
		intent.putExtra(com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_IS_INCOMING,
				true);
		intent.putExtra(com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_CALL_ID,
				remoteCallId);
		if (voiceCallKey != null) {
			intent.putExtra(com.professor.zerion.android.conversation.voice.VoiceCallActivity.EXTRA_VOICE_CALL_KEY,
					voiceCallKey);
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
		} else if (requestCode == REQUEST_RECORD_AUDIO) {
			// For Android 14+, we request both RECORD_AUDIO and FOREGROUND_SERVICE_MICROPHONE
			// Check if at least RECORD_AUDIO was granted (both required for foreground service)
			if (grantResults.length > 0 &&
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				// On Android 14+, verify both permissions granted before starting
				if (checkVoicePermission()) {
					startVoiceRecording();
				}
			}
		} else if (requestCode == REQUEST_VOICE_CALL) {
			// For Android 14+, we request both RECORD_AUDIO and FOREGROUND_SERVICE_MICROPHONE
			// Check if at least RECORD_AUDIO was granted (both required for foreground service)
			if (grantResults.length > 0 &&
					grantResults[0] == PackageManager.PERMISSION_GRANTED) {
				// On Android 14+, verify both permissions granted before starting
				if (checkVoicePermission()) {
					startVoiceCall();
				}
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
				viewModel.storeAttachments(uris, false);
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
		IntentFilter filter = new IntentFilter("com.professor.zerion.CLEANUP_VOICE_CALL");
		LocalBroadcastManager.getInstance(this).registerReceiver(voiceCallCleanupReceiver, filter);
		loadMessages();
	}

	@Override
	public void onStop() {
		super.onStop();

		// Voice recording cleanup is handled by VoiceRecordingController lifecycle observer

		// Stop VoiceCallService to prevent crash when returning from background
		try {
			Intent serviceIntent = new Intent(this,
					com.professor.zerion.android.conversation.voice.VoiceCallService.class);
			stopService(serviceIntent);
		} catch (Exception e) {
			// Ignore if service wasn't running
		}

		notificationManager.unblockContactNotification(contactId);
		list.stopPeriodicUpdate();
		try {
			LocalBroadcastManager.getInstance(this).unregisterReceiver(voiceCallCleanupReceiver);
		} catch (IllegalArgumentException e) {
			handleSecurityException(e);
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
	public boolean onCreateOptionsMenu(Menu menu) {
		MenuInflater inflater = getMenuInflater();
		inflater.inflate(R.menu.conversation_actions, menu);
		return super.onCreateOptionsMenu(menu);
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int itemId = item.getItemId();
		if (itemId == android.R.id.home) {
			onBackPressed();
			return true;
		} else if (itemId == R.id.action_voice_call) {
			startVoiceCall();
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
		} else if (itemId == R.id.action_block_user) {
			askToBlockContact();
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
		return false;
	}

	@Override
	public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
		if (item.getItemId() == R.id.action_delete) {
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
		// Check initial status and trigger observer
		viewModel.checkConnectionStatus(connectionRegistry);
	}

	@UiThread
	private void updateConnectionStatusUI(boolean connected) {
		if (connected) {
			toolbarStatus.setImageResource(R.drawable.contact_online);
			toolbarStatus.setContentDescription(getString(R.string.online));
		} else {
			toolbarStatus.setImageResource(R.drawable.contact_offline);
			toolbarStatus.setContentDescription(getString(R.string.offline));
		}
	}

	private void loadMessages() {
		// Trigger message loading via ViewModel
		viewModel.loadMessageHeaders();
	}

	private void onMessageHeadersLoaded(Collection<ConversationMessageHeader> headers) {
		if (headers == null) return;
		List<ConversationMessageHeader> sorted = new ArrayList<>(headers);
		sort(sorted, (a, b) -> Long.compare(b.getTimestamp(), a.getTimestamp()));
		displayMessages(sorted);
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
		// Delegate to ViewModel - result observed via getMessageTextLoaded()
		viewModel.loadMessageText(m);
	}

	private void displayMessageText(MessageId m, String text) {
		runOnUiThreadUnlessDestroyed(() -> {
			textCache.put(m, text);
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

	// When a message's text or attachments are loaded, scroll to the bottom
	// if the conversation is visible and we were previously at the bottom
	private boolean shouldScrollWhenUpdatingMessage() {
		return getLifecycle().getCurrentState().isAtLeast(STARTED)
				&& adapter.isScrolledToBottom(layoutManager);
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
			// contact name might not have been loaded
			observeOnce(viewModel.getContactDisplayName(), this,
					name -> addConversationItem(h.accept(visitor)));
		} else {
			// visitor also loads message text and attachments (if existing)
			addConversationItem(h.accept(visitor));
		}
	}

	@UiThread
	private void onMessagesMarked(Collection<MessageId> messageIds, boolean sent,
			boolean seen) {
		adapter.incrementRevision();
		Set<MessageId> messages = new HashSet<>(messageIds);
		SparseArray<ConversationItem> list = adapter.getOutgoingMessages();
		for (int i = 0; i < list.size(); i++) {
			ConversationItem item = list.valueAt(i);
			if (messages.contains(item.getId())) {
				item.setSent(sent);
				item.setSeen(seen);
				adapter.notifyItemChanged(list.keyAt(i));
			}
		}
	}

	@Override
	public LiveData<SendState> onSendClick(@Nullable String text,
			List<AttachmentHeader> headers, long expectedAutoDeleteTimer) {
		ConversationItem replyToItem = textInputView.getReplyingToItem();
		LiveData<SendState> result = viewModel.sendMessage(text, headers, expectedAutoDeleteTimer, replyToItem);
		// Clear reply preview after sending
		textInputView.hideReplyPreview();
		return result;
	}

	@Override
	public void onAttachImageClicked() {
		launchActivityToOpenFile(this, docLauncher, contentLauncher, "image/*");
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

	/**
	 * Called by {@link PrivateMessageHeader#accept(ConversationMessageVisitor)}
	 */
	@Override
	public List<AttachmentItem> getAttachmentItems(PrivateMessageHeader h) {
		List<LiveData<AttachmentItem>> liveDataList =
				attachmentRetriever.getAttachmentItems(h);
		List<AttachmentItem> items = new ArrayList<>(liveDataList.size());
		for (LiveData<AttachmentItem> liveData : liveDataList) {
			// first remove all our observers to avoid having more than one
			// in case we reload the conversation, e.g. after deleting messages
			liveData.removeObservers(this);
			// add a new observer
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
		try {
			File photoFile = createImageFile();
			cameraPhotoUri = FileProvider.getUriForFile(this,
					getPackageName() + ".fileprovider", photoFile);
			cameraLauncher.launch(cameraPhotoUri);
		} catch (IOException e) {
			new ZerionSnackbarBuilder()
					.setBackgroundColor(R.color.zerion_error_red)
					.make(list, R.string.image_attach_error, Snackbar.LENGTH_LONG)
					.show();
		}
	}

	private File createImageFile() throws IOException {
		String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
				.format(new Date());
		String imageFileName = "ZERION_" + timeStamp + "_";
		File storageDir = new File(getCacheDir(), "camera_photos");
		if (!storageDir.exists()) {
			storageDir.mkdirs();
		}
		return File.createTempFile(imageFileName, ".jpg", storageDir);
	}

	private void onPhotoTaken(Boolean success) {
		if (success && cameraPhotoUri != null) {
			List<Uri> uris = Collections.singletonList(cameraPhotoUri);
			onImagesChosen(uris);
		}
		cameraPhotoUri = null;
	}

	@Override
	public void onPhoneGallerySelected() {
		contentLauncher.launch("image/*");
	}

	@Override
	public void onVaultGallerySelected() {
		// Open vault gallery
	}

	@Override
	public void onPhoneDocumentsSelected() {
		String[] mimeTypes = new String[]{"image/*", "application/pdf"};
		docLauncher.launch(mimeTypes);
	}

	@Override
	public void onVaultDocumentsSelected() {
		// Open vault documents
	}

	@Override
	public List<AttachmentItem> loadAttachmentsForItem(ConversationMessageItem item) {
		// If attachments already loaded, return them
		if (!item.needsAttachmentLoading()) {
			return item.getAttachments();
		}

		// Load attachments lazily using getAttachmentItems (triggers observer setup)
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

		setAvatar((de.hdodenhof.circleimageview.CircleImageView) fullScreenAvatar, contactItem);

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

	// ==================== VoiceRecordingHost Interface Implementation ====================

	@Override
	public void onRecordingComplete() {
		// Recording finished and message sent
	}

	@Override
	public void onRecordingCancelled() {
		// Recording was cancelled by user
	}

	@Override
	public void onRecordingError(Exception e) {
		runOnUiThread(() -> {
			String errorMessage = e.getMessage();
			if (errorMessage == null || errorMessage.isEmpty()) {
				errorMessage = getString(R.string.voice_message_error);
			}
			// Show user-friendly error message
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

	// ==================== Attachment Recording Callbacks ====================

	@Override
	public void onAttachmentRecordingComplete(java.io.File audioFile, int durationMs, String mimeType) {
		// Attachment recording completed - file will be stored via storeVoiceAttachment
		// Show feedback to user
		int seconds = durationMs / 1000;
		String message = getString(R.string.voice_message) + " (" + seconds + "s)";
		Snackbar.make(list, message, Snackbar.LENGTH_SHORT).show();
	}

	@Override
	public void storeVoiceAttachment(android.net.Uri audioUri) {
		// Store the audio file as an attachment and send
		viewModel.storeVoiceAttachment(audioUri).observe(this, result -> {
			if (result != null && result.isFinished()) {
				// Check for errors in the result
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
					// Send the voice attachment
					Long timerValue = viewModel.getAutoDeleteTimer().getValue();
					long expectedTimer = timerValue != null ? timerValue : 0L;
					viewModel.sendVoiceAttachment(expectedTimer).observe(this, sendState -> {
						if (sendState == com.professor.zerion.android.view.TextSendController.SendState.SENT) {
							// Success - attachment sent
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
				.setTitle("Clear Chat")
				.setMessage("Delete all messages in this conversation?")
				.setPositiveButton("Clear", (dialog, which) -> viewModel.clearChat())
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void askToBlockContact() {
		new MaterialAlertDialogBuilder(this)
				.setTitle("Block Contact")
				.setMessage("Block this contact? They will not be able to send you messages.")
				.setPositiveButton("Block", (dialog, which) -> {
					finish();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void askToRemoveContact() {
		new MaterialAlertDialogBuilder(this)
				.setTitle("Remove Contact")
				.setMessage("Remove this contact? All messages will be deleted.")
				.setPositiveButton("Remove", (dialog, which) -> {
					// Delegate to ViewModel - isContactDeleted() observer will finish activity
					viewModel.removeContact();
				})
				.setNegativeButton(android.R.string.cancel, null)
				.show();
	}

	private void deleteSelectedMessages() {
		Collection<MessageId> selected = getSelection();
		if (selected.isEmpty()) return;

		new MaterialAlertDialogBuilder(this)
				.setTitle("Delete Messages")
				.setMessage("Delete " + selected.size() + " message(s)?")
				.setPositiveButton("Delete", (dialog, which) -> {
					// Close action mode first
					if (actionMode != null) actionMode.finish();
					// Delegate to ViewModel - getMessagesDeleted() observer handles UI update
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

		viewModel.storeAttachments(uris, false);
	}

	private void onAddedPrivateMessage(PrivateMessageHeader h) {
		// Convert header to ConversationItem using visitor
		// The visitor will trigger text/attachment loading via existing observers
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

	@Override
	public void respondToRequest(ConversationRequestItem item, boolean accept) {
		item.setAnswered();
		SessionId sessionId = item.getSessionId();
		ConversationRequestItem.RequestType type = item.getRequestType();

		dbExecutor.execute(() -> {
			try {
				if (type == ConversationRequestItem.RequestType.GROUP) {
					groupInvitationManager.respondToInvitation(contactId, sessionId, accept);
				} else if (type == ConversationRequestItem.RequestType.INTRODUCTION) {
					introductionManager.respondToIntroduction(contactId, sessionId, accept);
				}
				runOnUiThread(() -> {
					if (accept) {
						if (type == ConversationRequestItem.RequestType.GROUP) {
							Snackbar.make(list, R.string.groups_invitations_joined, Snackbar.LENGTH_SHORT).show();
						}
					} else {
						if (type == ConversationRequestItem.RequestType.GROUP) {
							Snackbar.make(list, R.string.groups_invitations_declined, Snackbar.LENGTH_SHORT).show();
						}
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
		if (item.getRequestType() == ConversationRequestItem.RequestType.GROUP) {
			GroupId groupId = item.getRequestedGroupId();
			if (groupId != null) {
				Intent intent = new Intent(this, GroupActivity.class);
				intent.putExtra(GroupActivity.GROUP_ID, groupId.getBytes());
				startActivity(intent);
			}
		}
	}

	@Override
	public void onAttachmentClicked(View view, ConversationMessageItem messageItem,
			AttachmentItem attachmentItem) {
		if (attachmentItem.getState() != AttachmentItem.State.ERROR) {
			Intent intent = new Intent(this, ImageActivity.class);
			intent.putExtra(CONTACT_ID, contactId.getInt());
			intent.putExtra(NAME, viewModel.getContactDisplayName().getValue());
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

	/**
	 * Checks if a message is a voice call signal using the structured format.
	 * Uses VoiceCallSignal.isSignal() for fast prefix detection without full parsing.
	 */
	private boolean isVoiceCallSignal(String text) {
		if (text == null || text.isEmpty()) {
			return false;
		}
		// Use structured signal detection (checks for binary prefix)
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
}
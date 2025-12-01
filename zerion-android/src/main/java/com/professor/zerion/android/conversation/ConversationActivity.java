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
import org.briarproject.bramble.api.contact.event.ContactRemovedEvent;
import org.briarproject.bramble.api.db.DatabaseExecutor;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.NoSuchContactException;
import org.briarproject.bramble.api.event.Event;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.plugin.event.ContactConnectedEvent;
import org.briarproject.bramble.api.plugin.event.ContactDisconnectedEvent;
import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.event.MessagesAckedEvent;
import org.briarproject.bramble.api.sync.event.MessagesSentEvent;
import org.briarproject.bramble.api.versioning.event.ClientVersionUpdatedEvent;
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
import org.briarproject.briar.api.autodelete.event.ConversationMessagesDeletedEvent;
import org.briarproject.briar.api.client.ProtocolStateException;
import org.briarproject.briar.api.client.SessionId;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.conversation.ConversationMessageVisitor;
import org.briarproject.briar.api.conversation.ConversationRequest;
import org.briarproject.briar.api.conversation.ConversationResponse;
import org.briarproject.briar.api.conversation.DeletionResult;
import org.briarproject.briar.api.conversation.event.ConversationMessageReceivedEvent;
import org.briarproject.briar.api.introduction.IntroductionManager;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessageHeader;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager;
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
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.widget.ActionMenuView;
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
		implements BaseFragmentListener, EventListener, ConversationListener,
		TextCache, AttachmentCache, AttachmentListener, ActionMode.Callback,
		AttachmentPickerDialog.AttachmentPickerListener {

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
	volatile ContactManager contactManager;
	@Inject
	volatile MessagingManager messagingManager;
	@Inject
	volatile ConversationManager conversationManager;
	@Inject
	volatile EventBus eventBus;
	@Inject
	volatile IntroductionManager introductionManager;
	@Inject
	volatile GroupInvitationManager groupInvitationManager;
	@Inject
	@DatabaseExecutor
	Executor dbExecutor;

	private final Map<MessageId, String> textCache = new ConcurrentHashMap<>();
	private final Observer<String> contactNameObserver = name -> {
		requireNonNull(name);
		loadMessages();
	};

	private final ActivityResultLauncher<String[]> docLauncher =
			registerForActivityResult(new OpenMultipleImageDocumentsAdvanced(),
					this::onImagesChosen);
	private final ActivityResultLauncher<String> contentLauncher =
			registerForActivityResult(new GetMultipleImagesAdvanced(),
					this::onImagesChosen);

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

		voiceRecorder = new com.professor.zerion.android.conversation.voice.VoiceMessageRecorder(
				this, dbExecutor);

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

		SwipeToReplyCallback swipeCallback = new SwipeToReplyCallback(this,
			this::onSwipeToReply);
		ItemTouchHelper itemTouchHelper = new ItemTouchHelper(swipeCallback);
		itemTouchHelper.attachToRecyclerView(list.getRecyclerView());


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

		// Initialize voice recording overlay views
		voiceRecordingOverlay = findViewById(R.id.voiceRecordingOverlay);
		recordingTimer = findViewById(R.id.recording_time);
		recordingPulse = findViewById(R.id.recording_pulse);
		cancelRecordingButton = findViewById(R.id.cancel_recording_button);
		sendVoiceButton = findViewById(R.id.send_voice_button);
		if (cancelRecordingButton != null) {
			cancelRecordingButton.setOnClickListener(v -> stopVoiceRecording());
		}
		if (sendVoiceButton != null) {
			sendVoiceButton.setOnClickListener(v -> finishVoiceRecording());
		}

		viewModel.getAutoDeleteTimer().observe(this, timer ->
				sendController.setAutoDeleteTimer(timer));
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
	private com.professor.zerion.android.conversation.voice.VoiceMessageRecorder voiceRecorder;
	private boolean isRecording = false;
	private android.animation.ValueAnimator pulseAnimator;

	// Voice recording overlay views
	private View voiceRecordingOverlay;
	private TextView recordingTimer;
	private View recordingPulse;
	private androidx.appcompat.widget.AppCompatImageButton cancelRecordingButton;
	private androidx.appcompat.widget.AppCompatImageButton sendVoiceButton;
	private long recordingStartTime = 0;

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
		eventBus.addListener(this);
		notificationManager.blockContactNotification(contactId);
		notificationManager.clearContactNotification(contactId);
		displayContactOnlineStatus();
		viewModel.getContactDisplayName().observe(this, contactNameObserver);
		list.startPeriodicUpdate();
		IntentFilter filter = new IntentFilter("com.professor.zerion.CLEANUP_VOICE_CALL");
		LocalBroadcastManager.getInstance(this).registerReceiver(voiceCallCleanupReceiver, filter);
	}

	@Override
	public void onStop() {
		super.onStop();

		// SECURITY: Zeroize voice recording state if activity stops during recording
		if (isRecording && voiceRecorder != null) {
			voiceRecorder.cancelStreamingRecording();
			isRecording = false;
			viewModel.cancelVoiceRecording();  // Will zeroize all crypto material
			hideRecordingBar();
		}

		// Stop VoiceCallService to prevent crash when returning from background
		try {
			Intent serviceIntent = new Intent(this,
					com.professor.zerion.android.conversation.voice.VoiceCallService.class);
			stopService(serviceIntent);
		} catch (Exception e) {
			// Ignore if service wasn't running
		}

		eventBus.removeListener(this);
		notificationManager.unblockContactNotification(contactId);
		viewModel.getContactDisplayName().removeObserver(contactNameObserver);
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
		if (connectionRegistry.isConnected(contactId)) {
			toolbarStatus.setImageResource(R.drawable.contact_online);
			toolbarStatus.setContentDescription(getString(R.string.online));
		} else {
			toolbarStatus.setImageResource(R.drawable.contact_offline);
			toolbarStatus.setContentDescription(getString(R.string.offline));
		}
	}

	private void loadMessages() {
		// OPTIMIZATION: Try to display cached messages instantly first
		List<ConversationMessageHeader> cachedHeaders =
				ConversationCache.getInstance().getSnapshot(contactId);
		if (cachedHeaders != null && !cachedHeaders.isEmpty()) {
			// Display cached messages immediately (0ms delay)
			// Get revision inside displayMessages to avoid race condition
			displayMessagesFromCache(cachedHeaders);
		}

		// Then load fresh data from DB in background
		runOnDbThread(() -> {
			try {
				Collection<ConversationMessageHeader> headers =
						conversationManager.getMessageHeaders(contactId);
				List<ConversationMessageHeader> sorted =
						new ArrayList<>(headers);
				sort(sorted, (a, b) ->
						Long.compare(b.getTimestamp(), a.getTimestamp()));

				// Update cache for next time
				ConversationCache.getInstance().put(contactId, sorted);

				// Only eagerly load if not already displayed from cache
				if (!sorted.isEmpty() && cachedHeaders == null) {
					ConversationMessageHeader latest = sorted.get(0);
					if (latest instanceof PrivateMessageHeader) {
						eagerlyLoadMessageSize((PrivateMessageHeader) latest);
					}
				}

				// Always display from DB to ensure we have latest data and hide spinner
				displayMessagesFromDb(sorted, cachedHeaders != null);
			} catch (NoSuchContactException e) {
				finishOnUiThread();
			} catch (DbException e) {
				handleSecurityException(e);
			}
		});
	}

	/**
	 * Display cached messages instantly without revision check.
	 * This provides immediate UI feedback while DB loads in background.
	 */
	private void displayMessagesFromCache(
			Collection<ConversationMessageHeader> headers) {
		runOnUiThreadUnlessDestroyed(() -> {
			adapter.incrementRevision();
			textInputView.setReady(true);
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

	/**
	 * Display messages from database. Always hides spinner and enables input.
	 * @param hadCache true if we already displayed cached messages
	 */
	private void displayMessagesFromDb(
			Collection<ConversationMessageHeader> headers, boolean hadCache) {
		runOnUiThreadUnlessDestroyed(() -> {
			// Always ensure input is ready when messages are loaded from DB
			textInputView.setReady(true);

			// If we had cache and content is same size, just ensure spinner is hidden
			if (hadCache && adapter.getItemCount() == headers.size()) {
				list.showData();
				return;
			}

			adapter.incrementRevision();
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

	@DatabaseExecutor
	private void eagerlyLoadMessageSize(PrivateMessageHeader h) {
		try {
			MessageId id = h.getId();
			if (h.hasText()) {
				String text = textCache.get(id);
				if (text == null) {
					text = messagingManager.getMessageText(id);
					textCache.put(id, requireNonNull(text));
				}
			}
			List<AttachmentHeader> headers = h.getAttachmentHeaders();
			if (headers.size() == 1) {
				AttachmentHeader header = headers.get(0);
				attachmentRetriever
						.cacheAttachmentItemWithSize(h.getId(), header);
			}
		} catch (DbException e) {
			handleSecurityException(e);
		}
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
		runOnDbThread(() -> {
			try {
				String text = messagingManager.getMessageText(m);
				displayMessageText(m, requireNonNull(text));
			} catch (DbException e) {
				handleSecurityException(e);
			}
		});
	}

	private void displayMessageText(MessageId m, String text) {
		runOnUiThreadUnlessDestroyed(() -> {
			textCache.put(m, text);
			Pair<Integer, ConversationMessageItem> pair =
					adapter.getMessageItem(m);
			if (pair != null) {
				if (text != null && !text.startsWith("VOICE_CALL:")) {
					boolean scroll = shouldScrollWhenUpdatingMessage();
					pair.getSecond().setText(text);
					adapter.notifyItemChanged(pair.getFirst());
					if (scroll) scrollToBottom();
				}
			}
		});
	}

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

	@Override
	public void eventOccurred(Event e) {
		if (e instanceof ContactRemovedEvent) {
			ContactRemovedEvent c = (ContactRemovedEvent) e;
			if (c.getContactId().equals(contactId)) {
				supportFinishAfterTransition();
			}
		} else if (e instanceof ConversationMessageReceivedEvent) {
			ConversationMessageReceivedEvent<?> p =
					(ConversationMessageReceivedEvent<?>) e;
			if (p.getContactId().equals(contactId)) {
				onNewConversationMessage(p.getMessageHeader());
			}
		} else if (e instanceof MessagesSentEvent) {
			MessagesSentEvent m = (MessagesSentEvent) e;
			if (m.getContactId().equals(contactId)) {
				markMessages(m.getMessageIds(), true, false);
			}
		} else if (e instanceof MessagesAckedEvent) {
			MessagesAckedEvent m = (MessagesAckedEvent) e;
			if (m.getContactId().equals(contactId)) {
				markMessages(m.getMessageIds(), true, true);
			}
		} else if (e instanceof ConversationMessagesDeletedEvent) {
			ConversationMessagesDeletedEvent m =
					(ConversationMessagesDeletedEvent) e;
			if (m.getContactId().equals(contactId)) {
				onConversationMessagesDeleted(m.getMessageIds());
			}
		} else if (e instanceof ContactConnectedEvent) {
			ContactConnectedEvent c = (ContactConnectedEvent) e;
			if (c.getContactId().equals(contactId)) {
				displayContactOnlineStatus();
			}
		} else if (e instanceof ContactDisconnectedEvent) {
			ContactDisconnectedEvent c = (ContactDisconnectedEvent) e;
			if (c.getContactId().equals(contactId)) {
				displayContactOnlineStatus();
			}
		} else if (e instanceof ClientVersionUpdatedEvent) {
			ClientVersionUpdatedEvent c = (ClientVersionUpdatedEvent) e;
			if (c.getContactId().equals(contactId)) {
				ClientId clientId = c.getClientVersion().getClientId();
				if (clientId.equals(MessagingManager.CLIENT_ID)) {
					viewModel.recheckFeaturesAndOnboarding(contactId);
				}
			}
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
		// Update cache with new message for instant load next time
		ConversationCache.getInstance().addMessage(contactId, h);

		// NOTE: Voice call signals are now handled via dedicated VOICE_SIGNAL message type
		// which routes directly to VoiceCallService via VoiceSignalReceivedEvent.
		// ConversationActivity no longer needs to intercept voice signals.
		// Legacy text-based signals (VOICE_CALL:) are handled by VoiceCallService
		// for backward compatibility.

		if (h instanceof ConversationRequest ||
				h instanceof ConversationResponse) {
			// contact name might not have been loaded
			observeOnce(viewModel.getContactDisplayName(), this,
					name -> {
						ConversationItem item = h.accept(visitor);
						if (item != null) addConversationItem(item);
					});
		} else {
			// visitor also loads message text and attachments (if existing)
			// Returns null for voice signaling messages (should be hidden)
			ConversationItem item = h.accept(visitor);
			if (item != null) addConversationItem(item);
		}
	}

	@UiThread
	private void onConversationMessagesDeleted(
			Collection<MessageId> messageIds) {
		// Invalidate cache when messages deleted
		ConversationCache.getInstance().invalidate(contactId);
		adapter.incrementRevision();
		adapter.removeItems(messageIds);
	}

	@UiThread
	private void markMessages(Collection<MessageId> messageIds, boolean sent,
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
	public void onSwipeToReply(ConversationItem item) {
		// Reply functionality - shows quoted message in input field
		if (item instanceof ConversationMessageItem) {
			ConversationMessageItem messageItem = (ConversationMessageItem) item;
			// Show reply preview in input area (works for text and media messages)
			textInputView.showReplyPreview(item);
			// Focus on text input
			textInputView.setReady(true);
			textInputView.showSoftKeyboard();
		}
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
		if (!isRecording && voiceRecorder != null) {
			isRecording = true;
			showRecordingBar();
		}
	}

	private void showRecordingBar() {
		// Hide normal text input mode
		if (textInputView != null) {
			textInputView.setVisibility(View.GONE);
		}
		// Show recording overlay with timer and send button (replaces text input)
		if (voiceRecordingOverlay != null) {
			voiceRecordingOverlay.setVisibility(View.VISIBLE);
		}
		// Start pulse animation for recording indicator
		startPulseAnimation();
		recordingStartTime = System.currentTimeMillis();

		try {
			// SECURITY: Get GroupId for AAD context before encryption starts
			org.briarproject.bramble.api.sync.GroupId groupId = viewModel.prepareVoiceRecording();
			byte[] groupIdBytes = groupId.getBytes();

			voiceRecorder.startStreamingRecording(groupIdBytes, new com.professor.zerion.android.conversation.voice.EncryptedChunkCallback() {
				@Override
				public void onRecordingStarted() {
					// Silent operation
				}

				@Override
				public void onEncryptionInit(byte[] iv, byte[] sessionKey) {
					// CRITICAL: Copy arrays before passing to ViewModel to avoid race condition
					// The ViewModel schedules work on a background thread, so we must copy first
					byte[] ivCopy = java.util.Arrays.copyOf(iv, iv.length);
					byte[] sessionKeyCopy = java.util.Arrays.copyOf(sessionKey, sessionKey.length);
					viewModel.onEncryptionInit(ivCopy, sessionKeyCopy);
					// Now safe to zeroize the original arrays
					java.util.Arrays.fill(iv, (byte) 0);
					java.util.Arrays.fill(sessionKey, (byte) 0);
				}

				@Override
				public void onEncryptedChunk(byte[] encrypted, int len, byte[] tagPart) {
					// CRITICAL: Copy arrays before passing to ViewModel to avoid race condition
					byte[] encryptedCopy = java.util.Arrays.copyOf(encrypted, encrypted.length);
					byte[] tagCopy = java.util.Arrays.copyOf(tagPart, tagPart.length);
					viewModel.appendEncryptedAudioChunk(encryptedCopy, len, tagCopy);
					// Now safe to zeroize the original arrays
					java.util.Arrays.fill(encrypted, (byte) 0);
					java.util.Arrays.fill(tagPart, (byte) 0);
				}

				@Override
				public void onEncryptionFinal(byte[] globalMAC, int totalDurationMs, int chunkCount) {
					// CRITICAL: Copy array before passing to ViewModel to avoid race condition
					byte[] globalMACCopy = java.util.Arrays.copyOf(globalMAC, globalMAC.length);
					viewModel.finalizeEncryptedVoiceMessage(globalMACCopy, chunkCount);
					// Now safe to zeroize the original array
					java.util.Arrays.fill(globalMAC, (byte) 0);
					runOnUiThread(() -> {
						isRecording = false;
						hideRecordingBar();
					});
				}

				@Override
				public void onRecordingProgress(int durationMs, int amplitudeDb) {
					runOnUiThread(() -> {
						// Update timer display
						int seconds = durationMs / 1000;
						int minutes = seconds / 60;
						int secs = seconds % 60;
						if (recordingTimer != null) {
							recordingTimer.setText(String.format("%d:%02d", minutes, secs));
						}
					});
				}

				@Override
				public void onError(Exception e) {
					runOnUiThread(() -> {
						handleSecurityException(e);
						isRecording = false;
						hideRecordingBar();
					});
				}

				@Override
				public void onCancelled() {
					runOnUiThread(() -> {
						isRecording = false;
						hideRecordingBar();
					});
				}
			});
		} catch (Exception e) {
			handleSecurityException(e);
			hideRecordingBar();
			isRecording = false;
		}
	}

	private void hideRecordingBar() {
		// Hide recording overlay
		if (voiceRecordingOverlay != null) {
			voiceRecordingOverlay.setVisibility(View.GONE);
		}
		// Show normal text input mode again
		if (textInputView != null) {
			textInputView.setVisibility(View.VISIBLE);
		}
		// Stop pulse animation
		stopPulseAnimation();
		// Reset timer display
		if (recordingTimer != null) {
			recordingTimer.setText("0:00");
		}
	}

	private void stopVoiceRecording() {
		if (voiceRecorder != null && isRecording) {
			try {
				// User cancelled - cleanup crypto material
				voiceRecorder.cancelStreamingRecording();
				viewModel.cancelVoiceRecording();  // SECURITY: Zeroize all crypto material
			} catch (Exception e) {
				handleSecurityException(e);
			}
			isRecording = false;
			hideRecordingBar();
		}
	}

	private void finishVoiceRecording() {
		if (voiceRecorder != null && isRecording) {
			try {
				// User clicked send button - finalize recording
				voiceRecorder.stopStreamingRecording();
			} catch (Exception e) {
				handleSecurityException(e);
			}
			// isRecording will be set to false in onEncryptionFinal callback
		}
	}

	private void startPulseAnimation() {
		if (recordingPulse != null) {
			pulseAnimator = android.animation.ValueAnimator.ofFloat(1.0f, 0.3f);
			pulseAnimator.setDuration(800);
			pulseAnimator.setRepeatMode(android.animation.ValueAnimator.REVERSE);
			pulseAnimator.setRepeatCount(android.animation.ValueAnimator.INFINITE);
			pulseAnimator.addUpdateListener(animation -> {
				float alpha = (float) animation.getAnimatedValue();
				recordingPulse.setAlpha(alpha);
			});
			pulseAnimator.start();
		}
	}

	private void stopPulseAnimation() {
		if (pulseAnimator != null) {
			pulseAnimator.cancel();
			pulseAnimator = null;
		}
		if (recordingPulse != null) {
			recordingPulse.setAlpha(1.0f);
		}
	}

	private void askToClearChat() {
		new MaterialAlertDialogBuilder(this)
				.setTitle("Clear Chat")
				.setMessage("Delete all messages in this conversation?")
				.setPositiveButton("Clear", (dialog, which) -> {
					runOnDbThread(() -> {
						try {
							Collection<ConversationMessageHeader> headers =
									conversationManager.getMessageHeaders(contactId);
							List<MessageId> ids = new ArrayList<>();
							for (ConversationMessageHeader h : headers) {
								ids.add(h.getId());
							}
							conversationManager.deleteMessages(contactId, ids);
							runOnUiThread(() -> adapter.clear());
						} catch (DbException e) {
							handleSecurityException(e);
						}
					});
				})
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
					runOnDbThread(() -> {
						try {
							contactManager.removeContact(contactId);
							finishOnUiThread();
						} catch (DbException e) {
							handleSecurityException(e);
						}
					});
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
					runOnDbThread(() -> {
						try {
							conversationManager.deleteMessages(contactId, selected);
						} catch (DbException e) {
							handleSecurityException(e);
						}
					});
					if (actionMode != null) actionMode.finish();
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
		runOnDbThread(() -> {
			try {
				// Cache the text first
				if (h.hasText()) {
					String text = messagingManager.getMessageText(h.getId());
					textCache.put(h.getId(), requireNonNull(text));
				}

				// Convert header to ConversationItem and add to adapter
				ConversationItem item = h.accept(visitor);
				if (item != null) {
					runOnUiThreadUnlessDestroyed(() -> {
						adapter.add(item);
						scrollToBottom();
					});
				}
			} catch (DbException e) {
				handleSecurityException(e);
			}
		});
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
		// Respond to introduction or group invitation request
	}

	@Override
	public void openRequestedShareable(ConversationRequestItem item) {
		// Open shareable based on request type (introduction or group invitation)
		// This would launch the appropriate activity
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
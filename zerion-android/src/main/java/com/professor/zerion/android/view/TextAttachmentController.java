package com.professor.zerion.android.view;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import android.widget.Toast;

import com.professor.zerion.R;
import com.professor.zerion.android.attachment.AttachmentItemResult;
import com.professor.zerion.android.attachment.AttachmentManager;
import com.professor.zerion.android.attachment.AttachmentResult;
import com.professor.zerion.android.view.ImagePreview.ImagePreviewListener;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import androidx.activity.result.ActivityResultLauncher;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AlertDialog.Builder;
import androidx.customview.view.AbsSavedState;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat;
import uk.co.samuelwall.materialtaptargetprompt.MaterialTapTargetPrompt;

import static android.view.View.GONE;
import static android.widget.Toast.LENGTH_LONG;
import static androidx.core.content.ContextCompat.getColor;
import static androidx.customview.view.AbsSavedState.EMPTY_STATE;
import static androidx.lifecycle.Lifecycle.State.DESTROYED;
import static com.professor.zerion.android.util.UiUtils.resolveColorAttribute;
import static com.professor.zerion.android.view.TextSendController.SendState.SENT;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import static org.briarproject.briar.api.messaging.MessagingConstants.MAX_ATTACHMENTS_PER_MESSAGE;

@UiThread
@NotNullByDefault
public class TextAttachmentController extends TextSendController
		implements ImagePreviewListener {

	private final ImagePreview imagePreview;
	private final AttachmentListener attachmentListener;
	private final CompositeSendButton sendButton;
	private final AttachmentManager attachmentManager;

	private final List<Uri> imageUris = new ArrayList<>();
	private boolean loadingUris = false;

	public TextAttachmentController(TextInputView v, ImagePreview imagePreview,
			AttachmentListener attachmentListener,
			AttachmentManager attachmentManager) {
		super(v, attachmentListener, false);
		this.attachmentListener = attachmentListener;
		this.imagePreview = imagePreview;
		this.attachmentManager = attachmentManager;
		this.imagePreview.setImagePreviewListener(this);

		sendButton = (CompositeSendButton) compositeSendButton;
		sendButton.setOnAttachmentClickListener(view -> onImageButtonClicked());

		// Long-press on send button to show disappearing messages timer selection
		sendButton.setOnSendLongClickListener(view -> {
			showDisappearingTimerPopup();
			return true;
		});
	}

	public void setOnAttachmentClickListener(android.view.View.OnClickListener listener) {
		sendButton.setOnAttachmentClickListener(listener);
	}

	public void setOnVoiceClickListener(android.view.View.OnClickListener listener) {
		sendButton.setOnVoiceClickListener(listener);
	}

	@Override
	protected void updateViewState() {
		super.updateViewState();
		if (loadingUris) {
			sendButton.showProgress(true);
		} else if (imageUris.isEmpty()) {
			sendButton.showProgress(false);
			sendButton.showImageButton(textIsEmpty, isSendButtonEnabled());
		} else {
			sendButton.showProgress(false);
			sendButton.showImageButton(false, isSendButtonEnabled());
		}
	}

	@Override
	protected boolean isTextInputEnabled() {
		return super.isTextInputEnabled() && !loadingUris;
	}

	@Override
	protected boolean isSendButtonEnabled() {
		return super.isSendButtonEnabled() && !loadingUris;
	}

	@Override
	protected boolean isBombVisible() {
		// Show bomb badge if either:
		// 1. The conversation has disappearing messages enabled (super.isBombVisible())
		// 2. A one-time timer was selected via long-press (expectedTimer != NO_AUTO_DELETE_TIMER)
		boolean hasOneTimeTimer = expectedTimer != -1L; // NO_AUTO_DELETE_TIMER = -1
		return (super.isBombVisible() || hasOneTimeTimer) && (!textIsEmpty || !imageUris.isEmpty());
	}

	@Override
	protected CharSequence getCurrentTextHint() {
		if (imageUris.isEmpty()) {
			return super.getCurrentTextHint();
		} else {
			Context ctx = textInput.getContext();
			return ctx.getString(R.string.image_caption_hint);
		}
	}

	@Override
	public void onSendEvent() {
		if (canSend()) {
			if (loadingUris) throw new AssertionError();
			listener.onSendClick(textInput.getText(),
					attachmentManager.getAttachmentHeadersForSending(),
					expectedTimer).observe(listener, this::onSendStateChanged);
		}
	}

	@Override
	protected void onSendStateChanged(SendState sendState) {
		super.onSendStateChanged(sendState);
		if (sendState == SENT) {
			expectedTimer = NO_AUTO_DELETE_TIMER;
			reset();
		}
	}

	@Override
	protected boolean canSendEmptyText() {
		return !imageUris.isEmpty();
	}

	public void setImagesSupported() {
		sendButton.setImagesSupported();
	}

	/**
	 * Shows a popup menu to select a disappearing message timer for one-time use.
	 * This allows users to send a message with a specific timer without changing
	 * the conversation's default timer setting.
	 */
	private void showDisappearingTimerPopup() {
		Context ctx = textInput.getContext();
		String[] timerOptions = {
			ctx.getString(R.string.off),
			"30 " + ctx.getString(R.string.seconds_short),
			"5 " + ctx.getString(R.string.minutes_short),
			"30 " + ctx.getString(R.string.minutes_short),
			"1 " + ctx.getString(R.string.hour_short),
			"8 " + ctx.getString(R.string.hours_short),
			"12 " + ctx.getString(R.string.hours_short),
			"24 " + ctx.getString(R.string.hours_short),
			"1 " + ctx.getString(R.string.week_short),
			"4 " + ctx.getString(R.string.weeks_short)
		};

		long[] timerValues = {
			-1L, // NO_AUTO_DELETE_TIMER
			30 * 1000L,
			5 * 60 * 1000L,
			30 * 60 * 1000L,
			60 * 60 * 1000L,
			8 * 60 * 60 * 1000L,
			12 * 60 * 60 * 1000L,
			24 * 60 * 60 * 1000L,
			7 * 24 * 60 * 60 * 1000L,
			4 * 7 * 24 * 60 * 60 * 1000L
		};

		new Builder(ctx, R.style.ZerionDialogTheme)
			.setTitle(R.string.disappearing_message_timer_title)
			.setItems(timerOptions, (dialog, which) -> {
				expectedTimer = timerValues[which];
				// Show toast to confirm selection
				String message = which == 0 ?
					ctx.getString(R.string.disappearing_timer_off) :
					ctx.getString(R.string.disappearing_timer_set, timerOptions[which]);
				Toast.makeText(ctx, message, Toast.LENGTH_SHORT).show();
				updateViewState();
			})
			.show();
	}

	private void onImageButtonClicked() {
		if (!sendButton.hasImageSupport()) {
			Context ctx = imagePreview.getContext();
			Builder builder = new Builder(ctx, R.style.OnboardingDialogTheme);
			builder.setTitle(
					ctx.getString(R.string.dialog_title_no_image_support));
			builder.setMessage(
					ctx.getString(R.string.dialog_message_no_image_support));
			builder.setPositiveButton(R.string.got_it, null);
			builder.show();
			return;
		}
		if (attachmentListener.getLifecycle().getCurrentState() != DESTROYED) {
			attachmentListener.onAttachImageClicked();
		}
	}

	@SuppressWarnings("JavadocReference")
	public void onImageReceived(@Nullable List<Uri> newUris) {
		if (newUris == null) return;
		if (loadingUris || !imageUris.isEmpty()) throw new AssertionError();
		onNewUris(false, newUris);
	}

	private void onNewUris(boolean restart, List<Uri> newUris) {
		if (newUris.isEmpty()) return;
		if (loadingUris) throw new AssertionError();
		if (textIsEmpty) onStartingMessage();
		loadingUris = true;
		if (newUris.size() > MAX_ATTACHMENTS_PER_MESSAGE) {
			newUris = newUris.subList(0, MAX_ATTACHMENTS_PER_MESSAGE);
			attachmentListener.onTooManyAttachments();
		}
		imageUris.addAll(newUris);
		updateViewState();
		List<ImagePreviewItem> items = ImagePreviewItem.fromUris(imageUris);
		imagePreview.showPreview(items);
		LiveData<AttachmentResult> result =
				attachmentManager.storeAttachments(imageUris, restart);
		result.observe(attachmentListener, new Observer<AttachmentResult>() {
			@Override
			public void onChanged(@Nullable AttachmentResult attachmentResult) {
				if (attachmentResult == null) {
					result.removeObserver(this);
				} else {
					boolean noError = onNewAttachmentItemResults(
							attachmentResult.getItemResults());
					if (noError && attachmentResult.isFinished()) {
						onAllAttachmentsCreated();
						result.removeObserver(this);
					}
				}
			}
		});
	}

	private boolean onNewAttachmentItemResults(
			Collection<AttachmentItemResult> itemResults) {
		if (!loadingUris) throw new AssertionError();
		for (AttachmentItemResult result : itemResults) {
			if (result.hasError()) {
				onError(result.getErrorMsg());
				return false;
			} else {
				imagePreview.loadPreviewImage(result);
			}
		}
		return true;
	}

	private void onAllAttachmentsCreated() {
		if (!loadingUris) throw new AssertionError();
		loadingUris = false;
		updateViewState();
	}

	private void reset() {
		imagePreview.setVisibility(GONE);
		imageUris.clear();
		loadingUris = false;
		updateViewState();
	}

	@Override
	public Parcelable onSaveInstanceState(@Nullable Parcelable superState) {
		SavedState state =
				new SavedState(superState == null ? EMPTY_STATE : superState);
		state.imageUris = imageUris;
		return state;
	}

	@Override
	@Nullable
	public Parcelable onRestoreInstanceState(Parcelable inState) {
		SavedState state = (SavedState) inState;
		if (!imageUris.isEmpty()) throw new AssertionError();
		if (state.imageUris != null) onNewUris(true, state.imageUris);
		return state.getSuperState();
	}

	@UiThread
	private void onError(@Nullable String errorMsg) {
		if (errorMsg == null) {
			errorMsg = imagePreview.getContext()
					.getString(R.string.image_attach_error);
		}
		Toast.makeText(textInput.getContext(), errorMsg, LENGTH_LONG).show();
		onCancel();
	}

	@Override
	public void onCancel() {
		textInput.clearText();
		attachmentManager.cancel();
		reset();
	}

	public void showImageOnboarding(Activity activity) {
		int color = resolveColorAttribute(activity, R.attr.colorControlNormal);
		Drawable drawable = VectorDrawableCompat
				.create(activity.getResources(), R.drawable.ic_image, null);
		new MaterialTapTargetPrompt.Builder(activity,
				R.style.OnboardingDialogTheme).setTarget(sendButton)
				.setPrimaryText(R.string.dialog_title_image_support)
				.setSecondaryText(R.string.dialog_message_image_support)
				.setBackgroundColour(getColor(activity, R.color.zerion_primary))
				.setIconDrawable(drawable)
				.setIconDrawableColourFilter(color)
				.show();
	}

	private static class SavedState extends AbsSavedState {

		@Nullable
		private List<Uri> imageUris;

		private SavedState(Parcelable superState) {
			super(superState);
		}

		private SavedState(Parcel in) {
			super(in);
			imageUris = in.readArrayList(Uri.class.getClassLoader());
		}

		@Override
		public void writeToParcel(Parcel out, int flags) {
			super.writeToParcel(out, flags);
			out.writeList(imageUris);
		}

		public static final Creator<SavedState> CREATOR =
				new Creator<SavedState>() {
					@Override
					public SavedState createFromParcel(Parcel in) {
						return new SavedState(in);
					}

					@Override
					public SavedState[] newArray(int size) {
						return new SavedState[size];
					}
				};
	}

	@UiThread
	public interface AttachmentListener extends SendListener {

		void onAttachImageClicked();

		void onTooManyAttachments();
	}
}

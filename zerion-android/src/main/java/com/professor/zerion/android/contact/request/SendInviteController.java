package com.professor.zerion.android.contact.request;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.contact.OwnLinkChangedException;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.InviteLink;
import org.zerionproject.core.api.contact.request.OutgoingRequestLimitException;
import org.zerionproject.core.api.db.ContactExistsException;
import org.zerionproject.core.api.db.DbException;
import org.zerionproject.core.api.db.PendingContactExistsException;
import org.zerionproject.core.api.identity.IdentityManager;

import java.security.GeneralSecurityException;
import java.util.concurrent.Executor;

import javax.annotation.Nullable;

import androidx.annotation.StringRes;
import androidx.annotation.UiThread;

import static android.widget.Toast.LENGTH_LONG;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_NOTE_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_OPEN_OUTGOING;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ZERO_ID_OPEN_MS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_NOTE_CODE_POINTS;
import static org.zerionproject.core.util.StringUtils.toUtf8;

@NotNullByDefault
public class SendInviteController {

	public interface Listener {

		void onRequestSaved();

		void onMutualAdded();

		void onScanChosen();

		void onInviteLinksChosen();
	}

	public enum Mode {
		NONE, REQUEST, MUTUAL_V1
	}

	private final Activity activity;
	private final View root;
	private final ContactRequestManager requests;
	private final IdentityManager identity;
	private final Executor db;
	private final Listener listener;

	private final TextInputLayout linkLayout, aliasLayout, shownNameLayout,
			noteLayout;
	private final EditText linkInput, aliasInput, shownNameInput, noteInput;
	private final TextView notice, zeroIdView;
	private final Button sendButton, shareButton, copyButton, qrButton;

	private Mode mode = Mode.NONE;
	private boolean reAdd = false;
	private boolean hasLink = false;
	private int generation = 0;
	private boolean busy = false;
	@Nullable
	private String zeroId;
	@Nullable
	private String sharedZeroId;

	public SendInviteController(Activity activity, View root,
			ContactRequestManager requests, IdentityManager identity,
			Executor db, Listener listener) {
		this.activity = activity;
		this.root = root;
		this.requests = requests;
		this.identity = identity;
		this.db = db;
		this.listener = listener;
		linkLayout = root.findViewById(R.id.inviteLinkLayout);
		linkInput = root.findViewById(R.id.inviteLinkInput);
		aliasLayout = root.findViewById(R.id.inviteAliasLayout);
		aliasInput = root.findViewById(R.id.inviteAliasInput);
		shownNameLayout = root.findViewById(R.id.inviteShownNameLayout);
		shownNameInput = root.findViewById(R.id.inviteShownNameInput);
		noteLayout = root.findViewById(R.id.inviteNoteLayout);
		noteInput = root.findViewById(R.id.inviteNoteInput);
		notice = root.findViewById(R.id.inviteNotice);
		zeroIdView = root.findViewById(R.id.zeroIdView);
		sendButton = root.findViewById(R.id.inviteSendButton);
		shareButton = root.findViewById(R.id.zeroIdShareButton);
		copyButton = root.findViewById(R.id.zeroIdCopyButton);
		qrButton = root.findViewById(R.id.zeroIdQrButton);

		linkInput.addTextChangedListener(new TextWatcher() {
			@Override
			public void beforeTextChanged(CharSequence s, int start,
					int count, int after) {
			}

			@Override
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {
			}

			@Override
			public void afterTextChanged(Editable s) {
				onLinkChanged(s.toString());
			}
		});
		root.findViewById(R.id.invitePasteButton).setOnClickListener(
				v -> paste());
		root.findViewById(R.id.inviteScanButton).setOnClickListener(
				v -> listener.onScanChosen());
		root.findViewById(R.id.inviteLinksButton).setOnClickListener(
				v -> listener.onInviteLinksChosen());
		sendButton.setOnClickListener(v -> onSendClicked());
		shareButton.setOnClickListener(v -> shareZeroId(LinkSharing::share));
		copyButton.setOnClickListener(v -> shareZeroId(LinkSharing::copy));
		qrButton.setOnClickListener(v -> shareZeroId(LinkSharing::showQr));
		noteLayout.setLengthCounter(SendInviteController::codePoints);
		noteLayout.setCounterMaxLength(MAX_NOTE_CODE_POINTS);
		((TextView) root.findViewById(R.id.zeroIdInfo)).setText(
				activity.getString(R.string.zero_id_info, zeroIdDays()));
		applyMode(Mode.NONE);
	}

	static int codePoints(@Nullable Editable text) {
		if (text == null) return 0;
		return Character.codePointCount(text, 0, text.length());
	}

	@UiThread
	public void start() {
		RestorePausePrompt.bindBanner(activity,
				root.findViewById(R.id.invitesPausedBanner), requests, db);
		db.execute(() -> {
			String link = null;
			String name = null;
			try {
				link = requests.getZeroIdLink();
				name = identity.getLocalAuthor().getName();
			} catch (DbException e) {
			}
			String finalLink = link;
			String finalName = name;
			activity.runOnUiThread(() -> onLoaded(finalLink, finalName));
		});
	}

	public void setLink(String link) {
		linkInput.setText(link);
	}

	@UiThread
	public Mode getMode() {
		return mode;
	}

	@UiThread
	private void onLoaded(@Nullable String link, @Nullable String name) {
		if (link != null) {
			zeroId = link;
			zeroIdView.setText(link);
			shareButton.setEnabled(true);
			copyButton.setEnabled(true);
			qrButton.setEnabled(true);
		}
		if (name != null && isEmpty(shownNameInput)) {
			shownNameInput.setText(name);
		}
	}

	private interface Share {
		void run(Activity activity, String link);
	}

	@UiThread
	private void shareZeroId(Share action) {
		db.execute(() -> {
			String link;
			try {
				link = requests.getZeroIdLink();
				requests.zeroIdShared();
			} catch (DbException e) {
				return;
			}
			activity.runOnUiThread(() -> {
				sharedZeroId = link;
				onLoaded(link, null);
				action.run(activity, link);
			});
		});
	}

	@UiThread
	private void paste() {
		ClipboardManager clipboard = (ClipboardManager)
				activity.getSystemService(Context.CLIPBOARD_SERVICE);
		if (clipboard == null) return;
		ClipData clip = clipboard.getPrimaryClip();
		if (clip != null && clip.getItemCount() > 0) {
			CharSequence text = clip.getItemAt(0).getText();
			if (text != null) linkInput.setText(text);
		}
	}

	@UiThread
	private void onLinkChanged(String text) {
		int gen = ++generation;
		linkLayout.setError(null);
		String link = ScannedCode.extractLink(text);
		hasLink = link != null;
		if (link == null) {
			applyMode(Mode.NONE);
			if (!text.trim().isEmpty()) {
				linkLayout.setError(activity.getString(R.string.invalid_link));
			}
			return;
		}
		db.execute(() -> {
			Mode m;
			int error = 0;
			try {
				InviteLink parsed = requests.parseLink(link);
				if (parsed.getFlow() == InviteLink.Flow.MUTUAL_CLASSICAL) {
					m = Mode.NONE;
					error = R.string.send_invite_classical_link;
				} else {
					m = modeOf(parsed);
				}
			} catch (FormatException e) {
				m = Mode.NONE;
				error = LinkErrors.messageFor(e);
			} catch (DbException e) {
				m = Mode.NONE;
			}
			Mode finalMode = m;
			int finalError = error;
			activity.runOnUiThread(() -> {
				if (gen != generation) return;
				applyMode(finalMode);
				if (finalError != 0) {
					linkLayout.setError(activity.getString(finalError));
				}
			});
		});
	}

	private static Mode modeOf(InviteLink parsed) {
		switch (parsed.getFlow()) {
			case REQUEST:
				return Mode.REQUEST;
			case MUTUAL_V1:
				return Mode.MUTUAL_V1;
			default:
				return Mode.NONE;
		}
	}

	/**
	 * The screen was opened from Re-add contact: a request sent from it
	 * tells the receiver that the sender is already their contact, and a
	 * pasted link pairs as a re-add, so the pairing may replace an existing
	 * contact's keys.
	 */
	@UiThread
	public void setReAdd(boolean reAdd) {
		this.reAdd = reAdd;
		applyMode(mode);
	}

	@UiThread
	private void applyMode(Mode m) {
		mode = m;
		boolean request = m == Mode.REQUEST || m == Mode.NONE;
		int requestOnly = request && hasLink ? View.VISIBLE : View.GONE;
		aliasLayout.setVisibility(hasLink ? View.VISIBLE : View.GONE);
		shownNameLayout.setVisibility(requestOnly);
		noteLayout.setVisibility(requestOnly);
		if (m == Mode.MUTUAL_V1) {
			notice.setText(R.string.send_invite_old_link_notice);
			notice.setVisibility(View.VISIBLE);
		} else if (reAdd && hasLink) {
			notice.setText(R.string.send_invite_reconnect_notice);
			notice.setVisibility(View.VISIBLE);
		} else {
			notice.setText(null);
			notice.setVisibility(View.GONE);
		}
		sendButton.setText(request ? R.string.send_invite_send_button
				: R.string.send_invite_add_button);
	}

	@UiThread
	private void onSendClicked() {
		if (busy) return;
		String link = ScannedCode.extractLink(linkInput.getText());
		if (link == null) {
			linkLayout.setError(activity.getString(isEmpty(linkInput)
					? R.string.missing_link : R.string.invalid_link));
			linkInput.requestFocus();
			return;
		}
		String alias = ContactNames.clean(aliasInput.getText());
		if (!check(aliasLayout, ContactNames.problem(alias,
				R.string.send_invite_alias_missing))) {
			return;
		}
		String shownName = ContactNames.clean(shownNameInput.getText());
		String note = noteOf(noteInput.getText());
		if (mode == Mode.REQUEST || mode == Mode.NONE) {
			if (!check(shownNameLayout, ContactNames.problem(shownName,
					R.string.send_invite_shown_name_missing))) {
				return;
			}
			if (!check(noteLayout, noteProblem(note))) return;
		}
		busy = true;
		sendButton.setEnabled(false);
		String shared = sharedZeroId;
		boolean reconnect = reAdd;
		db.execute(() -> {
			Runnable result;
			try {
				InviteLink parsed = requests.parseLink(link);
				switch (parsed.getFlow()) {
					case REQUEST:
						requests.sendRequest(link, alias, shownName, note,
								reconnect);
						boolean paused = requests.isPausedAfterRestore();
						result = () -> onRequestSaved(paused);
						break;
					case MUTUAL_V1:
						requests.addMutualFromInvite(link, alias, shared,
								reconnect);
						result = listener::onMutualAdded;
						break;
					default:
						result = () -> {
							done();
							linkLayout.setError(activity.getString(
									R.string.send_invite_classical_link));
						};
						break;
				}
			} catch (OutgoingRequestLimitException e) {
				result = () -> fail(activity.getString(
						R.string.send_invite_too_many, MAX_OPEN_OUTGOING));
			} catch (OwnLinkChangedException e) {
				result = () -> fail(R.string.send_invite_zero_id_changed);
			} catch (ContactExistsException | PendingContactExistsException e) {
				result = () -> fail(R.string.contact_already_exists_general);
			} catch (FormatException e) {
				int res = LinkErrors.messageFor(e);
				result = () -> {
					done();
					linkLayout.setError(activity.getString(res));
				};
			} catch (DbException | GeneralSecurityException
					| IllegalArgumentException | IllegalStateException e) {
				result = () -> fail(R.string.adding_contact_error);
			}
			activity.runOnUiThread(result);
		});
	}

	@UiThread
	private void onRequestSaved(boolean paused) {
		done();
		Toast.makeText(activity, paused ? R.string.send_invite_sent_paused
				: R.string.send_invite_sent, LENGTH_LONG).show();
		listener.onRequestSaved();
	}

	@UiThread
	private void fail(@StringRes int res) {
		fail(activity.getString(res));
	}

	@UiThread
	private void fail(String text) {
		done();
		Toast.makeText(activity, text, LENGTH_LONG).show();
	}

	static long zeroIdDays() {
		return ZERO_ID_OPEN_MS / DAYS.toMillis(1);
	}

	@UiThread
	private void done() {
		busy = false;
		sendButton.setEnabled(true);
	}

	private boolean check(TextInputLayout layout, @StringRes int problem) {
		if (problem == 0) {
			layout.setError(null);
			return true;
		}
		layout.setError(activity.getString(problem));
		if (layout.getEditText() != null) layout.getEditText().requestFocus();
		return false;
	}

	@Nullable
	static String noteOf(@Nullable CharSequence text) {
		if (text == null) return null;
		String s = text.toString().trim();
		return s.isEmpty() ? null : s;
	}

	@StringRes
	static int noteProblem(@Nullable String note) {
		if (note == null) return 0;
		if (note.codePointCount(0, note.length()) > MAX_NOTE_CODE_POINTS
				|| toUtf8(note).length > MAX_NOTE_BYTES) {
			return R.string.send_invite_note_too_long;
		}
		return 0;
	}

	private static boolean isEmpty(EditText e) {
		return e.getText() == null || e.getText().toString().trim().isEmpty();
	}
}

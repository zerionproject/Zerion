package com.professor.zerion.android.contact.request;

import android.app.Activity;
import android.content.res.Resources;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactLinkInfo;
import org.zerionproject.core.api.contact.request.ContactLinkLimitException;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.FloodNotice;
import org.zerionproject.core.api.db.DbException;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executor;

import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AlertDialog;

import static android.widget.Toast.LENGTH_LONG;
import static java.util.concurrent.TimeUnit.DAYS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_LABEL_CODE_POINTS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.MAX_PERMANENT_LINKS;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ZERO_ID_OPEN_MS;

@NotNullByDefault
public class InviteLinksController {

	static final long NEARLY_EXPIRED_MS = DAYS.toMillis(3);

	private static final class Blocked {

		private final byte[] linkId, senderTag;
		private final String via;

		private Blocked(byte[] linkId, byte[] senderTag, String via) {
			this.linkId = linkId;
			this.senderTag = senderTag;
			this.via = via;
		}
	}

	private interface DbTask {
		void run() throws DbException;
	}

	private interface Share {
		void run(Activity activity, String link);
	}

	private final Activity activity;
	private final ContactRequestManager requests;
	private final Executor db;
	private final ViewGroup linksContainer, blockedContainer, linkNotices,
			zeroIdNotices;
	private final View blockedSection, keepReceiving, keepReceivingQuiet;
	private final TextView linksEmpty, banner, zeroIdWindow;

	private List<ContactLinkInfo> permanent = Collections.emptyList();

	public InviteLinksController(Activity activity, View root,
			ContactRequestManager requests, Executor db) {
		this.activity = activity;
		this.requests = requests;
		this.db = db;
		linksContainer = root.findViewById(R.id.inviteLinksContainer);
		blockedContainer = root.findViewById(R.id.blockedContainer);
		blockedSection = root.findViewById(R.id.blockedSection);
		linkNotices = root.findViewById(R.id.inviteLinkNotices);
		zeroIdNotices = root.findViewById(R.id.zeroIdNotices);
		linksEmpty = root.findViewById(R.id.inviteLinksEmpty);
		root.findViewById(R.id.inviteLinksAdd).setOnClickListener(
				v -> onAddClicked());
		root.findViewById(R.id.zeroIdResetButton).setOnClickListener(
				v -> confirmReset());
		zeroIdWindow = root.findViewById(R.id.zeroIdWindow);
		keepReceiving = root.findViewById(R.id.zeroIdKeepReceiving);
		keepReceivingQuiet = root.findViewById(R.id.zeroIdKeepReceivingQuiet);
		keepReceiving.setOnClickListener(v -> run(requests::zeroIdShared));
		keepReceivingQuiet.setOnClickListener(
				v -> run(requests::zeroIdShared));
		banner = root.findViewById(R.id.invitesPausedBanner);
		((TextView) root.findViewById(R.id.zeroIdMoreText)).setText(
				activity.getString(R.string.zero_id_more_text,
						SendInviteController.zeroIdDays()));
		expander(root.findViewById(R.id.inviteLinksMoreInfo),
				root.findViewById(R.id.inviteLinksLimits));
		expander(root.findViewById(R.id.zeroIdMoreInfo),
				root.findViewById(R.id.zeroIdMoreText));
	}

	static void expander(TextView toggle, View content) {
		toggle.setOnClickListener(v -> {
			boolean open = content.getVisibility() != View.VISIBLE;
			content.setVisibility(open ? View.VISIBLE : View.GONE);
			toggle.setText(open ? R.string.contact_less_info
					: R.string.contact_more_info);
		});
	}

	static boolean keepReceivingIsMain(long lastShared, long now) {
		if (lastShared <= 0) return true;
		long until = lastShared + ZERO_ID_OPEN_MS;
		return until - now < NEARLY_EXPIRED_MS;
	}

	static String counts(Resources res, int undecided, int blocked) {
		List<String> parts = new ArrayList<>();
		if (undecided > 0) {
			parts.add(res.getQuantityString(
					R.plurals.invite_links_request_count, undecided,
					undecided));
		}
		if (blocked > 0) {
			parts.add(res.getQuantityString(
					R.plurals.invite_links_blocked_count, blocked, blocked));
		}
		return TextUtils.join(", ", parts);
	}

	@UiThread
	public void reload() {
		RestorePausePrompt.bindBanner(activity, banner, requests, db);
		db.execute(() -> {
			List<ContactLinkInfo> links;
			List<Blocked> blocked = new ArrayList<>();
			List<FloodNotice> notices;
			long shared = 0;
			try {
				notices = requests.getFloodNotices();
				links = new ArrayList<>();
				for (ContactLinkInfo l : requests.getLinks()) {
					if (l.getState() != ContactLink.STATE_ACTIVE) continue;
					if (l.getKind() == ContactLink.KIND_ZERO_ID) {
						shared = Math.max(shared, l.getLastSharedAt());
					}
					String via = l.getKind() == ContactLink.KIND_PERMANENT
							&& l.getLabel() != null
							? activity.getString(R.string.requests_via_link,
							l.getLabel())
							: activity.getString(R.string.requests_via_zero_id);
					for (byte[] tag : requests.getBlockedSenders(
							l.getLinkId())) {
						blocked.add(new Blocked(l.getLinkId(), tag, via));
					}
					if (l.getKind() == ContactLink.KIND_PERMANENT) links.add(l);
				}
			} catch (DbException e) {
				return;
			}
			List<ContactLinkInfo> finalLinks = links;
			long lastShared = shared;
			activity.runOnUiThread(() -> {
				render(finalLinks, blocked);
				renderNotices(notices);
				showWindow(lastShared);
			});
		});
	}

	@UiThread
	private void render(List<ContactLinkInfo> links, List<Blocked> blocked) {
		permanent = links;
		linksContainer.removeAllViews();
		LayoutInflater inflater = LayoutInflater.from(activity);
		for (ContactLinkInfo l : links) {
			View row = inflater.inflate(R.layout.list_item_invite_link,
					linksContainer, false);
			bindLink(row, l);
			linksContainer.addView(row);
		}
		linksEmpty.setVisibility(links.isEmpty() ? View.VISIBLE : View.GONE);
		blockedContainer.removeAllViews();
		int n = 0;
		for (Blocked b : blocked) {
			View row = inflater.inflate(R.layout.list_item_blocked_sender,
					blockedContainer, false);
			((TextView) row.findViewById(R.id.blockedSenderText)).setText(
					activity.getString(R.string.blocked_sender_row, ++n,
							b.via));
			row.findViewById(R.id.blockedSenderUnblock).setOnClickListener(
					v -> run(() -> requests.unblock(b.linkId, b.senderTag)));
			blockedContainer.addView(row);
		}
		blockedSection.setVisibility(blocked.isEmpty() ? View.GONE
				: View.VISIBLE);
	}

	@UiThread
	private void renderNotices(List<FloodNotice> notices) {
		linkNotices.removeAllViews();
		zeroIdNotices.removeAllViews();
		LayoutInflater inflater = LayoutInflater.from(activity);
		for (FloodNotice n : notices) {
			if (n.getKind() != ContactLink.KIND_ZERO_ID) continue;
			View row = inflater.inflate(R.layout.list_item_link_notice,
					zeroIdNotices, false);
			row.findViewById(R.id.linkNoticeLabel).setVisibility(View.GONE);
			String date = DateUtils.formatDateTime(activity, n.getAt(),
					DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR);
			((TextView) row.findViewById(R.id.linkNoticeText)).setText(
					activity.getString(R.string.zero_id_flood_reset, date));
			byte[] id = n.getLinkId();
			row.findViewById(R.id.linkNoticeDismiss).setOnClickListener(
					v -> run(() -> requests.dismissFloodNotice(id)));
			zeroIdNotices.addView(row);
		}
	}

	@UiThread
	private void showWindow(long lastShared) {
		long until = lastShared + ZERO_ID_OPEN_MS;
		long now = System.currentTimeMillis();
		boolean main = keepReceivingIsMain(lastShared, now);
		keepReceiving.setVisibility(main ? View.VISIBLE : View.GONE);
		keepReceivingQuiet.setVisibility(main ? View.GONE : View.VISIBLE);
		if (lastShared > 0 && now < until) {
			zeroIdWindow.setText(activity.getString(
					R.string.zero_id_receiving_until,
					DateUtils.formatDateTime(activity, until,
							DateUtils.FORMAT_SHOW_DATE
									| DateUtils.FORMAT_SHOW_YEAR)));
		} else {
			zeroIdWindow.setText(R.string.zero_id_not_receiving);
		}
	}

	private void bindLink(View row, ContactLinkInfo l) {
		((TextView) row.findViewById(R.id.inviteLinkLabel))
				.setText(l.getLabel());
		String counts = counts(activity.getResources(), l.getUndecidedCount(),
				l.getBlockedCount());
		TextView countsView = row.findViewById(R.id.inviteLinkCounts);
		countsView.setText(counts);
		countsView.setVisibility(counts.isEmpty() ? View.GONE : View.VISIBLE);
		boolean paused = l.isPaused(System.currentTimeMillis());
		TextView state = row.findViewById(R.id.inviteLinkState);
		if (paused) {
			state.setText(activity.getString(R.string.invite_links_flood_paused,
					DateUtils.formatDateTime(activity, l.getPausedUntil(),
							DateUtils.FORMAT_SHOW_DATE
									| DateUtils.FORMAT_SHOW_YEAR
									| DateUtils.FORMAT_SHOW_TIME)));
		} else {
			state.setText(l.isInboxOpen() ? R.string.invite_links_inbox_open
					: R.string.invite_links_inbox_closed);
		}
		String link = l.getLink();
		byte[] id = l.getLinkId();
		Button share = row.findViewById(R.id.inviteLinkShare);
		Button copy = row.findViewById(R.id.inviteLinkCopy);
		Button qr = row.findViewById(R.id.inviteLinkQr);
		Button resume = row.findViewById(R.id.inviteLinkResume);
		int sharing = paused ? View.GONE : View.VISIBLE;
		share.setVisibility(sharing);
		copy.setVisibility(sharing);
		qr.setVisibility(sharing);
		resume.setVisibility(paused ? View.VISIBLE : View.GONE);
		resume.setOnClickListener(
				v -> run(() -> requests.resumePermanentLink(id)));
		share.setEnabled(link != null);
		copy.setEnabled(link != null);
		qr.setEnabled(link != null);
		if (link != null) {
			share.setOnClickListener(v -> shared(id, link, LinkSharing::share));
			copy.setOnClickListener(v -> shared(id, link, LinkSharing::copy));
			qr.setOnClickListener(v -> shared(id, link, LinkSharing::showQr));
		}
		row.findViewById(R.id.inviteLinkWithdraw).setOnClickListener(
				v -> confirmWithdraw(id));
	}

	@UiThread
	private void shared(byte[] id, String link, Share action) {
		db.execute(() -> {
			try {
				requests.permanentLinkShared(id);
			} catch (DbException e) {
			}
		});
		action.run(activity, link);
	}

	@UiThread
	private void onAddClicked() {
		if (permanent.size() >= MAX_PERMANENT_LINKS) {
			toast(limitText());
			return;
		}
		View v = LayoutInflater.from(activity).inflate(
				R.layout.dialog_invite_link_label, null);
		TextInputLayout layout = v.findViewById(R.id.inviteLabelLayout);
		EditText input = v.findViewById(R.id.inviteLabelInput);
		AlertDialog dialog = new SecureAlertDialogBuilder(activity,
				R.style.ZerionDialogTheme)
				.setTitle(R.string.invite_links_add)
				.setView(v)
				.setPositiveButton(R.string.invite_links_add, null)
				.setNegativeButton(R.string.cancel, null)
				.show();
		dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b -> {
			String label = input.getText() == null ? ""
					: input.getText().toString().trim();
			int points = label.codePointCount(0, label.length());
			if (points == 0) {
				layout.setError(activity.getString(
						R.string.invite_links_label_missing));
				return;
			}
			if (points > MAX_LABEL_CODE_POINTS) {
				layout.setError(activity.getString(
						R.string.invite_links_label_too_long,
						MAX_LABEL_CODE_POINTS));
				return;
			}
			dialog.dismiss();
			create(label);
		});
	}

	private void create(String label) {
		db.execute(() -> {
			try {
				requests.createPermanentLink(label);
			} catch (ContactLinkLimitException e) {
				toast(limitText());
			} catch (DbException | IllegalArgumentException e) {
				toast(R.string.generic_error);
			}
			activity.runOnUiThread(this::reload);
		});
	}

	@UiThread
	private void confirmWithdraw(byte[] id) {
		new SecureAlertDialogBuilder(activity, R.style.ZerionDialogTheme)
				.setTitle(R.string.invite_links_withdraw_title)
				.setMessage(R.string.invite_links_withdraw_message)
				.setPositiveButton(R.string.invite_links_withdraw,
						(d, w) -> run(() -> requests.withdrawPermanentLink(id)))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	@UiThread
	private void confirmReset() {
		new SecureAlertDialogBuilder(activity, R.style.ZerionDialogTheme)
				.setTitle(R.string.zero_id_reset_title)
				.setMessage(R.string.zero_id_reset_message)
				.setPositiveButton(R.string.zero_id_reset, (d, w) -> run(() -> {
					requests.resetZeroId();
					toast(R.string.zero_id_reset_done);
				}))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	private void run(DbTask task) {
		db.execute(() -> {
			try {
				task.run();
			} catch (DbException e) {
				toast(R.string.generic_error);
			}
			activity.runOnUiThread(this::reload);
		});
	}

	private void toast(@StringRes int res) {
		toast(activity.getString(res));
	}

	private void toast(String text) {
		activity.runOnUiThread(() ->
				Toast.makeText(activity, text, LENGTH_LONG).show());
	}

	private String limitText() {
		return activity.getString(R.string.invite_links_limit_reached,
				MAX_PERMANENT_LINKS);
	}
}

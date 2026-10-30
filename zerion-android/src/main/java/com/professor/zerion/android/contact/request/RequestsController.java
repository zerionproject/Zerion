package com.professor.zerion.android.contact.request;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.snackbar.Snackbar;
import com.google.android.material.tabs.TabLayout;
import com.google.android.material.textfield.TextInputLayout;
import com.professor.zerion.R;
import com.professor.zerion.android.conversation.ConversationActivity;
import com.professor.zerion.android.security.SecureAlertDialogBuilder;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.ContactRequestManager;
import org.zerionproject.core.api.contact.request.IncomingRequestInfo;
import org.zerionproject.core.api.contact.request.OutgoingRequestInfo;
import org.zerionproject.core.api.db.DbException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.function.LongSupplier;

import javax.annotation.Nullable;

import androidx.annotation.StringRes;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import static android.widget.Toast.LENGTH_LONG;
import static com.professor.zerion.android.util.UiUtils.formatDate;
import static org.zerionproject.core.util.StringUtils.toHexString;

@NotNullByDefault
public class RequestsController {

	public static final int TAB_RECEIVED = 0;
	public static final int TAB_SENT = 1;
	static final long DECLINE_UNDO_MS = 5_000;

	private interface DbTask {
		void run() throws DbException;
	}

	private final Activity activity;
	private final View root;
	private final ContactRequestManager requests;
	private final ContactManager contacts;
	private final Executor db;
	private final TabLayout tabs;
	private final TextView empty;
	private final Adapter adapter = new Adapter();
	private final Handler handler = new Handler(Looper.getMainLooper());
	private final Set<String> declining = new HashSet<>();
	private final List<Runnable> pendingDeclines = new ArrayList<>();
	private final Map<String, RequestRow> added = new LinkedHashMap<>();
	private final Map<String, RequestRow> known = new HashMap<>();
	private final LongSupplier clock;
	private final Runnable refresh = this::reload;
	@Nullable
	private Snackbar undoBar;
	private boolean started = false;

	private List<RequestRow> received = Collections.emptyList();
	private List<RequestRow> sent = Collections.emptyList();
	private int tab;

	public RequestsController(Activity activity, View root,
			ContactRequestManager requests, ContactManager contacts,
			Executor db, int tab) {
		this(activity, root, requests, contacts, db, tab,
				System::currentTimeMillis);
	}

	RequestsController(Activity activity, View root,
			ContactRequestManager requests, ContactManager contacts,
			Executor db, int tab, LongSupplier clock) {
		this.activity = activity;
		this.clock = clock;
		this.root = root;
		this.requests = requests;
		this.contacts = contacts;
		this.db = db;
		this.tab = tab == TAB_SENT ? TAB_SENT : TAB_RECEIVED;
		tabs = root.findViewById(R.id.requestsTabs);
		empty = root.findViewById(R.id.requestsEmpty);
		RecyclerView list = root.findViewById(R.id.requestsList);
		list.setLayoutManager(new LinearLayoutManager(activity));
		list.setAdapter(adapter);
		tabs.addTab(tabs.newTab().setText(R.string.requests_tab_received));
		tabs.addTab(tabs.newTab().setText(R.string.requests_tab_sent));
		TabLayout.Tab selected = tabs.getTabAt(this.tab);
		if (selected != null) selected.select();
		tabs.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
			@Override
			public void onTabSelected(TabLayout.Tab t) {
				RequestsController.this.tab = t.getPosition();
				render();
			}

			@Override
			public void onTabUnselected(TabLayout.Tab t) {
			}

			@Override
			public void onTabReselected(TabLayout.Tab t) {
			}
		});
		render();
	}

	@UiThread
	public int getTab() {
		return tab;
	}

	@UiThread
	public void selectTab(int tab) {
		TabLayout.Tab t = tabs.getTabAt(tab == TAB_SENT ? TAB_SENT
				: TAB_RECEIVED);
		if (t != null) t.select();
	}

	@UiThread
	public void reload() {
		RestorePausePrompt.bindBanner(activity,
				root.findViewById(R.id.invitesPausedBanner), requests, db);
		db.execute(() -> {
			try {
				List<IncomingRequestInfo> in = requests.getIncomingRequests();
				List<OutgoingRequestInfo> out = requests.getOutgoingRequests();
				Collection<Pair<PendingContact, PendingContactState>> p =
						contacts.getPendingContacts();
				activity.runOnUiThread(() -> {
					if (activity.isDestroyed()) return;
					long now = clock.getAsLong();
					remember(received);
					remember(sent);
					received = withAdded(RequestRow.received(activity, in, p,
							now), RequestRow.Kind.INCOMING);
					sent = withAdded(RequestRow.sent(activity, out, in, p,
							now), RequestRow.Kind.OUTGOING);
					scheduleRefresh(now);
					render();
				});
			} catch (DbException e) {
			}
		});
	}

	@UiThread
	public void onContactAdded(byte[] requestId, ContactId contact) {
		String key = toHexString(requestId);
		remember(received);
		remember(sent);
		RequestRow shown = known.get(key);
		if (shown != null) added.put(key, shown.added(contact));
		if (undoBar == null) {
			Snackbar.make(root, R.string.requests_state_contact_added,
					Snackbar.LENGTH_LONG)
					.setAction(R.string.requests_open_chat,
							b -> openChat(contact))
					.show();
		}
		reload();
	}

	private void remember(List<RequestRow> rows) {
		for (RequestRow r : rows) {
			byte[] id = r.getRequestId();
			if (id != null) known.put(toHexString(id), r);
		}
	}

	private List<RequestRow> withAdded(List<RequestRow> rows,
			RequestRow.Kind kind) {
		if (added.isEmpty()) return rows;
		Set<String> seen = new HashSet<>();
		List<RequestRow> merged = new ArrayList<>(rows.size());
		for (RequestRow r : rows) {
			byte[] id = r.getRequestId();
			String key = id == null ? null : toHexString(id);
			RequestRow a = key == null ? null : added.get(key);
			if (a != null) {
				seen.add(key);
				merged.add(a);
			} else {
				merged.add(r);
			}
		}
		for (Map.Entry<String, RequestRow> e : added.entrySet()) {
			if (kind == RequestRow.Kind.INCOMING
					&& e.getValue().getKind() == kind
					&& !seen.contains(e.getKey())) {
				merged.add(e.getValue());
			}
		}
		RequestRow.sort(merged);
		return merged;
	}

	@UiThread
	public void onStart() {
		started = true;
	}

	@UiThread
	public void onStop() {
		started = false;
		handler.removeCallbacks(refresh);
	}

	@UiThread
	public void destroy() {
		onStop();
		known.clear();
		added.clear();
	}

	@UiThread
	private void scheduleRefresh(long now) {
		handler.removeCallbacks(refresh);
		if (!started || activity.isFinishing() || activity.isDestroyed()) {
			return;
		}
		long next = 0;
		for (List<RequestRow> list : Arrays.asList(received, sent)) {
			for (RequestRow r : list) {
				long at = r.getRefreshAt();
				if (at > now && (next == 0 || at < next)) next = at;
			}
		}
		if (next > 0) handler.postDelayed(refresh, next - now);
	}

	@UiThread
	private void openChat(ContactId c) {
		Intent i = new Intent(activity, ConversationActivity.class);
		i.putExtra(ConversationActivity.CONTACT_ID, c.getInt());
		activity.startActivity(i);
	}

	@UiThread
	public void flushDeclines() {
		handler.removeCallbacksAndMessages(null);
		Snackbar bar = undoBar;
		undoBar = null;
		if (bar != null) bar.dismiss();
		for (Runnable r : new ArrayList<>(pendingDeclines)) r.run();
	}

	@UiThread
	private void render() {
		List<RequestRow> rows = new ArrayList<>();
		for (RequestRow r : tab == TAB_SENT ? sent : received) {
			IncomingRequestInfo in = r.getIncoming();
			if (in != null && declining.contains(
					toHexString(in.getRequestId()))) {
				continue;
			}
			rows.add(r);
		}
		adapter.setRows(rows);
		empty.setText(tab == TAB_SENT ? R.string.requests_empty_sent
				: R.string.requests_empty_received);
		empty.setVisibility(rows.isEmpty() ? View.VISIBLE : View.GONE);
	}

	private void run(DbTask task) {
		db.execute(() -> {
			try {
				task.run();
			} catch (DbException e) {
				toast(R.string.generic_error);
			} catch (IllegalArgumentException e) {
				toast(R.string.name_reserved);
			} catch (IllegalStateException e) {
				toast(R.string.requests_expired);
			}
			activity.runOnUiThread(this::reload);
		});
	}

	private void toast(@StringRes int res) {
		activity.runOnUiThread(() ->
				Toast.makeText(activity, res, LENGTH_LONG).show());
	}

	@UiThread
	void onRowClicked(RequestRow row) {
		IncomingRequestInfo r = row.getIncoming();
		if (r == null || !row.isUndecided()) return;
		byte[] id = r.getRequestId();
		run(() -> requests.markOpened(id));
		showDetail(row, r);
	}

	@UiThread
	private void showDetail(RequestRow row, IncomingRequestInfo r) {
		View v = LayoutInflater.from(activity).inflate(
				R.layout.dialog_request_detail, null);
		((TextView) v.findViewById(R.id.requestDetailName))
				.setText(row.getTitle());
		((TextView) v.findViewById(R.id.requestDetailVia))
				.setText(row.getVia());
		((TextView) v.findViewById(R.id.requestDetailTime))
				.setText(formatDate(activity, r.getReceivedAt()));
		TextView note = v.findViewById(R.id.requestDetailNote);
		String text = r.getNote();
		if (android.os.Build.VERSION.SDK_INT >= 26) {
			note.setTextClassifier(
					android.view.textclassifier.TextClassifier.NO_OP);
		}
		if (text != null && !text.isEmpty()) {
			note.setText(text);
			note.setVisibility(View.VISIBLE);
		}
		if (r.isClockDifferent()) {
			v.findViewById(R.id.requestDetailClock).setVisibility(View.VISIBLE);
		}
		new SecureAlertDialogBuilder(activity, R.style.ZerionDialogTheme)
				.setView(v)
				.setPositiveButton(R.string.accept,
						(d, w) -> askAlias(r, r.getName()))
				.setNegativeButton(R.string.decline, (d, w) -> decline(r))
				.setNeutralButton(R.string.requests_block,
						(d, w) -> confirmBlock(r))
				.show();
	}

	@UiThread
	private void askAlias(IncomingRequestInfo r, @Nullable String name) {
		View v = LayoutInflater.from(activity).inflate(
				R.layout.dialog_request_accept, null);
		TextInputLayout layout = v.findViewById(R.id.requestAcceptNameLayout);
		EditText input = v.findViewById(R.id.requestAcceptName);
		if (name != null) input.setText(name);
		AlertDialog dialog = new SecureAlertDialogBuilder(activity,
				R.style.ZerionDialogTheme)
				.setTitle(R.string.requests_accept_name_title)
				.setView(v)
				.setPositiveButton(R.string.accept, null)
				.setNegativeButton(R.string.cancel, null)
				.show();
		Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
		ok.setOnClickListener(b -> {
			String alias = ContactNames.clean(input.getText());
			int problem = ContactNames.problem(alias,
					R.string.nickname_missing);
			if (problem != 0) {
				layout.setError(activity.getString(problem));
				return;
			}
			dialog.dismiss();
			byte[] id = r.getRequestId();
			run(() -> requests.accept(id, alias));
		});
	}

	@UiThread
	private void decline(IncomingRequestInfo r) {
		String key = toHexString(r.getRequestId());
		declining.add(key);
		render();
		byte[] id = r.getRequestId();
		Runnable[] holder = new Runnable[1];
		Snackbar[] bar = new Snackbar[1];
		Runnable commit = () -> {
			if (!pendingDeclines.remove(holder[0])) return;
			declining.remove(key);
			if (undoBar == bar[0]) undoBar = null;
			bar[0].dismiss();
			run(() -> requests.decline(id));
		};
		holder[0] = commit;
		bar[0] = Snackbar.make(root, R.string.requests_declined,
				Snackbar.LENGTH_LONG)
				.setDuration((int) DECLINE_UNDO_MS)
				.setAction(R.string.undo_button, b -> {
					if (!pendingDeclines.remove(commit)) {
						Toast.makeText(activity,
								R.string.requests_already_declined,
								LENGTH_LONG).show();
						return;
					}
					handler.removeCallbacks(commit);
					declining.remove(key);
					if (undoBar == bar[0]) undoBar = null;
					render();
				});
		undoBar = bar[0];
		pendingDeclines.add(commit);
		handler.postDelayed(commit, DECLINE_UNDO_MS);
		bar[0].show();
	}

	@UiThread
	private void confirmBlock(IncomingRequestInfo r) {
		byte[] id = r.getRequestId();
		new SecureAlertDialogBuilder(activity, R.style.ZerionDialogTheme)
				.setTitle(R.string.requests_block_title)
				.setMessage(blockMessage(activity, r))
				.setPositiveButton(R.string.requests_block,
						(d, w) -> run(() -> requests.block(id)))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	static String blockMessage(Context ctx, IncomingRequestInfo r) {
		if (r.getLinkKind() == ContactLink.KIND_ZERO_ID) {
			return ctx.getString(R.string.requests_block_message_zero_id);
		}
		String label = r.getLinkLabel();
		if (r.getLinkKind() == ContactLink.KIND_PERMANENT && label != null
				&& !label.isEmpty()) {
			return ctx.getString(R.string.requests_block_message_link, label);
		}
		return ctx.getString(R.string.requests_block_message);
	}

	@UiThread
	void onAction(RequestRow row) {
		IncomingRequestInfo in = row.getIncoming();
		OutgoingRequestInfo out = row.getOutgoing();
		PendingContact p = row.getPending();
		switch (row.getAction()) {
			case CANCEL:
				if (out != null) confirmCancel(out.getRequestId());
				break;
			case DISMISS:
				if (out != null) {
					byte[] id = out.getRequestId();
					run(() -> requests.dismissOutgoing(id));
				} else if (in != null) {
					byte[] id = in.getRequestId();
					run(() -> requests.dismissIncoming(id));
				}
				break;
			case TRY_AGAIN:
				if (in != null) {
					byte[] id = in.getRequestId();
					run(() -> requests.retryAccepted(id));
				}
				break;
			case REMOVE:
				if (p != null) confirmRemove(row, p);
				break;
			case OPEN_CHAT:
				ContactId c = row.getContact();
				if (c != null) openChat(c);
				break;
			default:
				break;
		}
	}

	@UiThread
	private void confirmCancel(byte[] id) {
		new SecureAlertDialogBuilder(activity, R.style.ZerionDialogTheme)
				.setTitle(R.string.requests_cancel_title)
				.setPositiveButton(R.string.requests_cancel,
						(d, w) -> run(() -> requests.cancelRequest(id)))
				.setNegativeButton(R.string.requests_keep, null)
				.show();
	}

	@UiThread
	private void confirmRemove(RequestRow row, PendingContact p) {
		if (row.getPendingState() == PendingContactState.FAILED) {
			run(() -> contacts.removePendingContact(p.getId()));
			return;
		}
		new SecureAlertDialogBuilder(activity, R.style.ZerionDialogTheme)
				.setTitle(R.string.dialog_title_remove_pending_contact)
				.setMessage(R.string.dialog_message_remove_pending_contact)
				.setPositiveButton(R.string.pending_contact_remove_action,
						(d, w) -> run(() ->
								contacts.removePendingContact(p.getId())))
				.setNegativeButton(R.string.cancel, null)
				.show();
	}

	@StringRes
	static int actionLabel(RequestRow row) {
		switch (row.getAction()) {
			case CANCEL:
				return R.string.requests_cancel;
			case DISMISS:
				return row.getKind() == RequestRow.Kind.OUTGOING
						? R.string.requests_remove : R.string.requests_dismiss;
			case TRY_AGAIN:
				return R.string.requests_try_again;
			case REMOVE:
				return R.string.pending_contact_remove_action;
			case OPEN_CHAT:
				return R.string.requests_open_chat;
			default:
				return 0;
		}
	}

	private class Adapter extends RecyclerView.Adapter<Holder> {

		private List<RequestRow> rows = Collections.emptyList();

		void setRows(List<RequestRow> rows) {
			this.rows = rows;
			notifyDataSetChanged();
		}

		@Override
		public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
			return new Holder(LayoutInflater.from(parent.getContext())
					.inflate(R.layout.list_item_request, parent, false));
		}

		@Override
		public void onBindViewHolder(Holder h, int position) {
			h.bind(rows.get(position));
		}

		@Override
		public int getItemCount() {
			return rows.size();
		}
	}

	private class Holder extends RecyclerView.ViewHolder {

		private final TextView title, subtitle, time;
		private final Button action;
		private final ProgressBar progress;

		Holder(View v) {
			super(v);
			title = v.findViewById(R.id.requestTitle);
			subtitle = v.findViewById(R.id.requestSubtitle);
			time = v.findViewById(R.id.requestTime);
			action = v.findViewById(R.id.requestAction);
			progress = v.findViewById(R.id.requestProgress);
		}

		void bind(RequestRow row) {
			title.setText(row.getTitle());
			title.setTypeface(null, row.isUnopened()
					? android.graphics.Typeface.BOLD
					: android.graphics.Typeface.NORMAL);
			String via = row.getVia();
			if (row.getState() != 0) {
				String state = activity.getString(row.getState());
				subtitle.setText(via == null ? state : via + "\n" + state);
			} else {
				subtitle.setText(via);
			}
			time.setText(formatDate(activity, row.getTime()));
			progress.setVisibility(row.showsProgress() ? View.VISIBLE
					: View.GONE);
			int label = actionLabel(row);
			if (label == 0) {
				action.setVisibility(View.GONE);
				action.setOnClickListener(null);
			} else {
				action.setVisibility(View.VISIBLE);
				action.setText(label);
				action.setOnClickListener(b -> onAction(row));
			}
			if (row.isUndecided()) {
				itemView.setOnClickListener(b -> onRowClicked(row));
				itemView.setClickable(true);
			} else {
				itemView.setOnClickListener(null);
				itemView.setClickable(false);
			}
		}
	}
}

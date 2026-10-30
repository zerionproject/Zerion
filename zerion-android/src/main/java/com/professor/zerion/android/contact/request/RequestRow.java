package com.professor.zerion.android.contact.request;

import android.content.Context;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.Pair;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.PendingContact;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.contact.PendingContactState;
import org.zerionproject.core.api.contact.request.ContactLink;
import org.zerionproject.core.api.contact.request.IncomingRequest;
import org.zerionproject.core.api.contact.request.IncomingRequestInfo;
import org.zerionproject.core.api.contact.request.OutgoingRequest;
import org.zerionproject.core.api.contact.request.OutgoingRequestInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import androidx.annotation.StringRes;

import static java.util.concurrent.TimeUnit.DAYS;
import static java.util.concurrent.TimeUnit.MINUTES;

@Immutable
@NotNullByDefault
public final class RequestRow {

	public enum Kind {
		INCOMING, OUTGOING, MUTUAL
	}

	public enum Action {
		NONE, CANCEL, DISMISS, TRY_AGAIN, REMOVE, OPEN_CHAT
	}

	public static final long NO_ANSWER_YET_MS = DAYS.toMillis(3);
	public static final long CONNECTING_SHOWN_MS = MINUTES.toMillis(2);

	private final Kind kind;
	@Nullable
	private final IncomingRequestInfo incoming;
	@Nullable
	private final OutgoingRequestInfo outgoing;
	@Nullable
	private final PendingContact pending;
	@Nullable
	private final PendingContactState pendingState;
	private final String title;
	@Nullable
	private final String via;
	@StringRes
	private final int state;
	private final long time;
	private final Action action;
	private final boolean unopened;
	private final boolean progress;
	private final long refreshAt;
	@Nullable
	private final ContactId contact;

	private RequestRow(Kind kind, @Nullable IncomingRequestInfo incoming,
			@Nullable OutgoingRequestInfo outgoing,
			@Nullable PendingContact pending,
			@Nullable PendingContactState pendingState, String title,
			@Nullable String via, int state, long time, Action action,
			boolean unopened) {
		this(kind, incoming, outgoing, pending, pendingState, title, via,
				state, time, action, unopened, false, 0, null);
	}

	private RequestRow(Kind kind, @Nullable IncomingRequestInfo incoming,
			@Nullable OutgoingRequestInfo outgoing,
			@Nullable PendingContact pending,
			@Nullable PendingContactState pendingState, String title,
			@Nullable String via, int state, long time, Action action,
			boolean unopened, boolean progress, long refreshAt,
			@Nullable ContactId contact) {
		this.kind = kind;
		this.incoming = incoming;
		this.outgoing = outgoing;
		this.pending = pending;
		this.pendingState = pendingState;
		this.title = title;
		this.via = via;
		this.state = state;
		this.time = time;
		this.action = action;
		this.unopened = unopened;
		this.progress = progress;
		this.refreshAt = refreshAt;
		this.contact = contact;
	}

	public Kind getKind() {
		return kind;
	}

	@Nullable
	public IncomingRequestInfo getIncoming() {
		return incoming;
	}

	@Nullable
	public OutgoingRequestInfo getOutgoing() {
		return outgoing;
	}

	@Nullable
	public PendingContact getPending() {
		return pending;
	}

	@Nullable
	public PendingContactState getPendingState() {
		return pendingState;
	}

	public String getTitle() {
		return title;
	}

	@Nullable
	public String getVia() {
		return via;
	}

	@StringRes
	public int getState() {
		return state;
	}

	public long getTime() {
		return time;
	}

	public Action getAction() {
		return action;
	}

	public boolean isUnopened() {
		return unopened;
	}

	public boolean showsProgress() {
		return progress;
	}

	public long getRefreshAt() {
		return refreshAt;
	}

	@Nullable
	public ContactId getContact() {
		return contact;
	}

	@Nullable
	public byte[] getRequestId() {
		if (incoming != null) return incoming.getRequestId();
		if (outgoing != null) return outgoing.getRequestId();
		return null;
	}

	public RequestRow added(ContactId c) {
		return new RequestRow(kind, incoming, outgoing, pending, null, title,
				via, R.string.requests_state_contact_added, time,
				Action.OPEN_CHAT, false, false, 0, c);
	}

	public boolean isUndecided() {
		return incoming != null && (incoming.getState()
				== IncomingRequest.STATE_RECEIVED
				|| incoming.getState() == IncomingRequest.STATE_OPENED);
	}

	public static String via(Context ctx, IncomingRequestInfo r) {
		String label = r.getLinkLabel();
		if (r.getLinkKind() == ContactLink.KIND_PERMANENT && label != null) {
			return ctx.getString(R.string.requests_via_link, label);
		}
		return ctx.getString(R.string.requests_via_zero_id);
	}

	public static List<RequestRow> received(Context ctx,
			Collection<IncomingRequestInfo> incoming,
			Collection<Pair<PendingContact, PendingContactState>> pendings,
			long now) {
		Map<PendingContactId, Pair<PendingContact, PendingContactState>> byId =
				index(pendings);
		List<RequestRow> rows = new ArrayList<>();
		for (IncomingRequestInfo r : incoming) {
			String name = r.getName();
			String title = name == null || name.isEmpty()
					? ctx.getString(R.string.requests_unnamed) : name;
			String via = via(ctx, r);
			int s = r.getState();
			if (s == IncomingRequest.STATE_RECEIVED
					|| s == IncomingRequest.STATE_OPENED) {
				rows.add(new RequestRow(Kind.INCOMING, r, null, null, null,
						title, via, 0, r.getReceivedAt(), Action.NONE,
						s == IncomingRequest.STATE_RECEIVED));
			} else if (s == IncomingRequest.STATE_ACCEPTED) {
				Pair<PendingContact, PendingContactState> p =
						r.getPendingContactId() == null ? null
								: byId.get(r.getPendingContactId());
				PendingContact pc = p == null ? null : p.getFirst();
				PendingContactState ps = p == null ? null : p.getSecond();
				if (pc != null && !pc.getAlias().isEmpty()) {
					title = pc.getAlias();
				}
				boolean failed = ps == null
						|| ps == PendingContactState.FAILED;
				long since = Math.max(r.getAcceptedAt(),
						pc == null ? 0 : pc.getTimestamp());
				long fresh = since + CONNECTING_SHOWN_MS;
				boolean recent = ps == PendingContactState
						.WAITING_FOR_CONNECTION && now >= since
						&& now < fresh;
				int state;
				boolean progress = true;
				if (failed) {
					state = R.string.requests_state_could_not_connect;
					progress = false;
				} else if (ps == PendingContactState.ADDING_CONTACT) {
					state = R.string.adding_contact;
				} else if (ps == PendingContactState.CONNECTING || recent) {
					state = R.string.connecting;
				} else {
					state = R.string.requests_state_connecting;
					progress = false;
				}
				rows.add(new RequestRow(Kind.INCOMING, r, null, pc, ps, title,
						via, state, r.getAcceptedAt(),
						failed ? Action.TRY_AGAIN : Action.NONE, false,
						progress, recent ? fresh : 0, null));
			} else {
				rows.add(new RequestRow(Kind.INCOMING, r, null, null, null,
						title, via, R.string.requests_state_cancelled_by_sender,
						r.getReceivedAt(), Action.DISMISS, false));
			}
		}
		sort(rows);
		return rows;
	}

	public static List<RequestRow> sent(Context ctx,
			Collection<OutgoingRequestInfo> outgoing,
			Collection<IncomingRequestInfo> incoming,
			Collection<Pair<PendingContact, PendingContactState>> pendings,
			long now) {
		Map<PendingContactId, Pair<PendingContact, PendingContactState>> byId =
				index(pendings);
		Set<PendingContactId> owned = new HashSet<>();
		for (OutgoingRequestInfo r : outgoing) {
			if (r.getPendingContactId() != null) {
				owned.add(r.getPendingContactId());
			}
		}
		for (IncomingRequestInfo r : incoming) {
			if (r.getPendingContactId() != null) {
				owned.add(r.getPendingContactId());
			}
		}
		List<RequestRow> rows = new ArrayList<>();
		for (OutgoingRequestInfo r : outgoing) {
			int state = outgoingState(r, now);
			boolean open = r.getState() == OutgoingRequest.STATE_QUEUED
					|| r.getState() == OutgoingRequest.STATE_DELIVERED;
			Action action = open ? Action.CANCEL
					: r.isWithdrawing() ? Action.NONE : Action.DISMISS;
			Pair<PendingContact, PendingContactState> p =
					r.getState() != OutgoingRequest.STATE_DELIVERED
							|| r.getPendingContactId() == null ? null
							: byId.get(r.getPendingContactId());
			PendingContactState ps = p == null ? null : p.getSecond();
			boolean progress = false;
			if (ps == PendingContactState.ADDING_CONTACT) {
				state = R.string.adding_contact;
				progress = true;
			} else if (ps == PendingContactState.CONNECTING) {
				state = R.string.connecting;
				progress = true;
			}
			rows.add(new RequestRow(Kind.OUTGOING, null, r,
					p == null ? null : p.getFirst(), ps, r.getAlias(), null,
					state, r.getCreatedAt(), action, false, progress, 0,
					null));
		}
		for (Pair<PendingContact, PendingContactState> p : pendings) {
			PendingContact pc = p.getFirst();
			if (owned.contains(pc.getId())) continue;
			String alias = pc.getAlias();
			rows.add(new RequestRow(Kind.MUTUAL, null, null, pc, p.getSecond(),
					alias.isEmpty() ? ctx.getString(R.string.unknown_contact)
							: alias, null, mutualState(p.getSecond()),
					pc.getTimestamp(), Action.REMOVE, false));
		}
		sort(rows);
		return rows;
	}

	@StringRes
	static int outgoingState(OutgoingRequestInfo r, long now) {
		switch (r.getState()) {
			case OutgoingRequest.STATE_QUEUED:
				return r.isInboxBusy() ? R.string.requests_state_inbox_busy
						: R.string.requests_state_queued;
			case OutgoingRequest.STATE_DELIVERED:
				return now - r.getDeliveredAt() >= NO_ANSWER_YET_MS
						? R.string.requests_state_no_answer_yet
						: R.string.requests_state_delivered;
			case OutgoingRequest.STATE_ADDED:
				return R.string.requests_state_accepted;
			case OutgoingRequest.STATE_NOT_DELIVERED:
				return R.string.requests_state_not_delivered;
			case OutgoingRequest.STATE_NO_ANSWER:
				return R.string.requests_state_no_answer;
			default:
				if (r.isWithdrawing()) {
					return R.string.requests_state_withdrawing;
				}
				return r.getDeliveredAt() > 0
						? R.string.requests_state_withdrawn
						: R.string.requests_state_cancelled;
		}
	}

	@StringRes
	static int mutualState(PendingContactState s) {
		switch (s) {
			case CONNECTING:
				return R.string.connecting;
			case ADDING_CONTACT:
				return R.string.adding_contact;
			case FAILED:
				return R.string.adding_contact_failed;
			default:
				return R.string.waiting_for_contact_to_come_online;
		}
	}

	private static Map<PendingContactId,
			Pair<PendingContact, PendingContactState>> index(
			Collection<Pair<PendingContact, PendingContactState>> pendings) {
		Map<PendingContactId, Pair<PendingContact, PendingContactState>> m =
				new HashMap<>();
		for (Pair<PendingContact, PendingContactState> p : pendings) {
			m.put(p.getFirst().getId(), p);
		}
		return m;
	}

	static void sort(List<RequestRow> rows) {
		Collections.sort(rows, (a, b) -> Long.compare(b.time, a.time));
	}
}

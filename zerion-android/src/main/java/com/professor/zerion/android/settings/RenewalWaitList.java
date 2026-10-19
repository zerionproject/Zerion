package com.professor.zerion.android.settings;

import android.content.res.Resources;
import android.text.format.DateUtils;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.connection.ContactConnectionDiagnostics;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.contact.ContactManager;
import org.zerionproject.core.api.db.DbException;

import java.util.ArrayList;
import java.util.List;

import static org.zerionproject.core.api.connection.ContactConnectionSnapshot.UNKNOWN;

@NotNullByDefault
final class RenewalWaitList {

	static final class Row {

		final ContactId contactId;
		final String name;
		final long lastConnectedAgoMs;

		Row(ContactId contactId, String name, long lastConnectedAgoMs) {
			this.contactId = contactId;
			this.name = name;
			this.lastConnectedAgoMs = lastConnectedAgoMs;
		}
	}

	private RenewalWaitList() {
	}

	static List<Row> load(ContactConnectionDiagnostics diagnostics,
			ContactManager contacts) {
		List<Row> rows = new ArrayList<>();
		List<ContactId> waiting;
		try {
			waiting = diagnostics.getContactsHoldingAddressChange();
		} catch (DbException e) {
			return rows;
		}
		for (ContactId c : waiting) {
			try {
				Contact contact = contacts.getContact(c);
				String alias = contact.getAlias();
				String name = alias == null ? contact.getAuthor().getName()
						: alias;
				rows.add(new Row(c, name, diagnostics.getLastConnectedAgoMs(c)));
			} catch (DbException ignored) {
			}
		}
		return rows;
	}

	static String lastSeen(Resources r, long agoMs, long nowMs) {
		if (agoMs == UNKNOWN) {
			return r.getString(R.string.contact_connection_never);
		}
		CharSequence when = DateUtils.getRelativeTimeSpanString(
				nowMs - Math.max(0, agoMs), nowMs, DateUtils.MINUTE_IN_MILLIS);
		return r.getString(R.string.renewal_waiting_last_seen, when);
	}
}

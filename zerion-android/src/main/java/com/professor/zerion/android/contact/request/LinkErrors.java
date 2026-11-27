package com.professor.zerion.android.contact.request;

import com.professor.zerion.R;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.UnsupportedVersionException;
import org.zerionproject.core.api.contact.request.DamagedLinkException;
import org.zerionproject.core.api.contact.request.IncompleteLinkException;
import org.zerionproject.core.api.contact.request.OwnLinkException;

import androidx.annotation.StringRes;

@NotNullByDefault
public final class LinkErrors {

	private LinkErrors() {
	}

	@StringRes
	public static int messageFor(Exception e) {
		if (e instanceof OwnLinkException) return R.string.send_invite_own_link;
		if (e instanceof DamagedLinkException) {
			return R.string.send_invite_damaged_link;
		}
		if (e instanceof IncompleteLinkException) {
			return R.string.send_invite_incomplete_link;
		}
		if (e instanceof UnsupportedVersionException) {
			return R.string.unsupported_link;
		}
		return R.string.invalid_link;
	}
}

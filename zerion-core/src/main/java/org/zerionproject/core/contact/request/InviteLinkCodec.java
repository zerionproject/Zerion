package org.zerionproject.core.contact.request;

import org.briarproject.nullsafety.NotNullByDefault;
import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.UnsupportedVersionException;
import org.zerionproject.core.api.contact.request.DamagedLinkException;
import org.zerionproject.core.api.contact.request.IncompleteLinkException;
import org.zerionproject.core.api.contact.request.InviteLink;
import org.zerionproject.core.api.contact.request.InviteLink.Flow;
import org.zerionproject.core.api.crypto.CryptoComponent;
import org.zerionproject.core.util.Base32;
import org.zerionproject.core.util.ZerionLinks;

import java.util.Arrays;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.zerionproject.core.api.contact.HandshakeLinkConstants.BASE32_LINK_BYTES_CLASSICAL;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.BASE32_LINK_BYTES_HYBRID;
import static org.zerionproject.core.api.contact.HandshakeLinkConstants.RAW_LINK_BYTES_CLASSICAL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.INBOX_PARAMETER;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_BODY_BYTES;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_TYPE_CLASSICAL;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_TYPE_REQUEST_ONLY;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.LINK_TYPE_ZERO_ID;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ONION_LENGTH;
import static org.zerionproject.core.api.contact.request.ContactRequestConstants.ONION_PUBLIC_KEY_BYTES;

@Immutable
@NotNullByDefault
public class InviteLinkCodec {

	private static final Pattern LINK = Pattern.compile("^"
			+ ZerionLinks.SCHEME_PATTERN + "://([a-z2-7]{"
			+ BASE32_LINK_BYTES_CLASSICAL + "}|[a-z2-7]{"
			+ BASE32_LINK_BYTES_HYBRID + "})(?:\\?(.*))?$");
	private static final Pattern ONION =
			Pattern.compile("^[a-z2-7]{" + ONION_LENGTH + "}$");

	private final CryptoComponent crypto;

	@Inject
	public InviteLinkCodec(CryptoComponent crypto) {
		this.crypto = crypto;
	}

	public InviteLink parse(String link) throws FormatException {
		Matcher m = LINK.matcher(link.trim());
		if (!m.matches()) throw new FormatException();
		byte[] raw;
		try {
			raw = Base32.decode(m.group(1), true);
		} catch (IllegalArgumentException e) {
			throw new FormatException();
		}
		if (raw.length < 1) throw new FormatException();
		int type = raw[0] & 0xFF;
		if (type > LINK_TYPE_REQUEST_ONLY) {
			throw new UnsupportedVersionException(false);
		}
		if (type == LINK_TYPE_CLASSICAL) {
			if (raw.length != RAW_LINK_BYTES_CLASSICAL) {
				throw new FormatException();
			}
			return new InviteLink(type, raw, null, Flow.MUTUAL_CLASSICAL);
		}
		if (raw.length != LINK_BODY_BYTES) throw new FormatException();
		String inbox = inboxParameter(m.group(2));
		if (inbox != null) return new InviteLink(type, raw, inbox, Flow.REQUEST);
		if (type == LINK_TYPE_ZERO_ID) {
			return new InviteLink(type, raw, null, Flow.MUTUAL_V1);
		}
		throw new IncompleteLinkException();
	}

	public String format(int type, byte[] commitment, byte[] x25519,
			@Nullable String inbox) {
		if (type != LINK_TYPE_ZERO_ID && type != LINK_TYPE_REQUEST_ONLY) {
			throw new IllegalArgumentException();
		}
		if (commitment.length + x25519.length != LINK_BODY_BYTES - 1) {
			throw new IllegalArgumentException();
		}
		byte[] body = new byte[LINK_BODY_BYTES];
		body[0] = (byte) type;
		System.arraycopy(commitment, 0, body, 1, commitment.length);
		System.arraycopy(x25519, 0, body, 1 + commitment.length,
				x25519.length);
		return format(body, inbox);
	}

	public String format(byte[] body, @Nullable String inbox) {
		String s = ZerionLinks.SCHEME + "://"
				+ Base32.encode(body).toLowerCase(Locale.US);
		if (inbox == null) return s;
		if (!isValidOnion(inbox)) throw new IllegalArgumentException();
		return s + "?" + INBOX_PARAMETER + "=" + inbox;
	}

	public boolean isValidOnion(String onion) {
		if (!ONION.matcher(onion).matches()) return false;
		byte[] decoded;
		try {
			decoded = Base32.decode(onion, true);
		} catch (IllegalArgumentException e) {
			return false;
		}
		if (decoded.length < ONION_PUBLIC_KEY_BYTES) return false;
		byte[] pub = Arrays.copyOf(decoded, ONION_PUBLIC_KEY_BYTES);
		return crypto.encodeOnion(pub).equals(onion);
	}

	public byte[] onionPublicKey(String onion) throws FormatException {
		if (!isValidOnion(onion)) throw new DamagedLinkException();
		return Arrays.copyOf(Base32.decode(onion, true),
				ONION_PUBLIC_KEY_BYTES);
	}

	@Nullable
	private String inboxParameter(@Nullable String query)
			throws FormatException {
		if (query == null || query.isEmpty()) return null;
		String found = null;
		for (String part : query.split("&", -1)) {
			int eq = part.indexOf('=');
			String name = eq < 0 ? part : part.substring(0, eq);
			if (!name.equals(INBOX_PARAMETER)) continue;
			if (found != null) throw new DamagedLinkException();
			String value = eq < 0 ? "" : part.substring(eq + 1);
			if (!isValidOnion(value)) throw new DamagedLinkException();
			found = value;
		}
		return found;
	}
}

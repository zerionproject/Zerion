package com.professor.zerion.android.channel;

import android.content.Context;

import com.professor.zerion.R;
import com.professor.zerion.android.contact.identity.IdentityFingerprint;

import org.briarproject.nullsafety.MethodsNotNullByDefault;
import org.briarproject.nullsafety.ParametersNotNullByDefault;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelManager;
import org.zerionproject.app.api.channel.ChannelState;
import org.zerionproject.app.api.channel.ChannelSubscriber;
import org.zerionproject.core.api.db.DbException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@MethodsNotNullByDefault
@ParametersNotNullByDefault
final class ChannelMember {

	enum Action {
		TRUST, UNTRUST, MAKE_EDITOR, REMOVE_EDITOR, REMOVE, BLOCK, UNBLOCK,
		SAFETY_CODE
	}

	final byte[] ed25519;
	final byte[] mlDsa;
	final String name;
	final boolean owner;
	final boolean me;
	final boolean announced;
	final boolean editor;
	final long delegationSeq;
	final boolean trusted;
	final boolean blocked;
	final boolean viewerIsOwner;
	final boolean publicChannel;
	final boolean requiresApproval;
	final boolean editorsFull;

	ChannelMember(byte[] ed25519, byte[] mlDsa, String name, boolean owner,
			boolean me, boolean announced, boolean editor, long delegationSeq,
			boolean trusted, boolean blocked, boolean viewerIsOwner,
			boolean publicChannel, boolean requiresApproval,
			boolean editorsFull) {
		this.ed25519 = ed25519;
		this.mlDsa = mlDsa;
		this.name = name;
		this.owner = owner;
		this.me = me;
		this.announced = announced;
		this.editor = editor;
		this.delegationSeq = delegationSeq;
		this.trusted = trusted;
		this.blocked = blocked;
		this.viewerIsOwner = viewerIsOwner;
		this.publicChannel = publicChannel;
		this.requiresApproval = requiresApproval;
		this.editorsFull = editorsFull;
	}

	static ChannelMember load(ChannelManager m, byte[] channelId,
			byte[] ed25519, String nameHint, byte[] mlDsaHint)
			throws DbException {
		ChannelState s = m.getChannel(channelId);
		if (s == null) throw new DbException();
		boolean viewerIsOwner = s.weArePublisher();
		byte[] mine;
		try {
			byte[] key = m.getMyChannelPublicKey(channelId);
			mine = key == null ? new byte[0]
					: Arrays.copyOf(key, Math.min(32, key.length));
		} catch (DbException e) {
			mine = new byte[0];
		}
		ChannelSubscriber sub = null;
		boolean blocked = false;
		List<ChannelDelegationCert> active;
		if (viewerIsOwner) {
			for (ChannelSubscriber c : m.getAnnouncedSubscribers(channelId)) {
				if (Arrays.equals(c.getEd25519PubKey(), ed25519)) {
					sub = c;
					break;
				}
			}
			for (byte[] k : m.getBannedKeys(channelId)) {
				if (Arrays.equals(k, ed25519)) blocked = true;
			}
			if (sub != null && sub.isBanned()) blocked = true;
			active = m.listActiveDelegations(channelId);
		} else {
			active = s.getActiveDelegations();
		}
		boolean editor = false;
		long seq = 0L;
		for (ChannelDelegationCert c : active) {
			if (Arrays.equals(c.getDelegateeEd25519PubKey(), ed25519)) {
				editor = true;
				seq = c.getDelegationSeq();
				break;
			}
		}
		String name = sub != null && !sub.getDisplayName().trim().isEmpty()
				? sub.getDisplayName().trim() : nameHint.trim();
		byte[] mlDsa = sub != null && sub.getMlDsaPubKey().length > 0
				? sub.getMlDsaPubKey() : mlDsaHint;
		return new ChannelMember(ed25519, mlDsa, name,
				Arrays.equals(ed25519, s.getPublisherEd25519PubKey()),
				mine.length > 0 && Arrays.equals(ed25519, mine), sub != null,
				editor, seq, sub != null && sub.isTrusted(), blocked,
				viewerIsOwner, s.isPublicChannel(), s.requiresApproval(),
				active.size()
						>= ChannelConstants.MAX_ACTIVE_DELEGATIONS_PER_CHANNEL);
	}

	List<Action> actions() {
		List<Action> out = new ArrayList<>();
		if (viewerIsOwner && !owner && !me) {
			if (blocked) {
				out.add(Action.UNBLOCK);
			} else {
				if (announced) out.add(trusted ? Action.UNTRUST : Action.TRUST);
				if (editor) {
					out.add(Action.REMOVE_EDITOR);
				} else if (announced && mlDsa.length > 0) {
					out.add(Action.MAKE_EDITOR);
				}
				if (!publicChannel) out.add(Action.REMOVE);
				out.add(Action.BLOCK);
			}
		}
		if (mlDsa.length > 0) out.add(Action.SAFETY_CODE);
		return out;
	}

	String displayName(Context ctx) {
		return name.isEmpty()
				? ctx.getString(R.string.channels_delegations_unnamed_person)
				: name;
	}

	String role(Context ctx) {
		String role = ctx.getString(owner ? R.string.channels_comment_owner
				: editor ? R.string.channels_member_role_editor
				: R.string.channels_member_role_subscriber);
		if (me) role = parts(ctx, role, R.string.channels_comment_you);
		if (blocked) {
			role = parts(ctx, role, R.string.channels_settings_blocked);
		} else if (trusted) {
			role = parts(ctx, role,
					R.string.channels_subscribers_trusted_badge);
		}
		return role;
	}

	private static String parts(Context ctx, String first, int second) {
		return ctx.getString(R.string.channels_label_parts, first,
				ctx.getString(second));
	}

	static String safetyCode(byte[] ed25519, byte[] mlDsa) {
		if (ed25519.length == 0 || mlDsa.length == 0) return "";
		return IdentityFingerprint.forIdentity(ed25519, mlDsa);
	}

	static String ownSafetyCode(byte[] hybridPublicKey) {
		if (hybridPublicKey.length <= 32) return "";
		return safetyCode(Arrays.copyOf(hybridPublicKey, 32),
				Arrays.copyOfRange(hybridPublicKey, 32,
						hybridPublicKey.length));
	}
}

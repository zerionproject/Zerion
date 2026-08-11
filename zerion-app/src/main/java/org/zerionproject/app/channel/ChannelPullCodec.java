package org.zerionproject.app.channel;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfReader;
import org.zerionproject.core.api.data.BdfReaderFactory;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.api.data.BdfWriterFactory;
import org.zerionproject.app.api.channel.ChannelConstants;
import org.zerionproject.app.api.channel.ChannelDelegationCert;
import org.zerionproject.app.api.channel.ChannelPost;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.Nullable;
import javax.inject.Inject;

@NotNullByDefault
class ChannelPullCodec {

	private final BdfReaderFactory readerFactory;
	private final BdfWriterFactory writerFactory;

	@Inject
	ChannelPullCodec(BdfReaderFactory readerFactory,
			BdfWriterFactory writerFactory) {
		this.readerFactory = readerFactory;
		this.writerFactory = writerFactory;
	}

	byte[] encodePullRequest(byte[] channelId, long sinceSeqNum,
			@Nullable byte[] hmacResponse, @Nullable byte[] nonce)
			throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_PULL_REQUEST);
		d.put("channelId", channelId);
		d.put("sinceSeqNum", sinceSeqNum);
		if (hmacResponse != null) d.put("hmacResponse", hmacResponse);
		if (nonce != null) d.put("nonce", nonce);
		return writeDict(d);
	}

	PullRequest decodePullRequest(byte[] data)
			throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_PULL_REQUEST.equals(type)) {
			throw new FormatException();
		}
		return new PullRequest(d.getRaw("channelId"),
				d.getLong("sinceSeqNum"),
				d.getOptionalRaw("hmacResponse"),
				d.getOptionalRaw("nonce"));
	}

	byte[] encodePullResponse(BdfDictionary manifest,
			List<ChannelPost> newPosts,
			@Nullable byte[] contentKeyEnvelope,
			List<String> neighbourHints,
			List<org.zerionproject.app.api.channel.ChannelReaction>
					reactions,
			List<org.zerionproject.app.api.channel.ChannelComment>
					comments) throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_PULL_RESPONSE);
		d.put("manifest", manifest);
		BdfList postList = new BdfList();
		for (ChannelPost p : newPosts) {
			postList.add(postToWire(p));
		}
		d.put("posts", postList);
		if (contentKeyEnvelope != null) {
			d.put("contentKeyEnvelope", contentKeyEnvelope);
		}
		BdfList hintList = new BdfList();
		for (String h : neighbourHints) hintList.add(h);
		d.put("neighbourHints", hintList);
		BdfList reactionList = new BdfList();
		for (org.zerionproject.app.api.channel.ChannelReaction r
				: reactions) {
			BdfDictionary rd = new BdfDictionary();
			rd.put("seq", r.getPostSeqNum());
			rd.put("emoji", r.getEmoji());
			rd.put("ed", r.getSignerEd25519PubKey());
			rd.put("ml", r.getSignerMlDsaPubKey());
			rd.put("ts", r.getTimestampHourMs());
			byte[] rSig = r.getSignature();
			if (rSig != null && rSig.length > 0) rd.put("sig", rSig);
			reactionList.add(rd);
		}
		d.put("reactions", reactionList);
		BdfList commentList = new BdfList();
		for (org.zerionproject.app.api.channel.ChannelComment c
				: comments) {
			BdfDictionary cd = new BdfDictionary();
			cd.put("seq", c.getParentPostSeqNum());
			cd.put("id", c.getCommentId());
			cd.put("body", c.getBody());
			cd.put("name", c.getAuthorDisplayName());
			cd.put("ed", c.getAuthorEd25519PubKey());
			cd.put("ml", c.getAuthorMlDsaPubKey());
			cd.put("ts", c.getTimestampHourMs());
			byte[] cSig = c.getSignature();
			if (cSig != null && cSig.length > 0) cd.put("sig", cSig);
			commentList.add(cd);
		}
		d.put("comments", commentList);
		return writeDict(d);
	}

	PullResponse decodePullResponse(byte[] data, byte[] channelId)
			throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_PULL_RESPONSE.equals(type)) {
			throw new FormatException();
		}
		BdfDictionary manifest = d.getDictionary("manifest");
		BdfList postList = d.getList("posts");
		List<ChannelPost> posts = new ArrayList<>(postList.size());
		for (Object o : postList) {
			if (!(o instanceof BdfDictionary)) continue;
			posts.add(wireToPost(channelId, (BdfDictionary) o));
		}
		byte[] envelope = d.getOptionalRaw("contentKeyEnvelope");
		List<String> hints = new ArrayList<>();
		BdfList hintList = d.getList("neighbourHints", new BdfList());
		for (Object o : hintList) {
			if (o instanceof String) hints.add((String) o);
		}
		List<org.zerionproject.app.api.channel.ChannelReaction>
				reactions = new ArrayList<>();
		BdfList reactionList = d.getList("reactions", new BdfList());
		for (Object o : reactionList) {
			if (!(o instanceof BdfDictionary)) continue;
			BdfDictionary rd = (BdfDictionary) o;
			byte[] rSig = rd.getOptionalRaw("sig");
			reactions.add(
					new org.zerionproject.app.api.channel.ChannelReaction(
							rd.getLong("seq"),
							rd.getString("emoji"),
							rd.getRaw("ed"),
							rd.getRaw("ml"),
							rd.getLong("ts"),
							rSig == null ? new byte[0] : rSig));
		}
		List<org.zerionproject.app.api.channel.ChannelComment>
				comments = new ArrayList<>();
		BdfList commentList = d.getList("comments", new BdfList());
		for (Object o : commentList) {
			if (!(o instanceof BdfDictionary)) continue;
			BdfDictionary cd = (BdfDictionary) o;
			byte[] cSig = cd.getOptionalRaw("sig");
			comments.add(
					new org.zerionproject.app.api.channel.ChannelComment(
							cd.getLong("seq"),
							cd.getLong("id"),
							cd.getString("body"),
							cd.getString("name"),
							cd.getRaw("ed"),
							cd.getRaw("ml"),
							cd.getLong("ts"),
							cSig == null ? new byte[0] : cSig));
		}
		return new PullResponse(manifest, posts, envelope, hints,
				reactions, comments);
	}

	BdfDictionary encodeManifest(byte[] channelId, byte[] salt,
			byte[] publisherEd25519, byte[] publisherMlDsa,
			String name, String description,
			@Nullable byte[] avatarHash, long createdAtHourMs,
			boolean publicChannel, @Nullable byte[] joinCapability,
			String currentOnion, long manifestSeq,
			@Nullable byte[] contentKeyHash,
			List<ChannelDelegationCert> activeDelegations,
			List<Long> revokedDelegationSeqs,
			long pinnedPostSeq, boolean requiresApproval,
			boolean discussionsEnabled, byte[] signature) {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_MANIFEST);
		d.put("channelId", channelId);
		d.put("salt", salt);
		d.put("publisherEd25519", publisherEd25519);
		d.put("publisherMlDsa", publisherMlDsa);
		d.put("name", name);
		d.put("description", description);
		if (avatarHash != null) d.put("avatarHash", avatarHash);
		d.put("createdAtHourMs", createdAtHourMs);
		d.put("publicChannel", publicChannel);
		if (joinCapability != null) d.put("joinCapability", joinCapability);
		d.put("currentOnion", currentOnion);
		d.put("manifestSeq", manifestSeq);
		if (contentKeyHash != null) {
			d.put("contentKeyHash", contentKeyHash);
		}
		BdfList delegList = new BdfList();
		for (ChannelDelegationCert c : activeDelegations) {
			delegList.add(certToWire(c));
		}
		d.put("activeDelegations", delegList);
		BdfList revList = new BdfList();
		for (Long seq : revokedDelegationSeqs) revList.add(seq);
		d.put("revokedDelegationSeqs", revList);
		d.put("pinnedPostSeq", pinnedPostSeq);
		d.put("requiresApproval", requiresApproval);
		if (ChannelConstants.DISCUSSIONS_IN_MANIFEST) {
			d.put("discussionsEnabled", discussionsEnabled);
		}
		d.put("signature", signature);
		return d;
	}

	private BdfDictionary postToWire(ChannelPost p) {
		BdfDictionary d = new BdfDictionary();
		d.put("seqNum", p.getSeqNum());
		d.put("prevHash", p.getPrevHash());
		d.put("timestampHourMs", p.getTimestampHourMs());
		d.put("body", p.getBody());
		d.put("ttlMs", p.getTtlMs());
		d.put("signature", p.getSignature());
		if (p.getDelegateSignerEd25519PubKey() != null) {
			d.put("delegateSignerEd25519",
					p.getDelegateSignerEd25519PubKey());
		}
		if (p.getDelegateSignerMlDsaPubKey() != null) {
			d.put("delegateSignerMlDsa",
					p.getDelegateSignerMlDsaPubKey());
		}
		BdfList atts = new BdfList();
		for (ChannelPost.ChannelAttachment a : p.getAttachments()) {
			BdfDictionary ad = new BdfDictionary();
			ad.put("hash", a.getBlobHash());
			ad.put("size", a.getSizeBytes());
			ad.put("mime", a.getMimeType());
			ad.put("key", a.getPerAttachmentKey());
			if (a.getThumbnail() != null) {
				ad.put("thumb", a.getThumbnail());
			}
			atts.add(ad);
		}
		d.put("attachments", atts);
		return d;
	}

	private ChannelPost wireToPost(byte[] channelId, BdfDictionary d)
			throws FormatException {
		List<ChannelPost.ChannelAttachment> atts = new ArrayList<>();
		BdfList rawAtts = d.getList("attachments", new BdfList());
		for (Object o : rawAtts) {
			if (!(o instanceof BdfDictionary)) continue;
			BdfDictionary ad = (BdfDictionary) o;
			atts.add(new ChannelPost.ChannelAttachment(
					ad.getRaw("hash"), ad.getLong("size"),
					ad.getString("mime"), ad.getRaw("key"), null,
					ad.getOptionalRaw("thumb")));
		}
		return new ChannelPost(channelId,
				d.getLong("seqNum"),
				d.getRaw("prevHash"),
				d.getLong("timestampHourMs"),
				d.getString("body"),
				atts,
				d.getLong("ttlMs"),
				d.getRaw("signature"),
				false,
				d.getOptionalRaw("delegateSignerEd25519"),
				d.getOptionalRaw("delegateSignerMlDsa"));
	}

	private BdfDictionary certToWire(ChannelDelegationCert c) {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_DELEGATION);
		d.put("channelId", c.getChannelId());
		d.put("delegateeEd25519", c.getDelegateeEd25519PubKey());
		d.put("delegateeMlDsa", c.getDelegateeMlDsaPubKey());
		d.put("validFromHourMs", c.getValidFromHourMs());
		d.put("validUntilHourMs", c.getValidUntilHourMs());
		d.put("delegationSeq", c.getDelegationSeq());
		d.put("signature", c.getSignature());
		return d;
	}

	/**
	 * Reads the optional capability challenge carried by a request. Returns
	 * null when either field is absent, which the publisher treats as an
	 * unauthenticated request.
	 */
	@javax.annotation.Nullable
	Challenge peekChallenge(byte[] data) throws IOException {
		BdfDictionary d = readDict(data);
		byte[] nonce = d.getOptionalRaw("nonce");
		byte[] hmac = d.getOptionalRaw("hmac");
		if (nonce == null || hmac == null) return null;
		return new Challenge(nonce, hmac);
	}

	static final class Challenge {
		final byte[] nonce;
		final byte[] hmac;

		Challenge(byte[] nonce, byte[] hmac) {
			this.nonce = nonce;
			this.hmac = hmac;
		}
	}

	private static void putChallenge(BdfDictionary d,
			@javax.annotation.Nullable byte[] nonce,
			@javax.annotation.Nullable byte[] hmac) {
		if (nonce != null && hmac != null) {
			d.put("nonce", nonce);
			d.put("hmac", hmac);
		}
	}

	byte[] encodeCommentRequest(byte[] channelId, long parentPostSeqNum,
			long commentId, String body, String authorName,
			long timestampHourMs, byte[] signerEd, byte[] signerMl,
			byte[] signature, @javax.annotation.Nullable byte[] nonce,
			@javax.annotation.Nullable byte[] hmac) throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_POST_COMMENT);
		putChallenge(d, nonce, hmac);
		d.put("channelId", channelId);
		d.put("seq", parentPostSeqNum);
		d.put("id", commentId);
		d.put("body", body);
		d.put("name", authorName);
		d.put("ts", timestampHourMs);
		d.put("ed", signerEd);
		d.put("ml", signerMl);
		d.put("sig", signature);
		return writeDict(d);
	}

	CommentRequest decodeCommentRequest(byte[] data) throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_POST_COMMENT.equals(type)) {
			throw new FormatException();
		}
		return new CommentRequest(d.getRaw("channelId"),
				d.getLong("seq"),
				d.getLong("id"),
				d.getString("body"),
				d.getString("name"),
				d.getLong("ts"),
				d.getRaw("ed"),
				d.getRaw("ml"),
				d.getRaw("sig"));
	}

	byte[] encodeCommentAck(boolean ok) throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_COMMENT_ACK);
		d.put("ok", ok);
		return writeDict(d);
	}

	boolean decodeCommentAck(byte[] data) throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_COMMENT_ACK.equals(type)) {
			throw new FormatException();
		}
		return d.getBoolean("ok", false);
	}

	@NotNullByDefault
	static final class CommentRequest {
		final byte[] channelId;
		final long parentPostSeqNum;
		final long commentId;
		final String body;
		final String authorName;
		final long timestampHourMs;
		final byte[] signerEd25519;
		final byte[] signerMlDsa;
		final byte[] signature;

		CommentRequest(byte[] channelId, long parentPostSeqNum,
				long commentId, String body, String authorName,
				long timestampHourMs, byte[] signerEd25519,
				byte[] signerMlDsa, byte[] signature) {
			this.channelId = channelId;
			this.parentPostSeqNum = parentPostSeqNum;
			this.commentId = commentId;
			this.body = body;
			this.authorName = authorName;
			this.timestampHourMs = timestampHourMs;
			this.signerEd25519 = signerEd25519;
			this.signerMlDsa = signerMlDsa;
			this.signature = signature;
		}
	}

	byte[] encodeAnnounceRequest(byte[] channelId, String displayName,
			long timestampHourMs, byte[] signerEd, byte[] signerMl,
			byte[] signature, @javax.annotation.Nullable byte[] nonce,
			@javax.annotation.Nullable byte[] hmac) throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_ANNOUNCE);
		putChallenge(d, nonce, hmac);
		d.put("channelId", channelId);
		d.put("name", displayName);
		d.put("ts", timestampHourMs);
		d.put("ed", signerEd);
		d.put("ml", signerMl);
		d.put("sig", signature);
		return writeDict(d);
	}

	AnnounceRequest decodeAnnounceRequest(byte[] data) throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_ANNOUNCE.equals(type)) {
			throw new FormatException();
		}
		return new AnnounceRequest(d.getRaw("channelId"),
				d.getString("name"),
				d.getLong("ts"),
				d.getRaw("ed"),
				d.getRaw("ml"),
				d.getRaw("sig"));
	}

	byte[] encodeAnnounceAck(boolean ok) throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_ANNOUNCE_ACK);
		d.put("ok", ok);
		return writeDict(d);
	}

	@NotNullByDefault
	static final class AnnounceRequest {
		final byte[] channelId;
		final String displayName;
		final long timestampHourMs;
		final byte[] signerEd25519;
		final byte[] signerMlDsa;
		final byte[] signature;

		AnnounceRequest(byte[] channelId, String displayName,
				long timestampHourMs, byte[] signerEd25519,
				byte[] signerMlDsa, byte[] signature) {
			this.channelId = channelId;
			this.displayName = displayName;
			this.timestampHourMs = timestampHourMs;
			this.signerEd25519 = signerEd25519;
			this.signerMlDsa = signerMlDsa;
			this.signature = signature;
		}
	}

	byte[] encodeReactionRequest(byte[] channelId, long postSeqNum,
			String emoji, long timestampHourMs,
			byte[] signerEd25519, byte[] signerMlDsa, byte[] signature,
			@javax.annotation.Nullable byte[] nonce,
			@javax.annotation.Nullable byte[] hmac)
			throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_POST_REACTION);
		putChallenge(d, nonce, hmac);
		d.put("channelId", channelId);
		d.put("seq", postSeqNum);
		d.put("emoji", emoji);
		d.put("ts", timestampHourMs);
		d.put("ed", signerEd25519);
		d.put("ml", signerMlDsa);
		d.put("sig", signature);
		return writeDict(d);
	}

	ReactionRequest decodeReactionRequest(byte[] data) throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_POST_REACTION.equals(type)) {
			throw new FormatException();
		}
		return new ReactionRequest(d.getRaw("channelId"),
				d.getLong("seq"),
				d.getString("emoji"),
				d.getLong("ts"),
				d.getRaw("ed"),
				d.getRaw("ml"),
				d.getRaw("sig"));
	}

	byte[] encodeReactionAck(boolean ok) throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_REACTION_ACK);
		d.put("ok", ok);
		return writeDict(d);
	}

	boolean decodeReactionAck(byte[] data) throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_REACTION_ACK.equals(type)) {
			throw new FormatException();
		}
		return d.getBoolean("ok", false);
	}

	@NotNullByDefault
	static final class ReactionRequest {
		final byte[] channelId;
		final long postSeqNum;
		final String emoji;
		final long timestampHourMs;
		final byte[] signerEd25519;
		final byte[] signerMlDsa;
		final byte[] signature;

		ReactionRequest(byte[] channelId, long postSeqNum, String emoji,
				long timestampHourMs, byte[] signerEd25519,
				byte[] signerMlDsa, byte[] signature) {
			this.channelId = channelId;
			this.postSeqNum = postSeqNum;
			this.emoji = emoji;
			this.timestampHourMs = timestampHourMs;
			this.signerEd25519 = signerEd25519;
			this.signerMlDsa = signerMlDsa;
			this.signature = signature;
		}
	}

	byte[] encodeApplyRequest(byte[] channelId, String displayName,
			long timestampHourMs, byte[] signerEd, byte[] signerMl,
			byte[] ephemeralAgreementPub, byte[] signature)
			throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN);
		d.put("channelId", channelId);
		d.put("name", displayName);
		d.put("ts", timestampHourMs);
		d.put("ed", signerEd);
		d.put("ml", signerMl);
		d.put("eph", ephemeralAgreementPub);
		d.put("sig", signature);
		return writeDict(d);
	}

	ApplyRequest decodeApplyRequest(byte[] data) throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_APPLY_TO_JOIN.equals(type)) {
			throw new FormatException();
		}
		return new ApplyRequest(d.getRaw("channelId"),
				d.getString("name"),
				d.getLong("ts"),
				d.getRaw("ed"),
				d.getRaw("ml"),
				d.getRaw("eph"),
				d.getRaw("sig"));
	}

	byte[] encodeApplyAck(boolean ok) throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_APPLY_ACK);
		d.put("ok", ok);
		return writeDict(d);
	}

	byte[] encodeCheckApprovalRequest(byte[] channelId,
			long timestampHourMs, byte[] signerEd, byte[] signerMl,
			byte[] signature) throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_CHECK_APPROVAL);
		d.put("channelId", channelId);
		d.put("ts", timestampHourMs);
		d.put("ed", signerEd);
		d.put("ml", signerMl);
		d.put("sig", signature);
		return writeDict(d);
	}

	CheckApprovalRequest decodeCheckApprovalRequest(byte[] data)
			throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_CHECK_APPROVAL.equals(type)) {
			throw new FormatException();
		}
		return new CheckApprovalRequest(d.getRaw("channelId"),
				d.getLong("ts"),
				d.getRaw("ed"),
				d.getRaw("ml"),
				d.getRaw("sig"));
	}

	byte[] encodeApprovalResponse(String status,
			@Nullable byte[] kemCt, @Nullable byte[] envelope)
			throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_APPROVAL_RESPONSE);
		d.put("status", status);
		if (kemCt != null) d.put("kemCt", kemCt);
		if (envelope != null) d.put("envelope", envelope);
		return writeDict(d);
	}

	ApprovalResponse decodeApprovalResponse(byte[] data)
			throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_APPROVAL_RESPONSE.equals(type)) {
			throw new FormatException();
		}
		return new ApprovalResponse(d.getString("status"),
				d.getOptionalRaw("kemCt"),
				d.getOptionalRaw("envelope"));
	}

	@NotNullByDefault
	static final class ApplyRequest {
		final byte[] channelId;
		final String displayName;
		final long timestampHourMs;
		final byte[] signerEd25519;
		final byte[] signerMlDsa;
		final byte[] ephemeralAgreementPub;
		final byte[] signature;

		ApplyRequest(byte[] channelId, String displayName,
				long timestampHourMs, byte[] signerEd25519,
				byte[] signerMlDsa, byte[] ephemeralAgreementPub,
				byte[] signature) {
			this.channelId = channelId;
			this.displayName = displayName;
			this.timestampHourMs = timestampHourMs;
			this.signerEd25519 = signerEd25519;
			this.signerMlDsa = signerMlDsa;
			this.ephemeralAgreementPub = ephemeralAgreementPub;
			this.signature = signature;
		}
	}

	@NotNullByDefault
	static final class CheckApprovalRequest {
		final byte[] channelId;
		final long timestampHourMs;
		final byte[] signerEd25519;
		final byte[] signerMlDsa;
		final byte[] signature;

		CheckApprovalRequest(byte[] channelId, long timestampHourMs,
				byte[] signerEd25519, byte[] signerMlDsa,
				byte[] signature) {
			this.channelId = channelId;
			this.timestampHourMs = timestampHourMs;
			this.signerEd25519 = signerEd25519;
			this.signerMlDsa = signerMlDsa;
			this.signature = signature;
		}
	}

	@NotNullByDefault
	static final class ApprovalResponse {
		final String status;
		@Nullable
		final byte[] kemCt;
		@Nullable
		final byte[] envelope;

		ApprovalResponse(String status, @Nullable byte[] kemCt,
				@Nullable byte[] envelope) {
			this.status = status;
			this.kemCt = kemCt;
			this.envelope = envelope;
		}
	}

	byte[] encodeAttachmentRequest(byte[] channelId, byte[] blobHash,
			@javax.annotation.Nullable byte[] nonce,
			@javax.annotation.Nullable byte[] hmac)
			throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_GET_ATTACHMENT);
		putChallenge(d, nonce, hmac);
		d.put("channelId", channelId);
		d.put("blobHash", blobHash);
		return writeDict(d);
	}

	AttachmentRequest decodeAttachmentRequest(byte[] data)
			throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_GET_ATTACHMENT.equals(type)) {
			throw new FormatException();
		}
		return new AttachmentRequest(d.getRaw("channelId"),
				d.getRaw("blobHash"));
	}

	byte[] encodeAttachmentResponse(byte[] blobHash, byte[] blob)
			throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_ATTACHMENT_BLOB);
		d.put("blobHash", blobHash);
		d.put("blob", blob);
		return writeDict(d);
	}

	AttachmentResponse decodeAttachmentResponse(byte[] data)
			throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_ATTACHMENT_BLOB.equals(type)) {
			throw new FormatException();
		}
		return new AttachmentResponse(d.getRaw("blobHash"),
				d.getRaw("blob"));
	}

	@NotNullByDefault
	static final class AttachmentRequest {
		final byte[] channelId;
		final byte[] blobHash;

		AttachmentRequest(byte[] channelId, byte[] blobHash) {
			this.channelId = channelId;
			this.blobHash = blobHash;
		}
	}

	@NotNullByDefault
	static final class AttachmentResponse {
		final byte[] blobHash;
		final byte[] blob;

		AttachmentResponse(byte[] blobHash, byte[] blob) {
			this.blobHash = blobHash;
			this.blob = blob;
		}
	}

	String peekType(byte[] data) {
		try {
			BdfDictionary d = readDict(data);
			return d.getString("type");
		} catch (IOException e) {
			return "";
		}
	}

	byte[] encodeTombstone(byte[] channelId, long timestampHourMs,
			byte[] hybridSig) throws IOException {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_CHANNEL_TOMBSTONE);
		d.put("channelId", channelId);
		d.put("ts", timestampHourMs);
		d.put("sig", hybridSig);
		return writeDict(d);
	}

	Tombstone decodeTombstone(byte[] data) throws IOException {
		BdfDictionary d = readDict(data);
		String type = d.getString("type");
		if (!ChannelConstants.WIRE_TYPE_CHANNEL_TOMBSTONE.equals(type)) {
			throw new FormatException();
		}
		return new Tombstone(d.getRaw("channelId"),
				d.getLong("ts"),
				d.getRaw("sig"));
	}

	@NotNullByDefault
	static final class Tombstone {
		final byte[] channelId;
		final long timestampHourMs;
		final byte[] hybridSig;

		Tombstone(byte[] channelId, long timestampHourMs,
				byte[] hybridSig) {
			this.channelId = channelId;
			this.timestampHourMs = timestampHourMs;
			this.hybridSig = hybridSig;
		}
	}

	private byte[] writeDict(BdfDictionary d) throws IOException {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = writerFactory.createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}

	private BdfDictionary readDict(byte[] data) throws IOException {
		BdfReader r = readerFactory.createReader(
				new ByteArrayInputStream(data));
		return r.readDictionary();
	}

	@NotNullByDefault
	static final class PullRequest {
		final byte[] channelId;
		final long sinceSeqNum;
		@Nullable
		final byte[] hmacResponse;
		@Nullable
		final byte[] nonce;

		PullRequest(byte[] channelId, long sinceSeqNum,
				@Nullable byte[] hmacResponse, @Nullable byte[] nonce) {
			this.channelId = channelId;
			this.sinceSeqNum = sinceSeqNum;
			this.hmacResponse = hmacResponse;
			this.nonce = nonce;
		}
	}

	@NotNullByDefault
	static final class PullResponse {
		final BdfDictionary manifest;
		final List<ChannelPost> newPosts;
		@Nullable
		final byte[] contentKeyEnvelope;
		final List<String> neighbourHints;
		final List<org.zerionproject.app.api.channel.ChannelReaction>
				reactions;
		final List<org.zerionproject.app.api.channel.ChannelComment>
				comments;

		PullResponse(BdfDictionary manifest, List<ChannelPost> newPosts,
				@Nullable byte[] contentKeyEnvelope,
				List<String> neighbourHints,
				List<org.zerionproject.app.api.channel.ChannelReaction>
						reactions,
				List<org.zerionproject.app.api.channel.ChannelComment>
						comments) {
			this.manifest = manifest;
			this.newPosts = newPosts;
			this.contentKeyEnvelope = contentKeyEnvelope;
			this.neighbourHints = neighbourHints;
			this.reactions = reactions;
			this.comments = comments;
		}
	}
}

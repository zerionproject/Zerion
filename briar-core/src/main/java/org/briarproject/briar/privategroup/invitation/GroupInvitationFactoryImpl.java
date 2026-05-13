package org.briarproject.briar.privategroup.invitation;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.crypto.CryptoComponent;
import org.briarproject.bramble.api.crypto.HybridSignaturePrivateKey;
import org.briarproject.bramble.api.crypto.PrivateKey;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.identity.AuthorId;
import org.briarproject.bramble.api.identity.IdentityManager;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.briar.api.privategroup.invitation.GroupInvitationFactory;
import org.briarproject.nullsafety.NotNullByDefault;

import java.security.GeneralSecurityException;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager.CLIENT_ID;
import static org.briarproject.briar.api.privategroup.invitation.GroupInvitationManager.MAJOR_VERSION;

@Immutable
@NotNullByDefault
class GroupInvitationFactoryImpl implements GroupInvitationFactory {

	private final ContactGroupFactory contactGroupFactory;
	private final ClientHelper clientHelper;
	private final CryptoComponent crypto;
	private final IdentityManager identityManager;

	@Inject
	GroupInvitationFactoryImpl(ContactGroupFactory contactGroupFactory,
			ClientHelper clientHelper, CryptoComponent crypto,
			IdentityManager identityManager) {
		this.contactGroupFactory = contactGroupFactory;
		this.clientHelper = clientHelper;
		this.crypto = crypto;
		this.identityManager = identityManager;
	}

	private static final java.util.logging.Logger DIAG =
			java.util.logging.Logger.getLogger("ZN-DIAG-INVITE-SIGN");

	@Override
	public byte[] signInvitation(Contact c, GroupId privateGroupId,
			long timestamp, PrivateKey privateKey) {
		AuthorId creatorId = c.getLocalAuthorId();
		AuthorId memberId = c.getAuthor().getId();
		BdfList token = createInviteToken(creatorId, memberId, privateGroupId,
				timestamp);
		try {
			byte[] mlDsaPriv = identityManager.getLocalMlDsaSigPrivateKey();
			DIAG.info("signInvitation ts=" + timestamp
					+ " mlDsaPriv=" + (mlDsaPriv == null ? "null"
							: ("present:" + mlDsaPriv.length + "B"))
					+ " edPriv.len=" + privateKey.getEncoded().length);
			if (mlDsaPriv != null) {
				byte[] signedBytes = clientHelper.toByteArray(token);
				DIAG.info("signInvitation signedBytes.len=" + signedBytes.length
						+ " sha256-prefix=" + java.util.HexFormat.of()
								.formatHex(java.util.Arrays.copyOf(
										java.security.MessageDigest
												.getInstance("SHA-256")
												.digest(signedBytes), 8)));
				HybridSignaturePrivateKey hybridKey =
						new HybridSignaturePrivateKey(privateKey.getEncoded(),
								mlDsaPriv);
				byte[] sig = crypto.hybridSign(SIGNING_LABEL_INVITE,
						signedBytes, hybridKey);
				DIAG.info("signInvitation EMITTED sig.len=" + sig.length);
				return sig;
			}
			DIAG.info("signInvitation FALLBACK to Ed25519-only (no ML-DSA)");
			return clientHelper.sign(SIGNING_LABEL_INVITE, token, privateKey);
		} catch (GeneralSecurityException e) {
			DIAG.severe("signInvitation FAILED: " + e);
			throw new IllegalArgumentException(e);
		} catch (FormatException e) {
			throw new AssertionError(e);
		} catch (org.briarproject.bramble.api.db.DbException e) {
			throw new IllegalStateException(e);
		}
	}

	@Override
	public BdfList createInviteToken(AuthorId creatorId, AuthorId memberId,
			GroupId privateGroupId, long timestamp) {
		Group contactGroup = contactGroupFactory.createContactGroup(CLIENT_ID,
				MAJOR_VERSION, creatorId, memberId);
		return BdfList.of(
				timestamp,
				contactGroup.getId(),
				privateGroupId
		);
	}

}

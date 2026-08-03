package org.zerionproject.app.api.channel;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

@NotNullByDefault
public final class ChannelApplication {

	public enum Status {
		PENDING, APPROVED, DENIED
	}

	private final String displayName;
	private final byte[] applicantEd25519;
	private final byte[] applicantMlDsa;
	private final byte[] applicantEphemeralAgreementPub;
	private final long appliedAtHourMs;
	private final Status status;
	@Nullable
	private final byte[] kemCiphertext;
	@Nullable
	private final byte[] envelope;

	public ChannelApplication(String displayName, byte[] applicantEd25519,
			byte[] applicantMlDsa, byte[] applicantEphemeralAgreementPub,
			long appliedAtHourMs, Status status,
			@Nullable byte[] kemCiphertext,
			@Nullable byte[] envelope) {
		this.displayName = displayName;
		this.applicantEd25519 = applicantEd25519;
		this.applicantMlDsa = applicantMlDsa;
		this.applicantEphemeralAgreementPub =
				applicantEphemeralAgreementPub;
		this.appliedAtHourMs = appliedAtHourMs;
		this.status = status;
		this.kemCiphertext = kemCiphertext;
		this.envelope = envelope;
	}

	public String getDisplayName() {
		return displayName;
	}

	public byte[] getApplicantEd25519() {
		return applicantEd25519;
	}

	public byte[] getApplicantMlDsa() {
		return applicantMlDsa;
	}

	public byte[] getApplicantEphemeralAgreementPub() {
		return applicantEphemeralAgreementPub;
	}

	public long getAppliedAtHourMs() {
		return appliedAtHourMs;
	}

	public Status getStatus() {
		return status;
	}

	@Nullable
	public byte[] getKemCiphertext() {
		return kemCiphertext;
	}

	@Nullable
	public byte[] getEnvelope() {
		return envelope;
	}
}

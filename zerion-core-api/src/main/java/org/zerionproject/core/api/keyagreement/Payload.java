package org.zerionproject.core.api.keyagreement;

import org.zerionproject.core.api.Bytes;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.List;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class Payload implements Comparable<Payload> {

	private final Bytes commitment;
	private final List<TransportDescriptor> descriptors;
	private final boolean keySeparation;

	public Payload(byte[] commitment, List<TransportDescriptor> descriptors) {
		this(commitment, descriptors, false);
	}

	public Payload(byte[] commitment, List<TransportDescriptor> descriptors,
			boolean keySeparation) {
		this.commitment = new Bytes(commitment);
		this.descriptors = descriptors;
		this.keySeparation = keySeparation;
	}

	public boolean offersKeySeparation() {
		return keySeparation;
	}

	public Payload withoutCapabilities() {
		if (!keySeparation) return this;
		return new Payload(getCommitment(), descriptors, false);
	}

	public byte[] getCommitment() {
		return commitment.getBytes();
	}

	public List<TransportDescriptor> getTransportDescriptors() {
		return descriptors;
	}

	@Override
	public int compareTo(Payload p) {
		return commitment.compareTo(p.commitment);
	}
}

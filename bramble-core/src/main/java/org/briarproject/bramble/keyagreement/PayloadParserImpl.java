package org.briarproject.bramble.keyagreement;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UnsupportedVersionException;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.BdfReader;
import org.briarproject.bramble.api.data.BdfReaderFactory;
import org.briarproject.bramble.api.keyagreement.Payload;
import org.briarproject.bramble.api.keyagreement.PayloadParser;
import org.briarproject.bramble.api.keyagreement.TransportDescriptor;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static org.briarproject.bramble.api.keyagreement.KeyAgreementConstants.COMMIT_LENGTH;
import static org.briarproject.bramble.api.keyagreement.KeyAgreementConstants.PROTOCOL_VERSION;
import static org.briarproject.bramble.util.StringUtils.ISO_8859_1;

@Immutable
@NotNullByDefault
class PayloadParserImpl implements PayloadParser {

	private static final int FORMAT_ID_MASK = 0xE0;
	private static final int VERSION_MASK = 0x1F;
	private static final int BQP_FORMAT_ID = 0;

	private final BdfReaderFactory bdfReaderFactory;

	@Inject
	PayloadParserImpl(BdfReaderFactory bdfReaderFactory) {
		this.bdfReaderFactory = bdfReaderFactory;
	}

	@Override
	public Payload parse(String payloadString) throws IOException {
		if (payloadString.isEmpty()) throw new FormatException();
		byte[] raw = payloadString.getBytes(ISO_8859_1);
		ByteArrayInputStream in = new ByteArrayInputStream(raw);
		int firstByte = in.read();
		if (firstByte == -1) throw new FormatException();
		int formatId = (firstByte & FORMAT_ID_MASK) >> 5;
		int formatVersion = firstByte & VERSION_MASK;
		if (formatId != BQP_FORMAT_ID) throw new FormatException();
		if (formatVersion != PROTOCOL_VERSION) {
			boolean tooOld = formatVersion < PROTOCOL_VERSION;
			throw new UnsupportedVersionException(tooOld);
		}
		BdfReader r = bdfReaderFactory.createReader(in);
		BdfList payload = r.readList();
		if (payload.isEmpty()) throw new FormatException();
		if (!r.eof()) throw new FormatException();
		byte[] commitment = payload.getRaw(0);
		if (commitment.length != COMMIT_LENGTH) throw new FormatException();
		List<TransportDescriptor> recognised = new ArrayList<>();
		return new Payload(commitment, recognised);
	}
}

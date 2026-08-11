package org.zerionproject.core.sync;

import org.zerionproject.core.BrambleCoreIntegrationTestEagerSingletons;
import org.zerionproject.core.api.contact.ContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.crypto.TransportCrypto;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.sync.Ack;
import org.zerionproject.core.api.sync.ClientId;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupFactory;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageFactory;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.sync.Offer;
import org.zerionproject.core.api.sync.Request;
import org.zerionproject.core.api.sync.SyncRecordReader;
import org.zerionproject.core.api.sync.SyncRecordReaderFactory;
import org.zerionproject.core.api.sync.SyncRecordWriter;
import org.zerionproject.core.api.sync.SyncRecordWriterFactory;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.api.transport.StreamReaderFactory;
import org.zerionproject.core.api.transport.StreamWriter;
import org.zerionproject.core.api.transport.StreamWriterFactory;
import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collection;

import javax.inject.Inject;

import static org.zerionproject.core.api.sync.SyncConstants.MAX_GROUP_DESCRIPTOR_LENGTH;
import static org.zerionproject.core.api.transport.TransportConstants.PROTOCOL_VERSION;
import static org.zerionproject.core.api.transport.TransportConstants.TAG_LENGTH;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getContactId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTransportId;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SyncIntegrationTest extends BrambleTestCase {

	@Inject
	GroupFactory groupFactory;
	@Inject
	MessageFactory messageFactory;
	@Inject
	StreamReaderFactory streamReaderFactory;
	@Inject
	StreamWriterFactory streamWriterFactory;
	@Inject
	SyncRecordReaderFactory recordReaderFactory;
	@Inject
	SyncRecordWriterFactory recordWriterFactory;
	@Inject
	TransportCrypto transportCrypto;

	private final ContactId contactId;
	private final TransportId transportId;
	private final SecretKey tagKey, headerKey;
	private final long streamNumber;
	private final Message message, message1;
	private final Collection<MessageId> messageIds;

	public SyncIntegrationTest() throws Exception {

		SyncIntegrationTestComponent component =
				DaggerSyncIntegrationTestComponent.builder().build();
		BrambleCoreIntegrationTestEagerSingletons.Helper
				.injectEagerSingletons(component);
		component.inject(this);

		contactId = getContactId();
		transportId = getTransportId();

		tagKey = getSecretKey();
		headerKey = getSecretKey();
		streamNumber = 123;

		ClientId clientId = getClientId();
		int majorVersion = 1234567890;
		byte[] descriptor = new byte[MAX_GROUP_DESCRIPTOR_LENGTH];
		Group group = groupFactory.createGroup(clientId, majorVersion,
				descriptor);

		long timestamp = System.currentTimeMillis();
		byte[] body = "Hello world".getBytes("UTF-8");
		message = messageFactory.createMessage(group.getId(), timestamp, body);
		message1 = messageFactory.createMessage(group.getId(), timestamp, body);
		messageIds = Arrays.asList(message.getId(), message1.getId());
	}

	@Test
	public void testWriteAndRead() throws Exception {
		read(write());
	}

	private byte[] write() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		StreamContext ctx = new StreamContext(contactId, null, transportId,
				tagKey, headerKey, streamNumber, false);
		StreamWriter streamWriter = streamWriterFactory.createStreamWriter(out,
				ctx);
		SyncRecordWriter recordWriter = recordWriterFactory.createRecordWriter(
				streamWriter.getOutputStream());

		recordWriter.writeAck(new Ack(messageIds));
		recordWriter.writeMessage(message);
		recordWriter.writeMessage(message1);
		recordWriter.writeOffer(new Offer(messageIds));
		recordWriter.writeRequest(new Request(messageIds));

		streamWriter.sendEndOfStream();
		return out.toByteArray();
	}

	private void read(byte[] connectionData) throws Exception {

		byte[] expectedTag = new byte[TAG_LENGTH];
		transportCrypto.encodeTag(expectedTag, tagKey, PROTOCOL_VERSION,
				streamNumber);

		InputStream in = new ByteArrayInputStream(connectionData);
		byte[] tag = new byte[TAG_LENGTH];
		assertEquals(TAG_LENGTH, in.read(tag, 0, TAG_LENGTH));
		assertArrayEquals(expectedTag, tag);

		StreamContext ctx = new StreamContext(contactId, null, transportId,
				tagKey, headerKey, streamNumber, false);
		InputStream streamReader = streamReaderFactory.createStreamReader(in,
				ctx);
		SyncRecordReader recordReader = recordReaderFactory.createRecordReader(
				streamReader);

		assertTrue(recordReader.hasAck());
		Ack a = recordReader.readAck();
		assertEquals(messageIds, a.getMessageIds());

		assertTrue(recordReader.hasMessage());
		Message m = recordReader.readMessage();
		checkMessageEquality(message, m);
		assertTrue(recordReader.hasMessage());
		m = recordReader.readMessage();
		checkMessageEquality(message1, m);
		assertFalse(recordReader.hasMessage());

		assertTrue(recordReader.hasOffer());
		Offer o = recordReader.readOffer();
		assertEquals(messageIds, o.getMessageIds());

		assertTrue(recordReader.hasRequest());
		Request req = recordReader.readRequest();
		assertEquals(messageIds, req.getMessageIds());

		in.close();
	}

	private void checkMessageEquality(Message m1, Message m2) {
		assertArrayEquals(m1.getId().getBytes(), m2.getId().getBytes());
		assertArrayEquals(m1.getGroupId().getBytes(),
				m2.getGroupId().getBytes());
		assertEquals(m1.getTimestamp(), m2.getTimestamp());
		assertEquals(m1.getRawLength(), m2.getRawLength());
		assertArrayEquals(m1.getBody(), m2.getBody());
	}
}

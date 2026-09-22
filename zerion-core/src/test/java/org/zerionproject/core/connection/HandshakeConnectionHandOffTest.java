package org.zerionproject.core.connection;

import org.zerionproject.core.api.connection.ConnectionRegistry;
import org.zerionproject.core.api.contact.Contact;
import org.zerionproject.core.api.contact.ContactExchangeManager;
import org.zerionproject.core.api.contact.HandshakeManager;
import org.zerionproject.core.api.contact.HandshakeManager.HandshakeResult;
import org.zerionproject.core.api.contact.PendingContactId;
import org.zerionproject.core.api.crypto.SecretKey;
import org.zerionproject.core.api.plugin.TransportId;
import org.zerionproject.core.api.transport.KeyManager;
import org.zerionproject.core.api.transport.StreamContext;
import org.zerionproject.core.api.transport.StreamReaderFactory;
import org.zerionproject.core.api.transport.StreamWriter;
import org.zerionproject.core.api.transport.StreamWriterFactory;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.TestDuplexTransportConnection;
import org.zerionproject.transport.ZtpConnectionHandler;
import org.jmock.Expectations;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import static org.zerionproject.core.api.transport.TransportConstants.TAG_LENGTH;
import static org.zerionproject.core.test.TestUtils.getContact;
import static org.zerionproject.core.test.TestUtils.getRandomBytes;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.test.TestUtils.getSecretKey;
import static org.zerionproject.core.test.TestUtils.getTransportId;

/**
 * After a pairing completes, the socket it ran on goes to the
 * established-contact handler with the new contact's id, dialled or accepted
 * according to the pairing direction. The handshake and the exchange still run
 * over the pending contact's classical streams, and no stream context is ever
 * requested for the contact itself: the rotation-key sync path is not used.
 */
public class HandshakeConnectionHandOffTest extends BrambleMockTestCase {

	private final KeyManager keyManager = context.mock(KeyManager.class);
	private final ConnectionRegistry connectionRegistry =
			context.mock(ConnectionRegistry.class);
	private final StreamReaderFactory streamReaderFactory =
			context.mock(StreamReaderFactory.class);
	private final StreamWriterFactory streamWriterFactory =
			context.mock(StreamWriterFactory.class);
	private final HandshakeManager handshakeManager =
			context.mock(HandshakeManager.class);
	private final ContactExchangeManager contactExchangeManager =
			context.mock(ContactExchangeManager.class);
	private final ZtpConnectionHandler connectionHandler =
			context.mock(ZtpConnectionHandler.class);
	private final StreamWriter streamWriter = context.mock(StreamWriter.class);

	private final PendingContactId pendingContactId =
			new PendingContactId(getRandomId());
	private final TransportId transportId = getTransportId();
	private final SecretKey masterKey = getSecretKey();
	private final Contact contact = getContact();
	private final byte[] tag = getRandomBytes(TAG_LENGTH);
	private final StreamContext ctxOut = handshakeContext();
	private final StreamContext ctxIn = handshakeContext();
	private final ByteArrayOutputStream socketOut = new ByteArrayOutputStream();
	private final InputStream socketIn = new ByteArrayInputStream(tag);
	private final TestDuplexTransportConnection connection =
			new TestDuplexTransportConnection(socketIn, socketOut);

	@Test
	public void outgoingPairingHandsTheSocketToTheContactHandlerAsDialled()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(keyManager).getStreamContext(pendingContactId, transportId);
			will(returnValue(ctxOut));
			oneOf(streamWriterFactory).createStreamWriter(
					with(any(OutputStream.class)), with(ctxOut));
			will(returnValue(streamWriter));
			allowing(streamWriter).getOutputStream();
			will(returnValue(socketOut));
			oneOf(keyManager).getStreamContext(with(transportId),
					with(equal(tag)));
			will(returnValue(ctxIn));
			oneOf(connectionRegistry).registerConnection(pendingContactId);
			will(returnValue(true));
			oneOf(streamReaderFactory).createStreamReader(
					with(any(InputStream.class)), with(ctxIn));
			will(returnValue(socketIn));
			oneOf(handshakeManager).handshake(pendingContactId, socketIn,
					streamWriter);
			will(returnValue(new HandshakeResult(masterKey, true, true)));
			oneOf(contactExchangeManager).exchangeContacts(pendingContactId,
					connection, masterKey, true, true, false, null, null, null,
					null);
			will(returnValue(contact));
			oneOf(connectionRegistry).unregisterConnection(pendingContactId,
					true);
			oneOf(connectionHandler).handlePaired(with(transportId),
					with(contact.getId().getInt()), with(false),
					with(any(InputStream.class)), with(any(OutputStream.class)));
		}});

		new OutgoingHandshakeConnection(keyManager, connectionRegistry,
				streamReaderFactory, streamWriterFactory, handshakeManager,
				contactExchangeManager, connectionHandler, Runnable::run,
				pendingContactId, transportId, connection, false).run();
	}

	@Test
	public void incomingPairingHandsTheSocketToTheContactHandlerAsAccepted()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(keyManager).getStreamContext(with(transportId),
					with(equal(tag)));
			will(returnValue(ctxIn));
			oneOf(keyManager).getStreamContext(pendingContactId, transportId);
			will(returnValue(ctxOut));
			oneOf(connectionRegistry).registerConnection(pendingContactId);
			will(returnValue(true));
			oneOf(streamReaderFactory).createStreamReader(
					with(any(InputStream.class)), with(ctxIn));
			will(returnValue(socketIn));
			oneOf(streamWriterFactory).createStreamWriter(
					with(any(OutputStream.class)), with(ctxOut));
			will(returnValue(streamWriter));
			allowing(streamWriter).getOutputStream();
			will(returnValue(socketOut));
			oneOf(handshakeManager).handshake(pendingContactId, socketIn,
					streamWriter);
			will(returnValue(new HandshakeResult(masterKey, false, true)));
			oneOf(contactExchangeManager).exchangeContacts(pendingContactId,
					connection, masterKey, false, true, false, null, null, null,
					null);
			will(returnValue(contact));
			oneOf(connectionRegistry).unregisterConnection(pendingContactId,
					true);
			oneOf(connectionHandler).handlePaired(with(transportId),
					with(contact.getId().getInt()), with(true),
					with(any(InputStream.class)), with(any(OutputStream.class)));
		}});

		new IncomingHandshakeConnection(keyManager, connectionRegistry,
				streamReaderFactory, streamWriterFactory, handshakeManager,
				contactExchangeManager, connectionHandler, Runnable::run,
				pendingContactId, transportId, connection, false).run();
	}

	@Test
	public void handlerFailureAfterPairingIsContainedAndTheSocketIsDisposed()
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(keyManager).getStreamContext(pendingContactId, transportId);
			will(returnValue(ctxOut));
			oneOf(streamWriterFactory).createStreamWriter(
					with(any(OutputStream.class)), with(ctxOut));
			will(returnValue(streamWriter));
			allowing(streamWriter).getOutputStream();
			will(returnValue(socketOut));
			oneOf(keyManager).getStreamContext(with(transportId),
					with(equal(tag)));
			will(returnValue(ctxIn));
			oneOf(connectionRegistry).registerConnection(pendingContactId);
			will(returnValue(true));
			oneOf(streamReaderFactory).createStreamReader(
					with(any(InputStream.class)), with(ctxIn));
			will(returnValue(socketIn));
			oneOf(handshakeManager).handshake(pendingContactId, socketIn,
					streamWriter);
			will(returnValue(new HandshakeResult(masterKey, true, true)));
			oneOf(contactExchangeManager).exchangeContacts(pendingContactId,
					connection, masterKey, true, true, false, null, null, null,
					null);
			will(returnValue(contact));
			oneOf(connectionRegistry).unregisterConnection(pendingContactId,
					true);
			oneOf(connectionHandler).handlePaired(with(transportId),
					with(contact.getId().getInt()), with(false),
					with(any(InputStream.class)), with(any(OutputStream.class)));
			will(throwException(new IOException()));
		}});

		new OutgoingHandshakeConnection(keyManager, connectionRegistry,
				streamReaderFactory, streamWriterFactory, handshakeManager,
				contactExchangeManager, connectionHandler, Runnable::run,
				pendingContactId, transportId, connection, false).run();
	}

	private StreamContext handshakeContext() {
		return new StreamContext(null, pendingContactId, transportId,
				getSecretKey(), getSecretKey(), 0L, true, false, false, null,
				null);
	}
}

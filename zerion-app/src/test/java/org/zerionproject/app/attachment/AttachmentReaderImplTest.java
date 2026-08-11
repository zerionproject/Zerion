package org.zerionproject.app.attachment;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfEntry;
import org.zerionproject.core.api.db.DatabaseComponent;
import org.zerionproject.core.api.db.NoSuchMessageException;
import org.zerionproject.core.api.db.Transaction;
import org.zerionproject.core.api.db.TransactionManager;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.test.BrambleMockTestCase;
import org.zerionproject.core.test.DbExpectations;
import org.zerionproject.app.api.attachment.Attachment;
import org.zerionproject.app.api.attachment.AttachmentHeader;
import org.zerionproject.app.api.attachment.AttachmentNotYetAvailableException;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import static java.lang.System.arraycopy;
import static org.zerionproject.core.test.TestUtils.getMessage;
import static org.zerionproject.core.test.TestUtils.getRandomId;
import static org.zerionproject.core.util.IoUtils.copyAndClose;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_CONTENT_TYPE;
import static org.zerionproject.app.api.attachment.MediaConstants.MSG_KEY_DESCRIPTOR_LENGTH;
import static org.junit.Assert.assertArrayEquals;

public class AttachmentReaderImplTest extends BrambleMockTestCase {

	private final TransactionManager db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);

	private final GroupId groupId = new GroupId(getRandomId());
	private final Message message = getMessage(groupId, 1234);
	private final String contentType = "image/jpeg";
	private final AttachmentHeader header = new AttachmentHeader(groupId,
			message.getId(), contentType);

	private final AttachmentReaderImpl attachmentReader =
			new AttachmentReaderImpl(db, clientHelper);

	@Test(expected = NoSuchMessageException.class)
	public void testWrongGroup() throws Exception {
		GroupId wrongGroupId = new GroupId(getRandomId());
		AttachmentHeader wrongGroup = new AttachmentHeader(wrongGroupId,
				message.getId(), contentType);

		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(clientHelper).getMessage(txn, message.getId());
			will(returnValue(message));
		}});

		attachmentReader.getAttachment(wrongGroup);
	}

	@Test(expected = AttachmentNotYetAvailableException.class)
	public void testMissingContentType() throws Exception {
		BdfDictionary meta = new BdfDictionary();

		testInvalidMetadata(meta);
	}

	@Test(expected = NoSuchMessageException.class)
	public void testWrongContentType() throws Exception {
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_CONTENT_TYPE, "image/png"));

		testInvalidMetadata(meta);
	}

	@Test(expected = NoSuchMessageException.class)
	public void testMissingDescriptorLength() throws Exception {
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_CONTENT_TYPE, contentType));

		testInvalidMetadata(meta);
	}

	private void testInvalidMetadata(BdfDictionary meta) throws Exception {
		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(clientHelper).getMessage(txn, message.getId());
			will(returnValue(message));
			oneOf(clientHelper)
					.getMessageMetadataAsDictionary(txn, message.getId());
			will(returnValue(meta));
		}});

		attachmentReader.getAttachment(header);
	}

	@Test
	public void testSkipsDescriptor() throws Exception {
		int descriptorLength = 123;
		BdfDictionary meta = BdfDictionary.of(
				new BdfEntry(MSG_KEY_CONTENT_TYPE, contentType),
				new BdfEntry(MSG_KEY_DESCRIPTOR_LENGTH, descriptorLength));

		byte[] body = message.getBody();
		byte[] expectedData = new byte[body.length - descriptorLength];
		arraycopy(body, descriptorLength, expectedData, 0, expectedData.length);

		Transaction txn = new Transaction(null, true);

		context.checking(new DbExpectations() {{
			oneOf(db).transactionWithResult(with(true), withDbCallable(txn));
			oneOf(clientHelper).getMessage(txn, message.getId());
			will(returnValue(message));
			oneOf(clientHelper)
					.getMessageMetadataAsDictionary(txn, message.getId());
			will(returnValue(meta));
		}});

		Attachment attachment = attachmentReader.getAttachment(header);
		InputStream in = attachment.getStream();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		copyAndClose(in, out);
		byte[] data = out.toByteArray();

		assertArrayEquals(expectedData, data);
	}
}

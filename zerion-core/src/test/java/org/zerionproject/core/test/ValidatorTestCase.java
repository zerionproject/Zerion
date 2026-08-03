package org.zerionproject.core.test;

import org.zerionproject.core.api.client.ClientHelper;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.MetadataEncoder;
import org.zerionproject.core.api.identity.Author;
import org.zerionproject.core.api.sync.Group;
import org.zerionproject.core.api.sync.GroupId;
import org.zerionproject.core.api.sync.Message;
import org.zerionproject.core.api.sync.MessageId;
import org.zerionproject.core.api.system.Clock;
import org.jmock.Expectations;

import static org.zerionproject.core.test.TestUtils.getAuthor;
import static org.zerionproject.core.test.TestUtils.getClientId;
import static org.zerionproject.core.test.TestUtils.getGroup;
import static org.zerionproject.core.test.TestUtils.getMessage;

public abstract class ValidatorTestCase extends BrambleMockTestCase {

	protected final ClientHelper clientHelper =
			context.mock(ClientHelper.class);
	protected final MetadataEncoder metadataEncoder =
			context.mock(MetadataEncoder.class);
	protected final Clock clock = context.mock(Clock.class);

	protected final Group group = getGroup(getClientId(), 123);
	protected final GroupId groupId = group.getId();
	protected final byte[] descriptor = group.getDescriptor();
	protected final Message message = getMessage(groupId);
	protected final MessageId messageId = message.getId();
	protected final long timestamp = message.getTimestamp();
	protected final Author author = getAuthor();
	protected final BdfList authorList = BdfList.of(
			author.getFormatVersion(),
			author.getName(),
			author.getPublicKey()
	);

	protected void expectParseAuthor(BdfList authorList, Author author)
			throws Exception {
		context.checking(new Expectations() {{
			oneOf(clientHelper).parseAndValidateAuthor(authorList);
			will(returnValue(author));
		}});
	}

}
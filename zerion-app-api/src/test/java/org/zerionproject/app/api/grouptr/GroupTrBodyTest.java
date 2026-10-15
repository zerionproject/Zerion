package org.zerionproject.app.api.grouptr;

import org.zerionproject.core.test.BrambleTestCase;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;
import static org.zerionproject.core.test.TestUtils.getRandomId;

public class GroupTrBodyTest extends BrambleTestCase {

	private final byte[] target = getRandomId();

	@Test
	public void aReplyRoundTrips() {
		byte[] body = GroupTrBody.encodeReply(target, "sure, see you à 8");

		assertEquals(GroupTrBody.MAGIC_BYTE, body[0]);
		assertEquals(GroupTrBody.VERSION_1, body[1]);
		assertEquals(GroupTrBody.TYPE_REPLY, body[2]);
		assertEquals(5, body[2]);
		assertArrayEquals(target, Arrays.copyOfRange(body, 3, 35));
		assertEquals(GroupTrBody.Kind.REPLY, GroupTrBody.kindOf(body));
		GroupTrBody.Parsed parsed = GroupTrBody.parse(body);
		assertEquals(GroupTrBody.Kind.REPLY, parsed.kind);
		assertEquals("sure, see you à 8", parsed.text);
		assertArrayEquals(target, parsed.replyTo);
		assertArrayEquals(target, GroupTrBody.replyTarget(body));
	}

	@Test
	public void aReplyMayQuoteWithoutText() {
		byte[] body = GroupTrBody.encodeReply(target, "");

		assertEquals(35, body.length);
		assertEquals(GroupTrBody.Kind.REPLY, GroupTrBody.kindOf(body));
		assertEquals("", GroupTrBody.parse(body).text);
	}

	@Test
	public void aShortReplyIsNotAReply() {
		byte[] full = GroupTrBody.encodeReply(target, "");
		byte[] shortBody = Arrays.copyOf(full, 34);

		assertEquals(GroupTrBody.Kind.TEXT, GroupTrBody.kindOf(shortBody));
		GroupTrBody.Parsed parsed = GroupTrBody.parse(shortBody);
		assertEquals(GroupTrBody.Kind.TEXT, parsed.kind);
		assertEquals("", parsed.text);
		assertNull(parsed.replyTo);
		assertNull(GroupTrBody.replyTarget(shortBody));
	}

	@Test
	public void aReplyWithInvalidUtf8IsNotAReply() {
		byte[] full = GroupTrBody.encodeReply(target, "ok");
		byte[] bad = Arrays.copyOf(full, full.length + 2);
		bad[full.length] = (byte) 0xC3;
		bad[full.length + 1] = (byte) 0x28;

		assertEquals(GroupTrBody.Kind.TEXT, GroupTrBody.kindOf(bad));
		assertEquals("", GroupTrBody.parse(bad).text);
		assertNull(GroupTrBody.replyTarget(bad));
	}

	@Test
	public void otherBodiesHaveNoReplyTarget() {
		assertNull(GroupTrBody.replyTarget(GroupTrBody.encodeText("hi")));
		assertNull(GroupTrBody.replyTarget(
				GroupTrBody.encodeVoice(new byte[4], 1000L)));
		assertNull(GroupTrBody.parse(GroupTrBody.encodeText("hi")).replyTo);
		assertNull(GroupTrBody.replyTarget(null));
	}

	@Test
	public void plainTextIsUnchanged() {
		byte[] body = GroupTrBody.encodeText("plain");

		assertArrayEquals("plain".getBytes(StandardCharsets.UTF_8), body);
		assertEquals(GroupTrBody.Kind.TEXT, GroupTrBody.kindOf(body));
		assertEquals("plain", GroupTrBody.parse(body).text);
	}

	@Test
	public void aTargetMustBe32Bytes() {
		try {
			GroupTrBody.encodeReply(new byte[31], "x");
			fail();
		} catch (IllegalArgumentException expected) {
		}
		try {
			GroupTrBody.encodeReply(new byte[33], "x");
			fail();
		} catch (IllegalArgumentException expected) {
		}
	}
}

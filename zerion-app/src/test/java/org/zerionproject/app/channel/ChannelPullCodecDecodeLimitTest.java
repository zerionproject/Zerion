package org.zerionproject.app.channel;

import org.zerionproject.core.api.FormatException;
import org.zerionproject.core.api.data.BdfDictionary;
import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.app.api.channel.ChannelConstants;

import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class ChannelPullCodecDecodeLimitTest {

	private static final byte[] CHANNEL_ID = new byte[32];

	private ChannelPullCodec codec;

	@Before
	public void setUp() {
		ChannelCodecTestComponent c =
				DaggerChannelCodecTestComponent.create();
		codec = new ChannelPullCodec(c.getBdfReaderFactory(),
				c.getBdfWriterFactory());
	}

	@Test
	public void maxPostsDecodes() throws Exception {
		BdfList posts = new BdfList();
		for (int i = 0; i < ChannelConstants.PULL_BATCH_MAX_POSTS; i++) {
			posts.add(post(i));
		}
		ChannelPullCodec.PullResponse r = codec.decodePullResponse(
				encode(response(posts, new BdfList(), new BdfList())),
				CHANNEL_ID);
		assertEquals(ChannelConstants.PULL_BATCH_MAX_POSTS,
				r.newPosts.size());
	}

	@Test
	public void oneOverMaxPostsIsRejected() throws Exception {
		BdfList posts = new BdfList();
		for (int i = 0; i < ChannelConstants.PULL_BATCH_MAX_POSTS + 1; i++) {
			posts.add(new BdfDictionary());
		}
		assertRejected(response(posts, new BdfList(), new BdfList()));
	}

	@Test
	public void hugePostListIsRejected() throws Exception {
		BdfList posts = new BdfList();
		for (int i = 0; i < 300_000; i++) posts.add(new BdfDictionary());
		assertRejected(response(posts, new BdfList(), new BdfList()));
	}

	@Test
	public void tooManyCommentsIsRejected() throws Exception {
		BdfList comments = new BdfList();
		for (int i = 0;
				i < ChannelConstants.MAX_PULL_RESPONSE_COMMENTS + 1; i++) {
			comments.add(comment(i, "c", "n"));
		}
		assertRejected(response(new BdfList(), new BdfList(), comments));
	}

	@Test
	public void commentsUpToTheEarlierLimitDecodeAndABadOneIsSkipped()
			throws Exception {
		BdfList comments = new BdfList();
		for (int i = 0; i < ChannelConstants.MAX_PULL_RESPONSE_COMMENTS - 2;
				i++) {
			comments.add(comment(i, "c", "n"));
		}
		char[] name = new char[ChannelConstants.MAX_COMMENT_AUTHOR_NAME_CHARS
				+ 1];
		java.util.Arrays.fill(name, 'n');
		comments.add(comment(-1, "bad", new String(name)));
		comments.add(comment(-2, "", "n"));
		ChannelPullCodec.PullResponse r = codec.decodePullResponse(
				encode(response(new BdfList(), new BdfList(), comments)),
				CHANNEL_ID);
		assertEquals(ChannelConstants.MAX_PULL_RESPONSE_COMMENTS - 2,
				r.comments.size());
	}

	@Test
	public void tooManyReactionsIsRejected() throws Exception {
		long cap = ChannelConstants.MAX_PULL_RESPONSE_REACTIONS;
		BdfList reactions = new BdfList();
		for (long i = 0; i < cap + 1; i++) reactions.add(new BdfDictionary());
		assertRejected(response(new BdfList(), reactions, new BdfList()));
	}

	@Test
	public void reactionsUpToTheEarlierDecodeLimitStillDecode()
			throws Exception {
		assertEquals(256L * ChannelConstants.PULL_BATCH_MAX_POSTS,
				ChannelConstants.MAX_PULL_RESPONSE_REACTIONS);
		BdfList reactions = new BdfList();
		for (long i = 0; i < ChannelConstants.MAX_PULL_RESPONSE_REACTIONS;
				i++) {
			reactions.add(reaction(i));
		}
		ChannelPullCodec.PullResponse r = codec.decodePullResponse(
				encode(response(new BdfList(), reactions, new BdfList())),
				CHANNEL_ID);
		assertEquals(ChannelConstants.MAX_PULL_RESPONSE_REACTIONS,
				r.reactions.size());
	}

	@Test
	public void tooManyAttachmentsPerPostIsRejected() throws Exception {
		BdfDictionary post = post(0);
		BdfList atts = new BdfList();
		for (int i = 0;
				i < ChannelConstants.MAX_ATTACHMENTS_PER_POST + 1; i++) {
			BdfDictionary a = new BdfDictionary();
			a.put("hash", new byte[32]);
			a.put("size", 1L);
			a.put("mime", "image/jpeg");
			a.put("key", new byte[32]);
			atts.add(a);
		}
		post.put("attachments", atts);
		BdfList posts = new BdfList();
		posts.add(post);
		assertRejected(response(posts, new BdfList(), new BdfList()));
	}

	private void assertRejected(BdfDictionary response) throws Exception {
		try {
			codec.decodePullResponse(encode(response), CHANNEL_ID);
			fail("oversized pull response must be rejected");
		} catch (FormatException expected) {
		}
	}

	private BdfDictionary response(BdfList posts, BdfList reactions,
			BdfList comments) {
		BdfDictionary d = new BdfDictionary();
		d.put("type", ChannelConstants.WIRE_TYPE_PULL_RESPONSE);
		d.put("manifest", new BdfDictionary());
		d.put("posts", posts);
		d.put("reactions", reactions);
		d.put("comments", comments);
		return d;
	}

	private BdfDictionary comment(long id, String body, String name) {
		BdfDictionary d = new BdfDictionary();
		d.put("seq", 1L);
		d.put("id", id);
		d.put("body", body);
		d.put("name", name);
		d.put("ed", new byte[32]);
		d.put("ml", new byte[0]);
		d.put("ts", 0L);
		return d;
	}

	private BdfDictionary reaction(long seq) {
		BdfDictionary d = new BdfDictionary();
		d.put("seq", seq % ChannelConstants.PULL_BATCH_MAX_POSTS);
		d.put("emoji", "x");
		d.put("ed", new byte[32]);
		d.put("ml", new byte[0]);
		d.put("ts", seq);
		return d;
	}

	private BdfDictionary post(int seq) {
		BdfDictionary d = new BdfDictionary();
		d.put("seqNum", (long) seq);
		d.put("prevHash", new byte[32]);
		d.put("timestampHourMs", 0L);
		d.put("body", "post " + seq);
		d.put("ttlMs", 0L);
		d.put("signature", new byte[64]);
		return d;
	}

	private byte[] encode(BdfDictionary d) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		org.zerionproject.core.api.data.BdfWriter w =
				DaggerChannelCodecTestComponent.create().getBdfWriterFactory()
						.createWriter(out);
		w.writeDictionary(d);
		w.flush();
		return out.toByteArray();
	}
}

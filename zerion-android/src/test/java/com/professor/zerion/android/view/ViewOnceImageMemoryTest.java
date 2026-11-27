package com.professor.zerion.android.view;

import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.Assert.assertTrue;

public class ViewOnceImageMemoryTest {

	private static String source(String path) throws IOException {
		File dir = new File(System.getProperty("user.dir"));
		File f = new File(dir, path);
		if (!f.exists()) f = new File(dir.getParentFile(),
				"zerion-android/" + path);
		return new String(Files.readAllBytes(f.toPath()),
				StandardCharsets.UTF_8);
	}

	@Test
	public void theComposerPreviewKeepsNoPictureInTheMemoryCache()
			throws IOException {
		String preview = source("src/main/java/com/professor/zerion/android/"
				+ "view/ImagePreviewViewHolder.java");
		int load = preview.indexOf(".load(attachmentItem.getHeader())");
		assertTrue(load > 0);
		String chain = preview.substring(load, preview.indexOf(";", load));
		assertTrue(chain.contains(".skipMemoryCache(true)"));
		assertTrue(chain.contains(".diskCacheStrategy(NONE)"));
	}

	@Test
	public void sendingAViewOncePhotoEmptiesTheImageMemoryCache()
			throws IOException {
		String chat = source("src/main/java/com/professor/zerion/android/"
				+ "conversation/ConversationActivity.java");
		int send = chat.indexOf("expectedAutoDeleteTimer, replyToItem, true);");
		assertTrue(send > 0);
		String after = chat.substring(send, chat.indexOf("return result;",
				send));
		assertTrue(after.contains("state == SendState.SENT"));
		assertTrue(after.contains("Glide.get(this).clearMemory()"));
	}
}

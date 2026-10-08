package com.professor.zerion.android.update;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

public class TorHttpsGetTest {

	private static byte[] bytes(String s) {
		return s.getBytes(StandardCharsets.ISO_8859_1);
	}

	@Test
	public void theBodyIsReturnedByteForByte() throws IOException {
		byte[] body = {0, 1, (byte) 0xff, '\r', '\n', 'x'};
		byte[] head = bytes("HTTP/1.1 200 OK\r\nContent-Length: 6\r\n\r\n");
		byte[] all = new byte[head.length + body.length];
		System.arraycopy(head, 0, all, 0, head.length);
		System.arraycopy(body, 0, all, head.length, body.length);
		TorHttpsGet.Response r = TorHttpsGet.parse(all, 100);
		assertEquals(200, r.status);
		assertArrayEquals(body, r.body);
	}

	@Test
	public void aMissingFileReportsItsStatus() throws IOException {
		assertEquals(404, TorHttpsGet.parse(
				bytes("HTTP/1.1 404 Not Found\r\n\r\n404: Not Found"), 100).status);
	}

	@Test
	public void encodedOrOversizedOrMalformedResponsesAreRefused() {
		String[] bad = {
				"HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n",
				"HTTP/1.1 200 OK\r\nContent-Encoding: gzip\r\n\r\nxx",
				"HTTP/1.1 200 OK\r\n\r\n" + new String(new char[101]).replace('\0', 'a'),
				"garbage without headers",
				"SMTP 200 OK\r\n\r\n",
				"HTTP/1.1 abc OK\r\n\r\n",
		};
		for (String b : bad) {
			try {
				TorHttpsGet.parse(bytes(b), 100);
				fail("accepted: " + b.substring(0, Math.min(40, b.length())));
			} catch (IOException expected) {
			}
		}
	}

	@Test
	public void readingStopsAtTheLimit() {
		try {
			TorHttpsGet.readAll(new ByteArrayInputStream(new byte[5000]), 4096);
			fail("read past the limit");
		} catch (IOException expected) {
		}
	}
}

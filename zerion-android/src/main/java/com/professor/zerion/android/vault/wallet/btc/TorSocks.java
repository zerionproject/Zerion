package com.professor.zerion.android.vault.wallet.btc;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

@NotNullByDefault
final class TorSocks {

	private TorSocks() {
	}

	static void connect(Socket socket, String host, int port, String user,
			String pass) throws IOException {
		OutputStream out = socket.getOutputStream();
		InputStream in = socket.getInputStream();

		out.write(new byte[]{0x05, 0x01, 0x02});
		out.flush();
		byte[] sel = readFully(in, 2);
		if (sel[0] != 0x05 || sel[1] != 0x02) {
			throw new IOException("Tor SOCKS auth not accepted");
		}

		byte[] u = user.getBytes(StandardCharsets.UTF_8);
		byte[] p = pass.getBytes(StandardCharsets.UTF_8);
		if (u.length > 255 || p.length > 255) {
			throw new IOException("SOCKS credential too long");
		}
		ByteArrayOutputStream auth = new ByteArrayOutputStream();
		auth.write(0x01);
		auth.write(u.length);
		auth.write(u, 0, u.length);
		auth.write(p.length);
		auth.write(p, 0, p.length);
		out.write(auth.toByteArray());
		out.flush();
		byte[] authReply = readFully(in, 2);
		if (authReply[1] != 0x00) {
			throw new IOException("Tor SOCKS auth failed");
		}

		byte[] d = host.getBytes(StandardCharsets.US_ASCII);
		if (d.length > 255) {
			throw new IOException("host too long");
		}
		ByteArrayOutputStream req = new ByteArrayOutputStream();
		req.write(0x05);
		req.write(0x01);
		req.write(0x00);
		req.write(0x03);
		req.write(d.length);
		req.write(d, 0, d.length);
		req.write((port >> 8) & 0xFF);
		req.write(port & 0xFF);
		out.write(req.toByteArray());
		out.flush();

		byte[] rep = readFully(in, 4);
		if (rep[0] != 0x05) {
			throw new IOException("bad SOCKS reply");
		}
		if (rep[1] != 0x00) {
			throw new IOException("Tor could not reach the server (rep="
					+ (rep[1] & 0xFF) + ")");
		}
		int atyp = rep[3] & 0xFF;
		int addrLen;
		if (atyp == 0x01) {
			addrLen = 4;
		} else if (atyp == 0x04) {
			addrLen = 16;
		} else if (atyp == 0x03) {
			addrLen = readFully(in, 1)[0] & 0xFF;
		} else {
			throw new IOException("bad SOCKS address type");
		}
		readFully(in, addrLen + 2);
	}

	private static byte[] readFully(InputStream in, int n) throws IOException {
		byte[] b = new byte[n];
		int off = 0;
		while (off < n) {
			int r = in.read(b, off, n - off);
			if (r < 0) {
				throw new IOException("SOCKS stream closed");
			}
			off += r;
		}
		return b;
	}
}

package org.zerionproject.core.data;

import org.zerionproject.core.api.data.BdfList;
import org.zerionproject.core.api.data.BdfWriter;
import org.zerionproject.core.plugin.tor.auth.OnionAuthRecords;
import org.zerionproject.core.util.StringUtils;
import org.junit.Test;

import java.io.ByteArrayOutputStream;

import static org.junit.Assert.assertEquals;

/**
 * The bytes of every client-authorization activation record, pinned so a
 * change on either platform that would break interoperability fails here.
 * The same vectors are asserted by OnionAuthRecordsTests on iOS. The test
 * lives in this package because the BDF writer implementation does.
 */
public class OnionAuthWireVectorsTest {

	private static final String ONION =
			"7s5whvmtwlppvz4r6syi35zvn6isidsyfc2ghsauobzhvetzubwetvad";
	private static final String ONION_2 =
			"tl3mosgot6ga5zdlanvydkb5gclpzxyp7kybaqrnz3l6625czv2c5uad";
	private static final String VEC_OFFER =
			"6021002101210141383773357768766d74776c7070767a34723673796933357a766e3669736964737966633267687361756f627a687665747a75627765747661645120000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f80";
	private static final String VEC_OFFER_NO_ADDRESS =
			"6021002101210141005120000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f80";
	private static final String VEC_READY =
			"6021012101210280";
	private static final String VEC_PROBE_SUCCESS =
			"6021022101210280";
	private static final String VEC_COMMIT =
			"602103210121014138746c336d6f73676f74366761357a646c616e7679646b623567636c707a787970376b79626171726e7a336c36363235637a763263357561645120808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f80";
	private static final String VEC_ROTATE =
			"602104210121024138746c336d6f73676f74366761357a646c616e7679646b623567636c707a787970376b79626171726e7a336c36363235637a76326335756164510080";
	private static final String VEC_ROTATE_ACK =
			"6021052101210280";

	@Test
	public void testWireVectorsAreStable() throws Exception {
		byte[] pub = new byte[32];
		for (int i = 0; i < 32; i++) pub[i] = (byte) i;
		byte[] fp = new byte[32];
		for (int i = 0; i < 32; i++) fp[i] = (byte) (0x80 + i);
		assertEquals(VEC_OFFER, hex(OnionAuthRecords.offer(1, ONION, pub)));
		assertEquals(VEC_OFFER_NO_ADDRESS,
				hex(OnionAuthRecords.offer(1, null, pub)));
		assertEquals(VEC_READY, hex(OnionAuthRecords.ready(2)));
		assertEquals(VEC_PROBE_SUCCESS, hex(OnionAuthRecords.probeSuccess(2)));
		assertEquals(VEC_COMMIT, hex(OnionAuthRecords.commit(1, ONION_2, fp)));
		assertEquals(VEC_ROTATE, hex(OnionAuthRecords.rotate(2, ONION_2, null)));
		assertEquals(VEC_ROTATE_ACK, hex(OnionAuthRecords.rotateAck(2)));
	}

	private static String hex(BdfList body) throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		BdfWriter w = new BdfWriterFactoryImpl().createWriter(out);
		w.writeList(body);
		w.flush();
		return StringUtils.toHexString(out.toByteArray()).toLowerCase();
	}
}

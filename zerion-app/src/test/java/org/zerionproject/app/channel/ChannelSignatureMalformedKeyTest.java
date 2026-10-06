package org.zerionproject.app.channel;

import org.junit.Test;
import org.zerionproject.core.api.crypto.CryptoComponent;

import org.zerionproject.core.api.crypto.KeyPair;
import org.zerionproject.core.api.crypto.PublicKey;

import static org.junit.Assert.assertFalse;

public class ChannelSignatureMalformedKeyTest {

	@Test
	public void aWrongLengthKeyIsAFailedSignature() throws Exception {
		CryptoComponent crypto =
				DaggerChannelCryptoTestComponent.create().getCryptoComponent();
		ChannelSignatures sigs = new ChannelSignatures(crypto);
		KeyPair ed = crypto.generateSignatureKeyPair();
		PublicKey edPub = ed.getPublic();
		byte[] input = new byte[] {1, 2, 3};
		byte[] sig = new byte[3373];
		for (byte[] ml : new byte[][] {new byte[1], new byte[100],
				new byte[5000]}) {
			assertFalse(sigs.verifyUserReaction(sig, input, edPub, ml));
			assertFalse(sigs.verifyUserApplication(sig, input, edPub, ml));
			assertFalse(sigs.verifyUserCheckApproval(sig, input, edPub, ml));
		}
	}
}

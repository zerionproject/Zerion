package org.zerionproject.core.crypto;

import android.content.Context;
import android.os.Build;
import android.os.Debug;

import org.zerionproject.core.api.crypto.CryptoComponent;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import androidx.test.platform.app.InstrumentationRegistry;

import static org.junit.Assert.assertArrayEquals;

public class Argon2TimingProbeDeviceTest {

	@Test
	public void timeTheAccountPasswordEncryption() throws Exception {
		StringBuilder report = new StringBuilder();
		report.append("device\t").append(Build.MANUFACTURER).append(' ')
				.append(Build.MODEL).append("\tandroid ")
				.append(Build.VERSION.RELEASE).append("\tdebugger_attached\t")
				.append(Debug.isDebuggerConnected()).append("\tmax_heap_mb\t")
				.append(Runtime.getRuntime().maxMemory() / (1024 * 1024))
				.append('\n');
		time(report, "production", PasswordCryptoForTests.create());
		time(report, "low_cost", PasswordCryptoForTests.createLowCost());
		Context ctx = InstrumentationRegistry.getInstrumentation()
				.getTargetContext();
		File dir = new File(ctx.getFilesDir(), "device-test-output");
		dir.mkdirs();
		try (FileOutputStream out = new FileOutputStream(new File(dir,
				"argon2-timing.tsv"))) {
			out.write(report.toString().getBytes(StandardCharsets.UTF_8));
		}
	}

	private static void time(StringBuilder report, String name,
			CryptoComponent crypto) throws Exception {
		byte[] key = new byte[32];
		char[] password = "timing probe 5%Gh".toCharArray();
		long t0 = System.nanoTime();
		byte[] ciphertext = crypto.encryptWithPassword(key.clone(), password,
				null);
		long t1 = System.nanoTime();
		byte[] plain = crypto.decryptWithPassword(ciphertext, password, null);
		long t2 = System.nanoTime();
		assertArrayEquals(key, plain);
		long cost = ((ciphertext[33] & 0xffL) << 24)
				| ((ciphertext[34] & 0xffL) << 16)
				| ((ciphertext[35] & 0xffL) << 8) | (ciphertext[36] & 0xffL);
		report.append(name).append("\tmemory_kib\t").append(cost >> 8)
				.append("\titerations\t").append(cost & 0xff)
				.append("\tencrypt_ms\t").append(ms(t1 - t0))
				.append("\tdecrypt_ms\t").append(ms(t2 - t1)).append('\n');
	}

	private static String ms(long ns) {
		return String.format(Locale.ROOT, "%.0f", ns / 1e6);
	}
}

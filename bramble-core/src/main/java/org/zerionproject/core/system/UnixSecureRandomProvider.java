package org.zerionproject.core.system;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.security.Provider;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.concurrent.Immutable;
@Immutable
@NotNullByDefault
class UnixSecureRandomProvider extends AbstractSecureRandomProvider {
	private static final File RANDOM_DEVICE = new File("/dev/urandom");

	private final AtomicBoolean seeded = new AtomicBoolean(false);
	private final File outputDevice;

	UnixSecureRandomProvider() {
		this(RANDOM_DEVICE);
	}

	UnixSecureRandomProvider(File outputDevice) {
		this.outputDevice = outputDevice;
	}

	@Override
	public Provider getProvider() {
		if (!seeded.getAndSet(true)) writeSeed();
		return new UnixProvider();
	}

	protected void writeSeed() {
		try {
			DataOutputStream out = new DataOutputStream(
					new FileOutputStream(outputDevice));
			writeToEntropyPool(out);
			out.flush();
			out.close();
		} catch (IOException e) {
		}
	}
	private static class UnixProvider extends Provider {

		private UnixProvider() {
			super("UnixPRNG", 1.0, "A Unix-specific PRNG using /dev/urandom");
			put("SecureRandom.SHA1PRNG", UnixSecureRandomSpi.class.getName());
			put("SecureRandom.SHA1PRNG ImplementedIn", "Software");
		}
	}
}
package net.i2p.router;

public final class I2pGlobalContextReset {

	private I2pGlobalContextReset() {
	}

	public static void reset() {
		RouterContext.killGlobalContext();
	}
}

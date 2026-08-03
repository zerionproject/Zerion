package com.professor.zerion.android.i2p;

import android.content.Context;
import android.content.res.AssetManager;

import net.i2p.router.Router;

import org.zerionproject.transport.i2p.I2pRouter;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

import javax.annotation.Nullable;

@NotNullByDefault
public class BundledI2pRouter implements I2pRouter {

	private static final long RUNNING_TIMEOUT_MS = 240_000;
	private static final long POLL_INTERVAL_MS = 5_000;
	private static final String ASSET_DIR = "i2p";

	private final Context appContext;
	private final int torSocksPort;
	private final Object lock = new Object();

	@Nullable
	private Router router;

	public BundledI2pRouter(Context context, int torSocksPort) {
		this.appContext = context.getApplicationContext();
		this.torSocksPort = torSocksPort;
	}

	@Override
	public void start() throws IOException {
		synchronized (lock) {
			if (router != null) return;
			File baseDir = new File(appContext.getFilesDir(), "i2p");
			if (!baseDir.exists() && !baseDir.mkdirs()) {
				throw new IOException("Could not create I2P directory");
			}
			extractAssets(ASSET_DIR, baseDir);
			writeLoggerConfig(baseDir);
			System.setProperty("i2p.dir.base", baseDir.getAbsolutePath());
			System.setProperty("i2p.dir.config", baseDir.getAbsolutePath());
			Router r = new Router(routerProperties());
			r.setKillVMOnEnd(false);
			r.runRouter();
			router = r;
			Thread t = new Thread(() -> awaitRunning(r), "I2pBootProgress");
			t.setDaemon(true);
			t.start();
		}
	}

	@Override
	public void stop() {
		synchronized (lock) {
			Router r = router;
			if (r != null) {
				r.shutdown(Router.EXIT_HARD);
			}
			router = null;
		}
	}

	private void awaitRunning(Router r) {
		long start = System.currentTimeMillis();
		long deadline = start + RUNNING_TIMEOUT_MS;
		while (!r.isRunning()) {
			if (System.currentTimeMillis() > deadline) {
				return;
			}
			try {
				Thread.sleep(POLL_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
		}
	}

	private void writeLoggerConfig(File baseDir) {
		File cfg = new File(baseDir, "logger.config");
		try (OutputStream out = new FileOutputStream(cfg)) {
			out.write(("logger.defaultLevel=CRIT\n"
					+ "logger.logFileSize=16KB\n"
					+ "logger.logRotationLimit=0\n"
					+ "logger.dropDuplicates=true\n")
					.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		} catch (IOException e) {
		}
	}

	private Properties routerProperties() {
		Properties p = new Properties();
		p.setProperty("i2cp.disableInterface", "true");
		p.setProperty("router.maxParticipatingTunnels", "0");
		p.setProperty("router.floodfillParticipant", "false");
		p.setProperty("i2p.hiddenMode", "true");
		p.setProperty("i2np.udp.enable", "false");
		p.setProperty("i2np.ntcp2.enable", "true");
		p.setProperty("i2np.inboundKBytesPerSecond", "128");
		p.setProperty("i2np.outboundKBytesPerSecond", "64");
		p.setProperty("i2np.upnp.enable", "false");
		p.setProperty("router.enableUPnP", "false");
		applyReseedOverTor(p);
		return p;
	}

	private void applyReseedOverTor(Properties p) {
		if (torSocksPort <= 0) return;
		String host = "127.0.0.1";
		String port = String.valueOf(torSocksPort);
		p.setProperty("router.reseedSSLProxyEnable", "true");
		p.setProperty("router.reseedSSLProxyType", "SOCKS5");
		p.setProperty("router.reseedSSLProxyHost", host);
		p.setProperty("router.reseedSSLProxyPort", port);
		p.setProperty("router.reseedSSLRequired", "true");
	}

	private void extractAssets(String assetPath, File targetDir)
			throws IOException {
		AssetManager assets = appContext.getAssets();
		String[] children = assets.list(assetPath);
		if (children == null || children.length == 0) {
			copyAsset(assets, assetPath, targetDir);
			return;
		}
		if (!targetDir.exists() && !targetDir.mkdirs()) {
			throw new IOException("Could not create " + targetDir);
		}
		for (String child : children) {
			extractAssets(assetPath + "/" + child, new File(targetDir, child));
		}
	}

	private void copyAsset(AssetManager assets, String assetPath, File target)
			throws IOException {
		File parent = target.getParentFile();
		if (parent != null && !parent.exists() && !parent.mkdirs()) {
			throw new IOException("Could not create " + parent);
		}
		try (InputStream in = assets.open(assetPath);
				OutputStream out = new FileOutputStream(target)) {
			byte[] buf = new byte[8192];
			int read;
			while ((read = in.read(buf)) != -1) {
				out.write(buf, 0, read);
			}
		}
	}
}

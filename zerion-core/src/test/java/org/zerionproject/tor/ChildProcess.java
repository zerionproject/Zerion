package org.zerionproject.tor;

import java.io.File;
import java.io.IOException;

/**
 * A stand-in for the Tor process in tests: a JVM that exits at once, prints
 * the line the wrapper waits for and then sleeps, or only sleeps.
 */
public final class ChildProcess {

	static final String EXIT = "exit";
	static final String LISTEN = "listen";
	static final String SLEEP = "sleep";

	private ChildProcess() {
	}

	public static void main(String[] args) throws InterruptedException {
		String mode = args.length == 0 ? SLEEP : args[0];
		if (mode.equals(EXIT)) return;
		if (mode.equals(LISTEN)) {
			System.out.println("Opened Control listener on 127.0.0.1:9051");
			System.out.flush();
		}
		Thread.sleep(600_000);
	}

	static Process spawn(String mode) throws IOException {
		String java = System.getProperty("java.home") + File.separator
				+ "bin" + File.separator + "java";
		String classpath = System.getProperty("java.class.path");
		return new ProcessBuilder(java, "-cp", classpath,
				ChildProcess.class.getName(), mode)
				.redirectErrorStream(true).start();
	}

	/**
	 * A script the wrapper can run in place of Tor. Windows runs a command
	 * file by its extension; everything else runs a shell script.
	 */
	static File script(File dir, String name, String mode)
			throws IOException {
		boolean windows = System.getProperty("os.name", "")
				.toLowerCase().startsWith("win");
		File f = new File(dir, name + (windows ? ".cmd" : ".sh"));
		String body;
		if (windows) {
			if (mode.equals(EXIT)) body = "@echo off\r\nexit /b 1\r\n";
			else if (mode.equals(LISTEN)) {
				body = "@echo off\r\necho Opened Control listener on 127.0.0.1:9051\r\n"
						+ "ping -n 600 127.0.0.1 > nul\r\n";
			} else body = "@echo off\r\nping -n 600 127.0.0.1 > nul\r\n";
		} else {
			if (mode.equals(EXIT)) body = "#!/bin/sh\nexit 1\n";
			else if (mode.equals(LISTEN)) {
				body = "#!/bin/sh\necho Opened Control listener on 127.0.0.1:9051\n"
						+ "sleep 600\n";
			} else body = "#!/bin/sh\nsleep 600\n";
		}
		java.nio.file.Files.write(f.toPath(),
				body.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
		f.setExecutable(true, true);
		return f;
	}
}

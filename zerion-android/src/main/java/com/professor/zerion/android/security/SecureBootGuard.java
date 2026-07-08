package com.professor.zerion.android.security;

import android.content.Context;
import android.os.Build;
import android.os.Debug;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

@NotNullByDefault
public final class SecureBootGuard {

	public static final int RESULT_OK = 0;
	public static final int RESULT_VERIFIED_BOOT_NOT_GREEN = 1;
	public static final int RESULT_BOOTLOADER_UNLOCKED = 2;
	public static final int RESULT_ROOT_BINARY_FOUND = 3;
	public static final int RESULT_MAGISK_FOUND = 4;
	public static final int RESULT_DEBUGGER_ATTACHED = 5;
	public static final int RESULT_FRIDA_FOUND = 6;
	public static final int RESULT_XPOSED_FOUND = 7;
	public static final int RESULT_ADB_DAEMON_LISTENING = 8;

	private SecureBootGuard() {
	}

	public static int evaluateStrictBoot() {
		String state = readSystemProperty("ro.boot.verifiedbootstate");
		if (state == null || !"green".equalsIgnoreCase(state)) {
			return RESULT_VERIFIED_BOOT_NOT_GREEN;
		}
		String locked = readSystemProperty("ro.boot.flash.locked");
		if (locked != null && !"1".equals(locked)) {
			return RESULT_BOOTLOADER_UNLOCKED;
		}
		String vbm = readSystemProperty("ro.boot.veritymode");
		if (vbm != null && "disabled".equalsIgnoreCase(vbm)) {
			return RESULT_VERIFIED_BOOT_NOT_GREEN;
		}
		return RESULT_OK;
	}

	public static int evaluateAntiTamper() {
		if (debuggerAttached()) return RESULT_DEBUGGER_ATTACHED;
		if (rootBinariesPresent()) return RESULT_ROOT_BINARY_FOUND;
		if (magiskArtifactsPresent()) return RESULT_MAGISK_FOUND;
		if (fridaArtifactsPresent()) return RESULT_FRIDA_FOUND;
		if (xposedArtifactsPresent()) return RESULT_XPOSED_FOUND;
		if (adbDaemonListening()) return RESULT_ADB_DAEMON_LISTENING;
		return RESULT_OK;
	}

	private static boolean debuggerAttached() {
		if (Debug.isDebuggerConnected()) return true;
		if (Debug.waitingForDebugger()) return true;
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				new FileInputStream("/proc/self/status"),
				StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) {
				if (line.startsWith("TracerPid:")) {
					String pid = line.substring(10).trim();
					if (!"0".equals(pid)) return true;
					break;
				}
			}
		} catch (Exception ignored) {
		}
		return false;
	}

	private static boolean rootBinariesPresent() {
		String[] paths = {
				"/system/bin/su",
				"/system/xbin/su",
				"/system/sbin/su",
				"/sbin/su",
				"/vendor/bin/su",
				"/su/bin/su",
				"/data/local/su",
				"/data/local/bin/su",
				"/data/local/xbin/su",
				"/system/app/Superuser.apk",
				"/system/etc/init.d/99SuperSUDaemon",
				"/dev/com.koushikdutta.superuser.daemon/",
				"/system/xbin/daemonsu"
		};
		for (String p : paths) {
			try {
				if (new File(p).exists()) return true;
			} catch (Exception ignored) {
			}
		}
		return false;
	}

	private static boolean magiskArtifactsPresent() {
		String[] paths = {
				"/sbin/.magisk",
				"/cache/.disable_magisk",
				"/dev/.magisk.unblock",
				"/system/etc/init/magisk.rc",
				"/data/adb/magisk",
				"/data/adb/magisk.db",
				"/data/adb/modules"
		};
		for (String p : paths) {
			try {
				if (new File(p).exists()) return true;
			} catch (Exception ignored) {
			}
		}
		if (procMapsContainsAny(new String[]{"magisk", "/data/adb/modules"})) {
			return true;
		}
		return procMountsContainsAny(new String[]{"magisk", "/data/adb"});
	}

	private static boolean fridaArtifactsPresent() {
		String[] paths = {
				"/data/local/tmp/re.frida.server",
				"/data/local/tmp/frida-server"
		};
		for (String p : paths) {
			try {
				if (new File(p).exists()) return true;
			} catch (Exception ignored) {
			}
		}
		if (procMapsContainsAny(new String[]{
				"frida-agent", "frida-gadget", "libfrida", "gum-js-loop",
				"gmain", "linjector"})) {
			return true;
		}
		try (java.net.Socket s = new java.net.Socket()) {
			s.connect(new java.net.InetSocketAddress("127.0.0.1", 27042),
					250);
			return true;
		} catch (Exception ignored) {
		}
		return false;
	}

	private static boolean xposedArtifactsPresent() {
		String[] paths = {
				"/system/framework/XposedBridge.jar",
				"/system/lib/libxposed_art.so",
				"/system/lib64/libxposed_art.so",
				"/system/bin/app_process32_xposed",
				"/system/bin/app_process64_xposed",
				"/data/data/de.robv.android.xposed.installer",
				"/data/data/org.meowcat.edxposed.manager",
				"/data/data/io.va.exposed",
				"/data/data/org.lsposed.manager"
		};
		for (String p : paths) {
			try {
				if (new File(p).exists()) return true;
			} catch (Exception ignored) {
			}
		}
		return procMapsContainsAny(new String[]{
				"XposedBridge", "libxposed", "LSPosed", "EdXposed"});
	}

	private static boolean adbDaemonListening() {
		try (java.net.Socket s = new java.net.Socket()) {
			s.connect(new java.net.InetSocketAddress("127.0.0.1", 5555),
					250);
			return true;
		} catch (Exception ignored) {
		}
		return false;
	}

	private static boolean procMapsContainsAny(String[] needles) {
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				new FileInputStream("/proc/self/maps"),
				StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) {
				for (String needle : needles) {
					if (line.contains(needle)) return true;
				}
			}
		} catch (Exception ignored) {
		}
		return false;
	}

	private static boolean procMountsContainsAny(String[] needles) {
		try (BufferedReader br = new BufferedReader(new InputStreamReader(
				new FileInputStream("/proc/self/mounts"),
				StandardCharsets.UTF_8))) {
			String line;
			while ((line = br.readLine()) != null) {
				for (String needle : needles) {
					if (line.contains(needle)) return true;
				}
			}
		} catch (Exception ignored) {
		}
		return false;
	}

	@SuppressWarnings("PrivateApi")
	private static String readSystemProperty(String key) {
		try {
			Class<?> clazz = Class.forName("android.os.SystemProperties");
			Method get = clazz.getMethod("get", String.class);
			Object out = get.invoke(null, key);
			return out == null ? null : out.toString();
		} catch (Throwable ignored) {
		}
		Process p = null;
		try {
			p = new ProcessBuilder("/system/bin/getprop", key)
					.redirectErrorStream(true).start();
			try (BufferedReader br = new BufferedReader(new InputStreamReader(
					p.getInputStream(), StandardCharsets.UTF_8))) {
				String line = br.readLine();
				return line == null ? null : line.trim();
			}
		} catch (Exception ignored) {
		} finally {
			if (p != null) p.destroyForcibly();
		}
		return null;
	}

	public static String describe(int result, Context ctx) {
		switch (result) {
			case RESULT_OK:
				return "OK";
			case RESULT_VERIFIED_BOOT_NOT_GREEN:
				return "Verified boot not in GREEN state. Device firmware is not a stock signed image. An attacker with bootloader access could have modified the OS.";
			case RESULT_BOOTLOADER_UNLOCKED:
				return "Bootloader is unlocked. A recovery-mode dump could bypass app-layer wipes.";
			case RESULT_ROOT_BINARY_FOUND:
				return "Root binary (su) detected. A rooted device cannot enforce app sandboxing against a determined attacker.";
			case RESULT_MAGISK_FOUND:
				return "Magisk artifacts detected. The OS has root and may be hooking app processes.";
			case RESULT_DEBUGGER_ATTACHED:
				return "A debugger is attached to this process. Memory contents can be read live.";
			case RESULT_FRIDA_FOUND:
				return "Frida instrumentation detected. Function hooks can rewrite cryptographic operations in flight.";
			case RESULT_XPOSED_FOUND:
				return "Xposed / LSPosed framework detected. System-wide hooks can intercept app calls.";
			case RESULT_ADB_DAEMON_LISTENING:
				return "ADB daemon is listening on localhost. USB or wireless debugging is exposing this device.";
			default:
				return "Hardened Mode check failed (code " + result + ").";
		}
	}
}

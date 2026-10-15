package com.professor.zerion.android.backup;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BackupStreamSmallHeapTest {

	private static final String HEAP = "-Xmx224m";
	private static final String MIB = "512";

	private static int probe(String... args) throws Exception {
		File argFile = File.createTempFile("zbk-probe", ".args");
		File output = File.createTempFile("zbk-probe", ".out");
		try {
			String classPath = System.getProperty("java.class.path")
					.replace('\\', '/');
			try (FileOutputStream out = new FileOutputStream(argFile)) {
				out.write(("-cp \"" + classPath + "\"")
						.getBytes(StandardCharsets.UTF_8));
			}
			String java = new File(new File(System.getProperty("java.home"),
					"bin"), "java").getPath();
			String[] command = new String[4 + args.length];
			command[0] = java;
			command[1] = HEAP;
			command[2] = "@" + argFile.getPath();
			command[3] = StreamingHeapProbe.class.getName();
			System.arraycopy(args, 0, command, 4, args.length);
			Process p = new ProcessBuilder(command)
					.redirectErrorStream(true)
					.redirectOutput(ProcessBuilder.Redirect.to(output))
					.start();
			assertTrue("the probe did not finish",
					p.waitFor(10, TimeUnit.MINUTES));
			return p.exitValue();
		} finally {
			assertTrue(argFile.delete());
			assertTrue(output.delete());
		}
	}

	@Test
	public void holdingTheWholeBackupDoesNotFitInTheProbeHeap()
			throws Exception {
		assertEquals(StreamingHeapProbe.OUT_OF_MEMORY, probe(MIB, "buffer"));
	}

	@Test
	public void aBackupLargerThanTheHeapIsSealedAndOpened() throws Exception {
		assertEquals(StreamingHeapProbe.OK, probe(MIB));
	}
}

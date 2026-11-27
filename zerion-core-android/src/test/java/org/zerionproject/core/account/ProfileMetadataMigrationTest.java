package org.zerionproject.core.account;

import android.content.Context;

import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.After;
import org.junit.Test;
import org.zerionproject.core.test.BrambleMockTestCase;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.zerionproject.core.test.TestUtils.deleteTestDirectory;
import static org.zerionproject.core.test.TestUtils.getTestDirectory;

public class ProfileMetadataMigrationTest extends BrambleMockTestCase {

	private static final String PROFILE = "p1";

	private final File testDir = getTestDirectory();
	private final File filesDir = new File(testDir, "files");
	private final ProfileMetadataCryptoTest.FakeKeys keys =
			new ProfileMetadataCryptoTest.FakeKeys();
	private final ProfileManager profiles;

	public ProfileMetadataMigrationTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		Context appContext = context.mock(Context.class);
		context.checking(new Expectations() {{
			allowing(appContext).getFilesDir();
			will(returnValue(filesDir));
		}});
		filesDir.mkdirs();
		profiles = new ProfileManager(appContext, false,
				new ProfileMetadataCrypto(keys));
		assertTrue(profiles.createProfileDir(PROFILE));
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	private File metaFile(String name) {
		return new File(profiles.getKeyDir(PROFILE), name);
	}

	private static byte[] sealedLookingBytes() {
		byte[] b = new byte[40];
		for (int i = 0; i < b.length; i++) b[i] = (byte) (0x80 + i * 3);
		b[0] = 'A';
		b[1] = 'b';
		b[2] = '\n';
		return b;
	}

	@Test
	public void aSealedDisplayNameIsLeftAloneWhileTheKeyIsUnavailable()
			throws Exception {
		assertTrue(profiles.writeDisplayName(PROFILE, "Alice"));
		File f = profiles.getDisplayNameFile(PROFILE);
		byte[] sealed = Files.readAllBytes(f.toPath());
		keys.deviceLocked = true;
		assertNull(profiles.readDisplayName(PROFILE));
		assertArrayEquals("nothing was written over the sealed name",
				sealed, Files.readAllBytes(f.toPath()));
		assertFalse(new File(f.getParentFile(), "display_name.tmp").exists());
		keys.deviceLocked = false;
		assertEquals("Alice", profiles.readDisplayName(PROFILE));
	}

	@Test
	public void aSealedMetaFileIsLeftAloneWhileTheKeyIsUnavailable()
			throws Exception {
		profiles.writeEncryptedMetaFile(PROFILE, "pending_identity_name",
				"Bob");
		File f = metaFile("pending_identity_name");
		byte[] sealed = Files.readAllBytes(f.toPath());
		keys.deviceLocked = true;
		assertNull(profiles.readEncryptedMetaFile(PROFILE,
				"pending_identity_name"));
		assertArrayEquals(sealed, Files.readAllBytes(f.toPath()));
		keys.deviceLocked = false;
		assertEquals("Bob", profiles.readEncryptedMetaFile(PROFILE,
				"pending_identity_name"));
	}

	@Test
	public void aLegacyPlaintextNameIsMigratedWhenItIsTheWholeFile()
			throws Exception {
		File f = profiles.getDisplayNameFile(PROFILE);
		Files.write(f.toPath(), "Carol\n".getBytes(StandardCharsets.UTF_8));
		assertEquals("Carol", profiles.readDisplayName(PROFILE));
		byte[] after = Files.readAllBytes(f.toPath());
		assertFalse(Arrays.equals("Carol\n".getBytes(StandardCharsets.UTF_8),
				after));
		assertEquals("Carol", profiles.readDisplayName(PROFILE));
		assertArrayEquals(after, Files.readAllBytes(f.toPath()));
	}

	@Test
	public void aFileThatOnlyStartsLikeALineIsNotTakenForALegacyName()
			throws Exception {
		File f = profiles.getDisplayNameFile(PROFILE);
		byte[] sealedLooking = sealedLookingBytes();
		Files.write(f.toPath(), sealedLooking);
		assertNull(profiles.readDisplayName(PROFILE));
		assertArrayEquals(sealedLooking, Files.readAllBytes(f.toPath()));

		byte[] twoLines = "Da\nve".getBytes(StandardCharsets.UTF_8);
		Files.write(f.toPath(), twoLines);
		assertNull(profiles.readDisplayName(PROFILE));
		assertArrayEquals(twoLines, Files.readAllBytes(f.toPath()));

		File meta = metaFile("pending_identity_name");
		Files.write(meta.toPath(), sealedLooking);
		assertNull(profiles.readEncryptedMetaFile(PROFILE,
				"pending_identity_name"));
		assertArrayEquals(sealedLooking, Files.readAllBytes(meta.toPath()));
	}

	@Test
	public void theLegacyLineRuleAcceptsOnlyAWholeReadableFile() {
		assertEquals("Erin", ProfileManager.legacyPlaintextLine(
				"Erin".getBytes(StandardCharsets.UTF_8)));
		assertEquals("Erin", ProfileManager.legacyPlaintextLine(
				"Erin\n".getBytes(StandardCharsets.UTF_8)));
		assertEquals("Erin", ProfileManager.legacyPlaintextLine(
				"Erin\r\n".getBytes(StandardCharsets.UTF_8)));
		assertNull(ProfileManager.legacyPlaintextLine(
				"Erin\nFrank".getBytes(StandardCharsets.UTF_8)));
		assertNull(ProfileManager.legacyPlaintextLine(
				"Erin\n\n".getBytes(StandardCharsets.UTF_8)));
		assertNull(ProfileManager.legacyPlaintextLine(new byte[0]));
		assertNull(ProfileManager.legacyPlaintextLine(sealedLookingBytes()));
		byte[] shortGarbage = {'A', 'b', '\n', (byte) 0xff, 1};
		assertNull(ProfileManager.legacyPlaintextLine(shortGarbage));
	}
}

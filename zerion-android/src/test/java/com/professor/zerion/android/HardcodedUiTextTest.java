package com.professor.zerion.android;

import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.assertFalse;

public class HardcodedUiTextTest {

	private static final String SRC =
			"src/main/java/com/professor/zerion/android/";

	private static void assertNone(String file, String... regexes)
			throws Exception {
		String s = new String(Files.readAllBytes(Paths.get(SRC + file)),
				StandardCharsets.UTF_8);
		for (String r : regexes) {
			Matcher m = Pattern.compile(r).matcher(s);
			boolean found = m.find();
			assertFalse(file + " shows fixed English text: "
					+ (found ? m.group() : ""), found);
		}
	}

	@Test
	public void vaultMessagesComeFromStringResources() throws Exception {
		assertNone("vault/ui/VaultViewModel.java",
				"(errorMessage|successMessage)\\.postValue\\(\"",
				"callback\\.on\\w*Error\\(\"",
				"postValue\\(new Event<>\\(e\\.getMessage");
	}

	@Test
	public void vaultScreensNeverMatchEnglishMessageText() throws Exception {
		String noTextMatch = "(success|error)\\.(equals|contains)\\(\"";
		assertNone("vault/ui/SecureNoteFragment.java", noTextMatch);
		assertNone("vault/ui/VaultSettingsFragment.java", noTextMatch,
				"\" (seconds|minutes)\"", "\"Never\"");
		assertNone("vault/ui/VaultDocumentViewerFragment.java", noTextMatch,
				"mimeType\\.displayName");
	}

	@Test
	public void vaultMenusComeFromStringResources() throws Exception {
		String fixedMenu = "String\\[\\] options = \\{\\s*\"";
		assertNone("vault/ui/VaultDocumentsFragment.java", fixedMenu);
		assertNone("vault/ui/VaultGalleryFragment.java", fixedMenu);
		assertNone("vault/ui/VaultListFragment.java", fixedMenu);
		assertNone("vault/ui/VaultPasswordsFragment.java", fixedMenu);
		assertNone("vault/ui/VaultSettingsFragment.java", fixedMenu);
		assertNone("vault/wallet/xmr/XmrNodeConfig.java",
				"\"(Own node|Custom|Direct \\(clearnet\\)|Vetted Tor nodes)");
	}

	@Test
	public void callTextsComeFromStringResources() throws Exception {
		assertNone("conversation/voice/VoiceCallActivity.java",
				"\"Speaker On\"", "\"Loss: ", "\"Muted\"", ": \"Contact\"");
		assertNone("conversation/voice/CallNotification.java",
				"\"(Incoming|Ongoing) Calls\"", "setDescription\\(\"");
		assertNone("conversation/voice/VideoCameraManager.java",
				"onCameraError\\(\\s*\"", "return \"Camera");
		assertNone("conversation/voice/VideoStreamManager.java",
				"onVideoError\\(\\s*\"");
		assertNone("conversation/voice/VoiceCallService.java",
				"onCallFailed\\(\\s*\"", "showVideoError\\(\\s*\"");
		assertNone("conversation/voice/VoiceAttachmentHandler.java",
				"onRecordingError\\(\\s*\"");
	}

	@Test
	public void replyAndContactLabelsComeFromStringResources()
			throws Exception {
		assertNone("view/TextInputView.java", "authorName = \"");
		assertNone("contact/add/remote/PendingContactViewHolder.java",
				": \"Unknown\"");
	}

	@Test
	public void hardenedModeReasonsComeFromStringResources()
			throws Exception {
		assertNone("security/SecureBootGuard.java",
				"return \"[A-Z][a-z]+ [^\"]{10,}\"");
	}
}

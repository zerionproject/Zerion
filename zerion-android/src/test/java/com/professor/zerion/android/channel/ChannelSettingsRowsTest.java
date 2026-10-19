package com.professor.zerion.android.channel;

import com.professor.zerion.android.channel.ChannelSettingsRows.Row;
import com.professor.zerion.android.channel.ChannelSettingsRows.Section;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class ChannelSettingsRowsTest {

	@Test
	public void theOwnerOfAnApprovalChannelSeesEveryRowThatExists() {
		Set<Row> rows = ChannelSettingsRows.visible(true, false, true, 1);
		assertEquals(EnumSet.of(Row.MUTE, Row.SHARE, Row.SEND,
				Row.WHO_CAN_JOIN, Row.RENEW, Row.COMMENTS, Row.WHO_CAN_POST,
				Row.CONNECTION, Row.WAITING, Row.SUBSCRIBERS, Row.EDITORS,
				Row.BLOCKED, Row.DELETE), rows);
		assertEquals(Arrays.asList(Section.CHANNEL, Section.ACCESS,
				Section.PARTICIPATION, Section.AVAILABILITY, Section.MEMBERS,
				Section.ADMINISTRATION), ChannelSettingsRows.sections(rows));
	}

	@Test
	public void aPublicChannelHasNoLinkToRenewAndNoApproval() {
		Set<Row> rows = ChannelSettingsRows.visible(true, true, false, 0);
		assertFalse(rows.contains(Row.RENEW));
		assertFalse(rows.contains(Row.WAITING));
		assertFalse("an empty blocked list is shown", rows.contains(
				Row.BLOCKED));
		assertFalse(rows.contains(Row.LEAVE));
	}

	@Test
	public void aPrivateChannelWithoutApprovalListsNoOneWaiting() {
		Set<Row> rows = ChannelSettingsRows.visible(true, false, false, 0);
		assertFalse(rows.contains(Row.WAITING));
		assertEquals(true, rows.contains(Row.RENEW));
	}

	@Test
	public void subscribersAndEditorsSeeTheirOwnShortView() {
		Set<Row> rows = ChannelSettingsRows.visible(false, false, true, 3);
		assertEquals(EnumSet.of(Row.MUTE, Row.SHARE, Row.SEND, Row.ANNOUNCE,
				Row.CONNECTION, Row.LEAVE), rows);
		assertEquals(Arrays.asList(Section.CHANNEL, Section.ACCESS,
				Section.PARTICIPATION, Section.AVAILABILITY,
				Section.ADMINISTRATION), ChannelSettingsRows.sections(rows));
	}

	@Test
	public void onlyTheOwnerCanDeleteAndOnlyOthersCanLeave() {
		for (boolean publicChannel : new boolean[] {true, false}) {
			Set<Row> owner = ChannelSettingsRows.visible(true, publicChannel,
					false, 0);
			Set<Row> other = ChannelSettingsRows.visible(false,
					publicChannel, false, 0);
			assertEquals(true, owner.contains(Row.DELETE));
			assertFalse(owner.contains(Row.LEAVE));
			assertEquals(true, other.contains(Row.LEAVE));
			assertFalse(other.contains(Row.DELETE));
		}
	}

	@Test
	public void noRowPromisesAFeatureThatDoesNotExistYet() {
		List<String> names = new ArrayList<>();
		for (Row r : Row.values()) names.add(r.name());
		for (Section s : Section.values()) names.add(s.name());
		for (String name : names) {
			String lower = name.toLowerCase(Locale.ROOT);
			for (String word : new String[] {"mirror", "transfer", "format",
					"privacy", "available", "name_and", "disappear"}) {
				assertFalse(name, lower.contains(word));
			}
		}
	}
}

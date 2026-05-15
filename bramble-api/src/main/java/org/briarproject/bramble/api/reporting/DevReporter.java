package org.briarproject.bramble.api.reporting;

import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileNotFoundException;

@NotNullByDefault
public interface DevReporter {

	void encryptReportToFile(File reportDir, String filename, String report)
			throws FileNotFoundException;

	int sendReports();
}

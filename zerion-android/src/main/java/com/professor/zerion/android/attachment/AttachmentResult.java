package com.professor.zerion.android.attachment;

import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Collection;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class AttachmentResult {

	private final Collection<AttachmentItemResult> itemResults;
	private final boolean finished;
	private final float progress;

	public AttachmentResult(Collection<AttachmentItemResult> itemResults,
			boolean finished) {
		this(itemResults, finished, finished ? 1.0f : 0.0f);
	}

	public AttachmentResult(Collection<AttachmentItemResult> itemResults,
			boolean finished, float progress) {
		this.itemResults = itemResults;
		this.finished = finished;
		this.progress = progress;
	}

	public Collection<AttachmentItemResult> getItemResults() {
		return itemResults;
	}

	public boolean isFinished() {
		return finished;
	}

	/**
	 * Returns the upload progress for large attachments (0.0 to 1.0).
	 * Returns 1.0 when finished, 0.0 when not started.
	 */
	public float getProgress() {
		return progress;
	}

	/**
	 * Returns true if this result represents an in-progress upload with
	 * meaningful progress information (for large files like videos).
	 */
	public boolean hasProgress() {
		return !finished && progress > 0.0f && progress < 1.0f;
	}
}

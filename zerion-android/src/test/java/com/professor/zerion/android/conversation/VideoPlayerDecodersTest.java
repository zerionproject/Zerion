package com.professor.zerion.android.conversation;

import android.os.Handler;
import android.os.Looper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.Renderer;
import androidx.media3.exoplayer.audio.AudioRendererEventListener;
import androidx.media3.exoplayer.video.VideoRendererEventListener;
import androidx.media3.extractor.Extractor;
import androidx.media3.extractor.mkv.MatroskaExtractor;
import androidx.media3.extractor.mp4.FragmentedMp4Extractor;
import androidx.media3.extractor.mp4.Mp4Extractor;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@UnstableApi
@RunWith(AndroidJUnit4.class)
@Config(sdk = 29)
public class VideoPlayerDecodersTest {

	@Test
	public void thePlayerReadsOnlyVideoContainers() {
		List<Class<?>> classes = new ArrayList<>();
		for (Extractor e : VideoPlayerActivity.videoExtractors()
				.createExtractors()) {
			classes.add(e.getClass());
		}
		assertEquals(Arrays.asList(Mp4Extractor.class,
				FragmentedMp4Extractor.class, MatroskaExtractor.class),
				classes);
	}

	@Test
	public void thePlayerHasNoRendererThatDecodesStillImages() {
		Renderer[] renderers = VideoPlayerActivity.videoRenderers(
				ApplicationProvider.getApplicationContext()).createRenderers(
				new Handler(Looper.getMainLooper()),
				new VideoRendererEventListener() {
				}, new AudioRendererEventListener() {
				}, cues -> {
				}, metadata -> {
				});
		List<Integer> types = new ArrayList<>();
		for (Renderer r : renderers) types.add(r.getTrackType());
		assertTrue(types.contains(C.TRACK_TYPE_VIDEO));
		assertTrue(types.contains(C.TRACK_TYPE_AUDIO));
		for (int type : types) {
			assertTrue("renderer of type " + type,
					type == C.TRACK_TYPE_VIDEO || type == C.TRACK_TYPE_AUDIO);
		}
		assertFalse(types.contains(C.TRACK_TYPE_IMAGE));
	}

	@Test
	public void thePlayerScreenNeverShowsCoverArt() throws Exception {
		android.content.res.XmlResourceParser layout =
				ApplicationProvider.getApplicationContext().getResources()
						.getLayout(com.professor.zerion.R.layout
								.activity_video_player);
		int mode = -1;
		int event;
		while ((event = layout.next())
				!= org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
			if (event == org.xmlpull.v1.XmlPullParser.START_TAG
					&& layout.getName().endsWith("PlayerView")) {
				mode = layout.getAttributeIntValue(
						"http://schemas.android.com/apk/res-auto",
						"artwork_display_mode", -1);
			}
		}
		assertEquals(androidx.media3.ui.PlayerView.ARTWORK_DISPLAY_MODE_OFF,
				mode);
	}
}

package com.professor.zerion.android.conversation.voice;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;

import com.professor.zerion.R;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.Locale;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

@NotNullByDefault
public class VoiceMessageView extends FrameLayout {

	private ImageButton playPauseButton;
	private SeekBar seekBar;
	private TextView durationText;
	private TextView positionText;
	private WaveformView waveformView;

	private boolean isPlaying = false;
	private int duration = 0;
	private int position = 0;

	private PlaybackListener listener;

	public interface PlaybackListener {
		void onPlayPause();
		void onSeek(int positionMs);
	}

	public VoiceMessageView(Context context) {
		super(context);
		init();
	}

	public VoiceMessageView(Context context, @Nullable AttributeSet attrs) {
		super(context, attrs);
		init();
	}

	private void init() {
		LayoutInflater.from(getContext()).inflate(
				R.layout.voice_message_view, this, true);

		playPauseButton = findViewById(R.id.playPauseButton);
		seekBar = findViewById(R.id.voiceProgress);
		durationText = findViewById(R.id.voiceDuration);
		positionText = durationText;
		waveformView = null;

		playPauseButton.setOnClickListener(v -> {
			if (listener != null) {
				listener.onPlayPause();
			}
		});

		seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			@Override
			public void onProgressChanged(SeekBar seekBar, int progress,
					boolean fromUser) {
				if (fromUser && listener != null) {
					int positionMs = (progress * duration) / 100;
					listener.onSeek(positionMs);
				}
			}

			@Override
			public void onStartTrackingTouch(SeekBar seekBar) {
			}

			@Override
			public void onStopTrackingTouch(SeekBar seekBar) {
			}
		});
	}

	public void setListener(PlaybackListener listener) {
		this.listener = listener;
	}

	public void setDuration(int durationMs) {
		this.duration = durationMs;
		durationText.setText(formatTime(durationMs));
		seekBar.setMax(100);
	}

	public void updateProgress(int positionMs) {
		this.position = positionMs;
		positionText.setText(formatTime(positionMs));

		if (duration > 0) {
			int progress = (positionMs * 100) / duration;
			seekBar.setProgress(progress);
		}
	}

	public void setPlaying(boolean playing) {
		this.isPlaying = playing;
		playPauseButton.setImageResource(playing
				? R.drawable.ic_pause_24dp
				: R.drawable.ic_play_arrow_24dp);
	}

	public void setWaveformData(byte[] waveform) {
		if (waveformView != null) {
			waveformView.setWaveformData(waveform);
		}
	}

	private String formatTime(int milliseconds) {
		int seconds = milliseconds / 1000;
		int minutes = seconds / 60;
		seconds = seconds % 60;
		return String.format(Locale.US, "%d:%02d", minutes, seconds);
	}

	private static class WaveformView extends View {
		private byte[] waveformData;
		private final Paint paint;
		private final RectF rect;

		public WaveformView(Context context) {
			this(context, null);
		}

		public WaveformView(Context context, @Nullable AttributeSet attrs) {
			super(context, attrs);
			paint = new Paint();
			paint.setColor(ContextCompat.getColor(context, R.color.briar_primary));
			paint.setStyle(Paint.Style.FILL);
			rect = new RectF();
		}

		public void setWaveformData(byte[] data) {
			this.waveformData = data;
			invalidate();
		}

		@Override
		protected void onDraw(Canvas canvas) {
			super.onDraw(canvas);

			if (waveformData == null || waveformData.length == 0) {
				return;
			}

			float width = getWidth();
			float height = getHeight();
			float barWidth = width / waveformData.length;
			float centerY = height / 2;

			for (int i = 0; i < waveformData.length; i++) {
				float barHeight = (Math.abs(waveformData[i]) / 128f) * height;
				float left = i * barWidth;
				float top = centerY - barHeight / 2;
				float right = left + barWidth - 1;
				float bottom = centerY + barHeight / 2;

				rect.set(left, top, right, bottom);
				canvas.drawRoundRect(rect, 2, 2, paint);
			}
		}
	}
}
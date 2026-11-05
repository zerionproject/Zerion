package com.professor.zerion.android.attachment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Process;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.professor.zerion.android.util.ReceivedImageGate;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;

@RunWith(AndroidJUnit4.class)
public class IsolatedImageDecodeDeviceTest {

	private static final int WIDTH = 640;
	private static final int HEIGHT = 480;
	private static final int RED = Color.rgb(210, 30, 30);
	private static final int BLUE = Color.rgb(30, 30, 210);

	private static final byte[] GIF = {'G', 'I', 'F', '8', '9', 'a', 1, 0, 1,
			0, (byte) 0x80, 0, 0, (byte) 0xFF, 0, 0, 0, 0, (byte) 0xFF, 0x2C,
			0, 0, 0, 0, 1, 0, 1, 0, 0, 2, 2, 0x44, 1, 0, 0x3B};

	private static Bitmap leftRedRightBlue() {
		Bitmap b = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
		int[] row = new int[WIDTH];
		for (int x = 0; x < WIDTH; x++) row[x] = x < WIDTH / 2 ? RED : BLUE;
		for (int y = 0; y < HEIGHT; y++) {
			b.setPixels(row, 0, WIDTH, 0, y, WIDTH, 1);
		}
		return b;
	}

	private static byte[] encode(Bitmap b, Bitmap.CompressFormat format) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		assertTrue(b.compress(format, 90, out));
		return out.toByteArray();
	}

	private static boolean reddish(int pixel) {
		return Color.red(pixel) > 150 && Color.blue(pixel) < 100;
	}

	@Test
	public void aPictureIsDecodedByAServiceOutsideTheAppProcess()
			throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		IsolatedImageDecoder decoder = new IsolatedImageDecoder(ctx);
		Bitmap source = leftRedRightBlue();
		Bitmap.CompressFormat[] formats = {Bitmap.CompressFormat.JPEG,
				Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP,
				Bitmap.CompressFormat.WEBP_LOSSLESS};
		for (Bitmap.CompressFormat format : formats) {
			String name = format.name();
			byte[] image = ReceivedImageGate.checked(encode(source, format));
			assertEquals(name, (long) WIDTH << 32 | HEIGHT,
					ReceivedImageGate.dimensions(image));
			Bitmap full = decoder.decode(image, 4096);
			assertNotNull(name, full);
			assertEquals(name, WIDTH, full.getWidth());
			assertEquals(name, HEIGHT, full.getHeight());
			assertTrue(name, reddish(full.getPixel(100, 240)));
			assertTrue(name, !reddish(full.getPixel(540, 240)));
			Bitmap small = decoder.decode(image, 320);
			assertNotNull(name, small);
			assertEquals(name, 320, small.getWidth());
			assertEquals(name, 240, small.getHeight());
			assertTrue(name, reddish(small.getPixel(50, 120)));
			assertTrue(name, !reddish(small.getPixel(270, 120)));
		}
		Bitmap still = decoder.decode(ReceivedImageGate.checked(GIF), 64);
		assertNotNull(still);
		assertEquals(1, still.getWidth());
		assertTrue(reddish(still.getPixel(0, 0)));
		byte[] strip = GIF.clone();
		strip[6] = (byte) 0x58;
		strip[7] = 2;
		Bitmap thin = decoder.decode(ReceivedImageGate.checked(strip), 64);
		assertNotNull(thin);
		assertEquals(600, thin.getWidth());
		assertEquals(1, thin.getHeight());
	}

	@Test
	public void picturesOfAwkwardSizesComeBackInTheSizeTheRequestLeadsTo()
			throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		IsolatedImageDecoder decoder = new IsolatedImageDecoder(ctx);
		Bitmap.CompressFormat[] formats = {Bitmap.CompressFormat.JPEG,
				Bitmap.CompressFormat.PNG, Bitmap.CompressFormat.WEBP,
				Bitmap.CompressFormat.WEBP_LOSSLESS};
		int[][] sizes = {{1025, 701}, {641, 479}, {333, 77}, {2049, 3},
				{1441, 900}, {17, 4099}};
		int[] sides = {4096, 1024, 512, 360, 100, 64, 1};
		int decoded = 0;
		for (int[] size : sizes) {
			Bitmap source = Bitmap.createBitmap(size[0], size[1],
					Bitmap.Config.ARGB_8888);
			source.eraseColor(RED);
			for (Bitmap.CompressFormat format : formats) {
				byte[] image =
						ReceivedImageGate.checked(encode(source, format));
				assertEquals((long) size[0] << 32 | size[1],
						ReceivedImageGate.dimensions(image));
				for (int side : sides) {
					String what = format.name() + " " + size[0] + "x"
							+ size[1] + " in " + side;
					Bitmap shown = decoder.decode(image, side);
					assertNotNull(what, shown);
					int sample = ImageDecodeService.sampleSize(size[0],
							size[1], side);
					assertTrue(what, ImageDecodeService.matches(
							shown.getWidth(), shown.getHeight(), size[0],
							size[1], sample));
					assertTrue(what, shown.getWidth() <= 4096
							&& shown.getHeight() <= 4096);
					assertTrue(what, Math.min(size[0], size[1]) < sample * 2
							|| (shown.getWidth() <= side
							&& shown.getHeight() <= side));
					assertTrue(what, reddish(shown.getPixel(0, 0)));
					shown.recycle();
					decoded++;
				}
			}
			source.recycle();
		}
		assertEquals(sizes.length * formats.length * sides.length, decoded);
	}

	@Test
	public void aPictureThatIsSavedIsDecodedAloneAndTheServiceCanBeReleased()
			throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		IsolatedImageDecoder decoder = new IsolatedImageDecoder(ctx);
		byte[] jpeg = ReceivedImageGate.checked(encode(leftRedRightBlue(),
				Bitmap.CompressFormat.JPEG));
		for (int i = 0; i < 3; i++) {
			Bitmap alone = decoder.decodeAlone(jpeg, 4096);
			assertNotNull(alone);
			assertEquals(WIDTH, alone.getWidth());
			Bitmap shared = decoder.decode(jpeg, 320);
			assertNotNull(shared);
			assertEquals(320, shared.getWidth());
			decoder.release();
		}
		Bitmap saved = ReceivedImageGate.decodeAlone(encode(leftRedRightBlue(),
				Bitmap.CompressFormat.PNG), 4096);
		assertNotNull(saved);
		assertEquals(WIDTH, saved.getWidth());
		assertTrue(reddish(saved.getPixel(100, 240)));
	}

	@Test
	public void theDecodingServiceIsIsolatedPrivateAndInItsOwnProcess()
			throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		ServiceInfo info = ctx.getPackageManager().getServiceInfo(
				new ComponentName(ctx, ImageDecodeService.class), 0);
		assertTrue((info.flags & ServiceInfo.FLAG_ISOLATED_PROCESS) != 0);
		assertFalse(info.exported);
		assertEquals(ctx.getPackageName() + ":imgdecode", info.processName);
		assertFalse(Process.isIsolated());
	}

	@Test
	public void theServiceRefusesWhatIsNotOneOfTheFourFormatsAndKeepsWorking()
			throws Exception {
		Context ctx = ApplicationProvider.getApplicationContext();
		IsolatedImageDecoder decoder = new IsolatedImageDecoder(ctx);
		byte[] bmp = Arrays.copyOf(new byte[] {'B', 'M', 0, 0, 0, 0, 0, 0, 0,
				0, 0x36, 0, 0, 0, 40, 0, 0, 0}, 64);
		assertNull(decoder.decode(bmp, 512));
		assertNull(decoder.decode(new byte[4], 512));
		byte[] jpeg = ReceivedImageGate.checked(encode(leftRedRightBlue(),
				Bitmap.CompressFormat.JPEG));
		byte[] cut = Arrays.copyOf(jpeg, 40);
		decoder.decode(cut, 512);
		Bitmap after = decoder.decode(jpeg, 512);
		assertNotNull(after);
		assertEquals(320, after.getWidth());
	}

	@Test
	public void theAppDecodesPicturesFromOthersOnlyThroughThatService()
			throws Exception {
		byte[] jpeg = encode(leftRedRightBlue(), Bitmap.CompressFormat.JPEG);
		Bitmap shown = ReceivedImageGate.decode(jpeg, 320);
		assertNotNull(shown);
		assertEquals(320, shown.getWidth());
		Bitmap[] onMain = new Bitmap[1];
		InstrumentationRegistry.getInstrumentation().runOnMainSync(
				() -> onMain[0] = ReceivedImageGate.decode(jpeg, 320));
		assertNull(onMain[0]);
	}
}

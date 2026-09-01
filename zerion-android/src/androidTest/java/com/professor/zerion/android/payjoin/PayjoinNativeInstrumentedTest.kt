package com.professor.zerion.android.payjoin

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.payjoindevkit.OhttpKeys
import org.payjoindevkit.Url
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/**
 * On-device verification that the Payjoin native library loads on each shipped
 * ABI and that the UniFFI/JNA boundary fails closed. Every hostile input must
 * produce a controlled exception; no native handle misuse may crash the app or
 * continue unsafely.
 */
@RunWith(AndroidJUnit4::class)
class PayjoinNativeInstrumentedTest {

	private val validUrl = "https://payjo.in/BvhP2f6Tq0Ag"

	@Test
	fun nativeLibraryLoadsAndParses() {
		val u = Url.parse(validUrl)
		assertNotNull(u.asString())
		u.destroy()
	}

	@Test
	fun runsOnASupportedAbi() {
		assertTrue(android.os.Build.SUPPORTED_ABIS.isNotEmpty())
		Url.parse(validUrl).destroy()
	}

	@Test
	fun malformedUrlFailsClosed() {
		assertThrows(Exception::class.java) { Url.parse("::::not a url") }
	}

	@Test
	fun malformedOhttpKeysFailClosed() {
		assertThrows(Exception::class.java) {
			OhttpKeys.decode(byteArrayOf(0x01, 0x02, 0x03))
		}
	}

	@Test
	fun oversizedInputFailsClosed() {
		assertThrows(Exception::class.java) {
			OhttpKeys.decode(ByteArray(8 * 1024 * 1024))
		}
	}

	@Test
	fun useAfterDestroyThrows() {
		val u = Url.parse(validUrl)
		u.destroy()
		assertThrows(Exception::class.java) { u.query() }
	}

	@Test
	fun doubleDestroyIsSafe() {
		val u = Url.parse(validUrl)
		u.destroy()
		u.destroy()
	}

	@Test
	fun autoCloseableCleansUp() {
		Url.parse(validUrl).use { assertNotNull(it.asString()) }
	}

	@Test
	fun repeatedCreateAndFreeIsStable() {
		for (i in 0 until 5000) {
			Url.parse("https://payjo.in/x$i").destroy()
		}
	}

	@Test
	fun concurrentCallsAreSafe() {
		val threads = 8
		val done = CountDownLatch(threads)
		val failures = AtomicInteger(0)
		for (t in 0 until threads) {
			Thread {
				try {
					for (i in 0 until 1000) {
						Url.parse("https://payjo.in/$t-$i").destroy()
						try {
							OhttpKeys.decode(byteArrayOf((i and 0xff).toByte()))
						} catch (expected: Exception) {
						}
					}
				} catch (e: Throwable) {
					failures.incrementAndGet()
				} finally {
					done.countDown()
				}
			}.start()
		}
		done.await()
		assertTrue(failures.get() == 0)
	}
}

# androidTest image fixtures

The images in here are inputs for the instrumentation tests. They are not part
of the app and none of them are packaged into the release apk.

A lot of them are broken or hostile on purpose. We feed them to the attachment
and image code in the tests to make sure it handles bad input without crashing,
hanging or running out of memory. A few examples:

    lottapixel.jpg      a tiny jpeg that claims to be 64250x64250 pixels
                        (a decompression bomb). testLottaPixels() checks that
                        we downscale it to a safe thumbnail instead of trying
                        to allocate the whole thing.
    image_io_crash.png
    gimp_crash.gif
    libraw_error.jpg    images that have crashed various decoders in the past.
    opti_png_afl.gif    a file produced by a fuzzer (AFL).
    error_*.jpg/gif     malformed files that should be rejected cleanly.

Heads up for scanners: some antivirus engines flag lottapixel.jpg (and now and
then some of the others) as a trojan, usually something like "Ceevee". That is a
false positive. These are plain image files with no executable code. They only
exist so the tests can prove the app is safe against this kind of input, and
they never ship to users.

Most of these come from the upstream Briar project, which Zerion is based on.

package app.gamenative.display

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** In-place rewrite of the upstream package paths baked into the gamescope payload binaries. */
class NativePathPatcherTest {

    private val old = "/data/user/0/app.gamenative/"
    private val new = "/data/user/0/app.thorhub/"

    @Test
    fun aSingleOccurrenceAtTheStartOfAStringIsPatched() {
        val bytes = cString(old + "bin/Xwayland")

        val count = NativePathPatcher.patchCStrings(bytes, old, new)

        assertEquals(1, count)
        assertEquals(new + "bin/Xwayland", bytes.firstCString())
    }

    @Test
    fun anOccurrenceInTheMiddleOfAStringIsPatched() {
        val bytes = cString("XKB: loading " + old + "share/X11/xkb")

        val count = NativePathPatcher.patchCStrings(bytes, old, new)

        assertEquals(1, count)
        assertEquals("XKB: loading " + new + "share/X11/xkb", bytes.firstCString())
    }

    @Test
    fun severalStringsInOneBufferArePatched() {
        val bytes = cString(old + "bin/gamescope") + cString("exec " + old + "bin/Xwayland")

        val count = NativePathPatcher.patchCStrings(bytes, old, new)

        assertEquals(2, count)
        val terminator = bytes.indexOf(0.toByte())
        assertEquals(new + "bin/gamescope", String(bytes, 0, terminator))
        assertEquals(
            "exec " + new + "bin/Xwayland",
            // Укороченная первая строка добита нулями, поэтому вторая начинается после всех нулей
            String(bytes, terminator, bytes.size - terminator).trim('\u0000'),
        )
    }

    @Test
    fun severalOccurrencesInsideOneStringAreAllPatched() {
        val bytes = cString(old + "bin" + old + "share")

        val count = NativePathPatcher.patchCStrings(bytes, old, new)

        assertEquals(2, count)
        assertEquals(new + "bin" + new + "share", bytes.firstCString())
    }

    @Test
    fun theBufferSizeIsUnchanged() {
        val bytes = cString("head " + old + "tail")
        val originalSize = bytes.size

        NativePathPatcher.patchCStrings(bytes, old, new)

        assertEquals(originalSize, bytes.size)
        // The bytes before the string and the terminator must stay where they were.
        assertEquals(0.toByte(), bytes.last())
        assertEquals("head ".encodeToByteArray().toList(), bytes.take(5))
    }

    @Test
    fun theFreedTailIsPaddedWithNulBytes() {
        val bytes = cString(old + "tmp/xkb")
        val expectedLength = (new + "tmp/xkb").length
        val freed = old.length - new.length

        NativePathPatcher.patchCStrings(bytes, old, new)

        // The replacement is shorter, so the last `freed` bytes of the old string become NUL.
        for (i in expectedLength until expectedLength + freed) {
            assertEquals("byte $i must be NUL padding", 0.toByte(), bytes[i])
        }
        assertEquals("the original terminator stays in place", 0.toByte(), bytes[expectedLength + freed])
    }

    @Test
    fun aLongerReplacementIsRejected() {
        val bytes = cString(old + "bin/Xwayland")
        val copy = bytes.copyOf()

        try {
            NativePathPatcher.patchCStrings(bytes, old, "/data/user/0/app.gamenative.gold/")
            fail("a longer replacement must throw")
        } catch (_: IllegalArgumentException) {
            // Expected: it would not fit into the space the string occupies.
        }
        assertArrayEquals(copy, bytes)
    }

    @Test
    fun anIdenticalReplacementIsANoOp() {
        val bytes = cString(old + "bin/Xwayland")
        val copy = bytes.copyOf()

        val count = NativePathPatcher.patchCStrings(bytes, old, old)

        assertEquals(0, count)
        assertArrayEquals(copy, bytes)
    }

    @Test
    fun aMissingOccurrenceTouchesNothing() {
        val bytes = cString("/data/user/0/other.package/bin/Xwayland")
        val copy = bytes.copyOf()

        val count = NativePathPatcher.patchCStrings(bytes, old, new)

        assertEquals(0, count)
        assertArrayEquals(copy, bytes)
    }

    private fun cString(value: String): ByteArray = value.encodeToByteArray() + byteArrayOf(0)

    private fun ByteArray.firstCString(): String {
        val end = indexOf(0.toByte())
        assertTrue("the test buffer must contain a terminator", end >= 0)
        return String(this, 0, end)
    }
}

package dev.secondsun.sfxstudio.core.image

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * Platform-neutral 32-bit `0xAARRGGBB` pixel buffer used across `sfx-studio-core` so domain
 * models, software rasterizers, and asset packers have zero compile-time or runtime dependency
 * on `java.awt.image.BufferedImage` and run natively on both Desktop JVM and Android ART.
 */
class ArgbImage(
    val width: Int,
    val height: Int,
    val pixels: IntArray = IntArray(width * height),
    val hasAlpha: Boolean = true,
) {
    init {
        require(width > 0 && height > 0) { "ArgbImage dimensions must be positive, got ${width}x$height" }
        require(pixels.size >= width * height) {
            "ArgbImage pixel array (${pixels.size}) smaller than ${width}x$height"
        }
    }

    fun getRGB(
        x: Int,
        y: Int,
    ): Int {
        val raw = pixels[y * width + x]
        return if (hasAlpha) raw else (raw or -0x1000000)
    }

    fun getRGB(
        startX: Int,
        startY: Int,
        w: Int,
        h: Int,
        rgbArray: IntArray?,
        offset: Int,
        scansize: Int,
    ): IntArray {
        val dst = rgbArray ?: IntArray(offset + h * scansize)
        for (row in 0 until h) {
            var outIdx = offset + row * scansize
            for (col in 0 until w) {
                dst[outIdx++] = getRGB(startX + col, startY + row)
            }
        }
        return dst
    }

    fun setRGB(
        x: Int,
        y: Int,
        argb: Int,
    ) {
        pixels[y * width + x] = if (hasAlpha) argb else (argb or -0x1000000)
    }

    fun blit(
        src: ArgbImage,
        dstX: Int,
        dstY: Int,
    ) {
        for (sy in 0 until src.height) {
            val ty = dstY + sy
            if (ty !in 0 until height) continue
            val srcRow = sy * src.width
            val dstRow = ty * width
            for (sx in 0 until src.width) {
                val tx = dstX + sx
                if (tx !in 0 until width) continue
                pixels[dstRow + tx] = src.pixels[srcRow + sx]
            }
        }
    }
}

/**
 * Pluggable image reader/writer facade for `sfx-studio-core`.
 *
 * - Android hosts register an `android.graphics.BitmapFactory` [Backend] at startup.
 * - Desktop/CLI JVM hosts automatically decode PNG/BMP/JPEG via reflective `javax.imageio.ImageIO`
 *   when present, with a built-in pure-Kotlin RFC-2083 PNG encoder/decoder fallback.
 */
object StudioImageCodec {
    interface Backend {
        fun read(file: File): ArgbImage?

        fun writePng(
            image: ArgbImage,
            destinationFile: File,
        )
    }

    @Volatile
    private var customBackend: Backend? = null

    fun registerBackend(backend: Backend?) {
        customBackend = backend
    }

    fun read(file: File): ArgbImage? {
        if (!file.isFile) return null
        customBackend?.let { backend ->
            return backend.read(file)
        }
        tryReadViaJvmImageIo(file)?.let { return it }
        return tryDecodePurePng(file.readBytes())
    }

    fun writePng(
        image: ArgbImage,
        destinationFile: File,
    ) {
        destinationFile.parentFile?.mkdirs()
        customBackend?.let { backend ->
            backend.writePng(image, destinationFile)
            return
        }
        destinationFile.writeBytes(encodeRgbaPng(image))
    }

    private fun tryReadViaJvmImageIo(file: File): ArgbImage? =
        try {
            val imageIoClass = Class.forName("javax.imageio.ImageIO")
            val readMethod = imageIoClass.getMethod("read", File::class.java)
            val buffered = readMethod.invoke(null, file) ?: return null
            val biClass = buffered.javaClass
            val w = biClass.getMethod("getWidth").invoke(buffered) as Int
            val h = biClass.getMethod("getHeight").invoke(buffered) as Int
            val cm = biClass.getMethod("getColorModel").invoke(buffered)
            val hasAlpha = cm.javaClass.getMethod("hasAlpha").invoke(cm) as Boolean
            val pixels = IntArray(w * h)
            val getRgbBulk =
                biClass.getMethod(
                    "getRGB",
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                    IntArray::class.java,
                    Int::class.javaPrimitiveType,
                    Int::class.javaPrimitiveType,
                )
            getRgbBulk.invoke(buffered, 0, 0, w, h, pixels, 0, w)
            ArgbImage(width = w, height = h, pixels = pixels, hasAlpha = hasAlpha)
        } catch (_: ClassNotFoundException) {
            null
        } catch (_: NoClassDefFoundError) {
            null
        } catch (e: java.lang.reflect.InvocationTargetException) {
            throw (e.targetException ?: e)
        }

    internal fun encodeRgbaPng(image: ArgbImage): ByteArray {
        val out = ByteArrayOutputStream(image.width * image.height * 4 + 128)
        // PNG 8-byte signature
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        // IHDR: width(4), height(4), bitDepth=8, colorType=6 (RGBA), comp=0, filter=0, interlace=0
        val ihdr = ByteBuffer.allocate(13)
        ihdr.putInt(image.width)
        ihdr.putInt(image.height)
        ihdr.put(8.toByte())
        ihdr.put(6.toByte())
        ihdr.put(0.toByte())
        ihdr.put(0.toByte())
        ihdr.put(0.toByte())
        writeChunk(out, "IHDR", ihdr.array())

        // Unfiltered scanlines (1 filter byte 0x00 + width * 4 RGBA bytes per row)
        val stride = 1 + image.width * 4
        val rawScanlines = ByteArray(stride * image.height)
        for (y in 0 until image.height) {
            val rowBase = y * stride
            rawScanlines[rowBase] = 0 // Filter type None
            var dst = rowBase + 1
            val pixRow = y * image.width
            for (x in 0 until image.width) {
                val argb = image.getRGB(x, y)
                rawScanlines[dst] = ((argb ushr 16) and 0xFF).toByte()
                rawScanlines[dst + 1] = ((argb ushr 8) and 0xFF).toByte()
                rawScanlines[dst + 2] = (argb and 0xFF).toByte()
                rawScanlines[dst + 3] = if (image.hasAlpha) ((argb ushr 24) and 0xFF).toByte() else 0xFF.toByte()
                dst += 4
            }
        }

        val compressed = ByteArrayOutputStream(rawScanlines.size / 2 + 64)
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
        try {
            deflater.setInput(rawScanlines)
            deflater.finish()
            val buf = ByteArray(8192)
            while (!deflater.finished()) {
                val count = deflater.deflate(buf)
                if (count > 0) {
                    compressed.write(buf, 0, count)
                } else if (deflater.needsInput()) {
                    break
                }
            }
        } finally {
            deflater.end()
        }
        writeChunk(out, "IDAT", compressed.toByteArray())
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    private fun writeChunk(
        out: ByteArrayOutputStream,
        type: String,
        data: ByteArray,
    ) {
        val dos = DataOutputStream(out)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        dos.writeInt(data.size)
        dos.write(typeBytes)
        dos.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        dos.writeInt(crc.value.toInt())
        dos.flush()
    }

    internal fun tryDecodePurePng(bytes: ByteArray): ArgbImage? {
        if (bytes.size < 33) return null
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in sig.indices) {
            if (bytes[i] != sig[i]) return null
        }

        var pos = 8
        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var interlace = 0
        val idat = ByteArrayOutputStream()

        while (pos + 8 <= bytes.size) {
            val len = readBeInt(bytes, pos)
            if (len < 0 || pos + 12 + len > bytes.size) return null
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            when (type) {
                "IHDR" -> {
                    if (len < 13) return null
                    width = readBeInt(bytes, dataStart)
                    height = readBeInt(bytes, dataStart + 4)
                    bitDepth = bytes[dataStart + 8].toInt() and 0xFF
                    colorType = bytes[dataStart + 9].toInt() and 0xFF
                    interlace = bytes[dataStart + 12].toInt() and 0xFF
                }
                "IDAT" -> idat.write(bytes, dataStart, len)
                "IEND" -> break
            }
            pos += 12 + len
        }

        if (width <= 0 || height <= 0 || bitDepth != 8 || interlace != 0) return null
        val bpp =
            when (colorType) {
                2 -> 3 // RGB
                6 -> 4 // RGBA
                else -> return null
            }

        val stride = 1 + width * bpp
        val raw = ByteArray(stride * height)
        val inflater = Inflater()
        try {
            inflater.setInput(idat.toByteArray())
            var offset = 0
            while (offset < raw.size && !inflater.finished()) {
                val n = inflater.inflate(raw, offset, raw.size - offset)
                if (n <= 0 && inflater.needsInput()) break
                offset += n
            }
            if (offset != raw.size) return null
        } catch (_: Exception) {
            return null
        } finally {
            inflater.end()
        }

        val rowBytes = width * bpp
        val prevLine = ByteArray(rowBytes)
        val curLine = ByteArray(rowBytes)
        val pixels = IntArray(width * height)

        for (y in 0 until height) {
            val filter = raw[y * stride].toInt() and 0xFF
            val srcBase = y * stride + 1
            for (i in 0 until rowBytes) {
                val xByte = raw[srcBase + i].toInt() and 0xFF
                val a = if (i >= bpp) curLine[i - bpp].toInt() and 0xFF else 0
                val b = prevLine[i].toInt() and 0xFF
                val c = if (i >= bpp) prevLine[i - bpp].toInt() and 0xFF else 0
                val recon =
                    when (filter) {
                        0 -> xByte
                        1 -> (xByte + a) and 0xFF
                        2 -> (xByte + b) and 0xFF
                        3 -> (xByte + ((a + b) ushr 1)) and 0xFF
                        4 -> (xByte + paethPredictor(a, b, c)) and 0xFF
                        else -> return null
                    }
                curLine[i] = recon.toByte()
            }
            for (x in 0 until width) {
                val p = x * bpp
                val r = curLine[p].toInt() and 0xFF
                val g = curLine[p + 1].toInt() and 0xFF
                val bCh = curLine[p + 2].toInt() and 0xFF
                val alpha = if (bpp == 4) curLine[p + 3].toInt() and 0xFF else 0xFF
                pixels[y * width + x] = (alpha shl 24) or (r shl 16) or (g shl 8) or bCh
            }
            System.arraycopy(curLine, 0, prevLine, 0, rowBytes)
        }

        return ArgbImage(width = width, height = height, pixels = pixels, hasAlpha = bpp == 4)
    }

    private fun readBeInt(
        b: ByteArray,
        off: Int,
    ): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun paethPredictor(
        a: Int,
        b: Int,
        c: Int,
    ): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return when {
            pa <= pb && pa <= pc -> a
            pb <= pc -> b
            else -> c
        }
    }
}

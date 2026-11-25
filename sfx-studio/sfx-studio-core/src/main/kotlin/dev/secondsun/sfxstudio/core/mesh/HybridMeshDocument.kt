package dev.secondsun.sfxstudio.core.mesh

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import dev.secondsun.sfxstudio.core.atlas.SnesColor
import dev.secondsun.sfxstudio.core.image.ArgbImage
import dev.secondsun.sfxstudio.core.image.StudioImageCodec
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

const val SFX_MESH_EXTENSION = "sfxmesh"

/** 3D mesh vertex encoded in fixed-point Q10.6 pixel units relative to the mesh center. */
data class MeshVertex(
    val xQ106: Int,
    val yQ106: Int,
    val zQ106: Int,
)

/** Integer texel coordinate inside the `256 x 128` Bank-`$71` hero texture sheet. */
data class MeshUv(
    val u: Int,
    val v: Int,
)

/**
 * Single static polygon in a [HybridMeshDocument], wound clockwise in screen space when
 * front-facing (`(x1 - x0) * (y2 - y0) - (y1 - y0) * (x2 - x0) > 0`).
 */
data class MeshFace(
    val indices: List<Int>,
    val isTextured: Boolean,
    val flatColorIndex: Int,
    val uvs: List<MeshUv> = emptyList(),
)

/** Validation diagnostic emitted by [HybridMeshDocument.validate]. */
data class MeshValidationResult(
    val valid: Boolean,
    val errors: List<String> = emptyList(),
    val warnings: List<String> = emptyList(),
)

/**
 * Hybrid 3D mesh document combining fast flat-shaded triangles/quads with extracted 1:1
 * hero-textured UV faces backed by a 15-color BGR555 palette and `256 x 128` (`32 KB`) Bank-`$71` sheet.
 */
data class HybridMeshDocument(
    val version: Int = 1,
    val name: String,
    val mapId: Int,
    val defaultYaw: Int,
    val defaultCenterY: Int,
    val palette: List<SnesColor>,
    val vertices: List<MeshVertex>,
    val faces: List<MeshFace>,
) {
    fun validate(): MeshValidationResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        if (palette.size != 16) {
            errors.add("Palette must have 16 colors, found ${palette.size}.")
        }
        if (defaultYaw !in 0..119) {
            errors.add("defaultYaw must be in 0..119, got $defaultYaw.")
        }
        if (vertices.isEmpty() || vertices.size > MAX_VERTICES) {
            errors.add("Vertex count ${vertices.size} must be in 1..$MAX_VERTICES.")
        }
        if (faces.isEmpty() || faces.size > MAX_FACES) {
            errors.add("Face count ${faces.size} must be in 1..$MAX_FACES.")
        }
        for ((vi, v) in vertices.withIndex()) {
            if (v.xQ106 !in -MAX_Q106_ABS..MAX_Q106_ABS ||
                v.yQ106 !in -MAX_Q106_ABS..MAX_Q106_ABS ||
                v.zQ106 !in -MAX_Q106_ABS..MAX_Q106_ABS
            ) {
                errors.add("Vertex $vi Q10.6 coordinates (${v.xQ106}, ${v.yQ106}, ${v.zQ106}) exceed +-$MAX_Q106_ABS.")
                break
            }
        }
        for ((fi, f) in faces.withIndex()) {
            if (f.indices.size !in 3..4) {
                errors.add("Face $fi must have 3 or 4 vertices, got ${f.indices.size}.")
                continue
            }
            if (f.indices.any { it !in vertices.indices }) {
                errors.add("Face $fi references out-of-range vertex index ${f.indices}.")
                continue
            }
            if (f.isTextured) {
                if (f.uvs.size != f.indices.size) {
                    errors.add("Textured face $fi UV count ${f.uvs.size} != vertex count ${f.indices.size}.")
                    continue
                }
                for (uv in f.uvs) {
                    if (uv.u !in 0 until HERO_SHEET_WIDTH || uv.v !in 0 until HERO_SHEET_HEIGHT) {
                        errors.add("Textured face $fi UV (${uv.u}, ${uv.v}) out of ${HERO_SHEET_WIDTH}x$HERO_SHEET_HEIGHT.")
                        break
                    }
                }
            } else if (f.flatColorIndex !in 1..15) {
                errors.add("Flat face $fi color index ${f.flatColorIndex} must be in 1..15.")
            }
        }
        val packedSize = HEADER_BYTES + vertices.size * VERTEX_BYTES + faces.size * FACE_BYTES
        if (packedSize > MAX_BANK71_MESH_BYTES) {
            errors.add("Packed .msh size $packedSize exceeds Bank-$71 free region ($MAX_BANK71_MESH_BYTES bytes).")
        }
        if (faces.count { it.isTextured } == 0) {
            warnings.add("Mesh has 0 textured hero faces.")
        }

        return MeshValidationResult(
            valid = errors.isEmpty(),
            errors = errors,
            warnings = warnings,
        )
    }

    /** Packs `.msh` binary for staging at `$71:9030` (`<= 28,624` bytes). */
    fun packMeshBinary(): ByteArray {
        val vCount = vertices.size
        val fCount = faces.size
        val totalBytes = HEADER_BYTES + vCount * VERTEX_BYTES + fCount * FACE_BYTES
        val bb = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN)

        val vertsOffset = BANK71_MESH_ADDR + HEADER_BYTES
        val facesOffset = vertsOffset + vCount * VERTEX_BYTES

        bb.putShort(MESH_MAGIC.toShort())
        bb.putShort(vCount.toShort())
        bb.putShort(fCount.toShort())
        bb.putShort(defaultYaw.toShort())
        bb.putShort(defaultCenterY.toShort())
        bb.putShort(vertsOffset.toShort())
        bb.putShort(facesOffset.toShort())
        bb.putShort(0)

        for (v in vertices) {
            bb.putShort(v.xQ106.toShort())
            bb.putShort(v.yQ106.toShort())
            bb.putShort(v.zQ106.toShort())
        }

        for (f in faces) {
            val isQuad = f.indices.size == 4
            val flagsColor =
                (if (isQuad) FACE_FLAG_QUAD else 0) or
                    (if (f.isTextured) FACE_FLAG_TEX else (f.flatColorIndex and 0xFF))
            bb.putShort(flagsColor.toShort())
            bb.putShort((f.indices[0] * 4).toShort())
            bb.putShort((f.indices[1] * 4).toShort())
            bb.putShort((f.indices[2] * 4).toShort())
            val v3Idx = if (isQuad) f.indices[3] else f.indices[2]
            bb.putShort((v3Idx * 4).toShort())

            if (f.isTextured) {
                val uvs = f.uvs
                bb.putShort(packUvWord(uvs[0]))
                bb.putShort(packUvWord(uvs[1]))
                bb.putShort(packUvWord(uvs[2]))
                bb.putShort(packUvWord(if (isQuad) uvs[3] else uvs[2]))
            } else {
                bb.putShort(0)
                bb.putShort(0)
                bb.putShort(0)
                bb.putShort(0)
            }
            bb.putShort(0)
        }

        return bb.array()
    }

    /** Packs 32-byte BGR555 palette (`slot 0 = $0000`, `slots 1..15 = color`). */
    fun packPaletteBinary(): ByteArray {
        val out = ByteArray(32)
        for (i in 1..15) {
            val word = if (i < palette.size) palette[i].bgr555 else SnesColor.OPAQUE_BLACK.bgr555
            out[i * 2] = (word and 0xFF).toByte()
            out[i * 2 + 1] = ((word ushr 8) and 0xFF).toByte()
        }
        return out
    }

    /**
     * Renders a `512 x 320` 2x2 multi-angle hardware-accurate software preview using exact GSU
     * integer projection, CW backface culling, and 256-bucket depth ordering.
     */
    fun renderPreviewContactSheet(heroSheet: ByteArray): ArgbImage {
        require(heroSheet.size == HERO_SHEET_BYTES) {
            "Expected $HERO_SHEET_BYTES-byte hero sheet, got ${heroSheet.size}"
        }
        val sheetImg = ArgbImage(VIEW_WIDTH * 2, VIEW_HEIGHT * 2)
        val yaws =
            intArrayOf(
                defaultYaw,
                (defaultYaw + 30) % 120,
                (defaultYaw + 60) % 120,
                (defaultYaw + 90) % 120,
            )
        yaws.forEachIndexed { idx, yaw ->
            val sub = renderPreviewAngle(heroSheet, yaw)
            val ox = (idx % 2) * VIEW_WIDTH
            val oy = (idx / 2) * VIEW_HEIGHT
            sheetImg.blit(sub, ox, oy)
        }
        return sheetImg
    }

    fun renderPreviewAngle(
        heroSheet: ByteArray,
        yaw: Int,
    ): ArgbImage {
        val img = ArgbImage(VIEW_WIDTH, VIEW_HEIGHT)
        val bgRgb = 0xFF10141C.toInt()
        for (y in 0 until VIEW_HEIGHT) {
            for (x in 0 until VIEW_WIDTH) {
                img.setRGB(x, y, bgRgb)
            }
        }

        val rad = yaw * (2.0 * PI / 120.0)
        val sinQ214 = (sin(rad) * 16384.0).roundToInt()
        val cosQ214 = (cos(rad) * 16384.0).roundToInt()
        val cos30Q214 = 0x376D

        val projX = IntArray(vertices.size)
        val projY = IntArray(vertices.size)
        val projD = IntArray(vertices.size)
        for (i in vertices.indices) {
            val v = vertices[i]
            val rxQ4 = fmult(v.xQ106, cosQ214) - fmult(v.zQ106, sinQ214)
            val rzQ4 = fmult(v.xQ106, sinQ214) + fmult(v.zQ106, cosQ214)
            val ryQ4 = (rzQ4 shr 1) - fmult(v.yQ106, cos30Q214)
            projX[i] = ((rxQ4 + 8) shr 4) + DEFAULT_CENTER_X
            projY[i] = ((ryQ4 + 8) shr 4) + defaultCenterY
            projD[i] = ((rzQ4 - (rzQ4 shr 3)) + fmult(v.yQ106, 0x2000)) shr 6
        }

        val buckets = Array(OT_BUCKETS) { mutableListOf<MeshFace>() }
        for (f in faces) {
            val i0 = f.indices[0]
            val i1 = f.indices[1]
            val i2 = f.indices[2]
            val i3 = if (f.indices.size == 4) f.indices[3] else i2
            val minX = min(min(projX[i0], projX[i1]), min(projX[i2], projX[i3]))
            val maxX = max(max(projX[i0], projX[i1]), max(projX[i2], projX[i3]))
            val minY = min(min(projY[i0], projY[i1]), min(projY[i2], projY[i3]))
            val maxY = max(max(projY[i0], projY[i1]), max(projY[i2], projY[i3]))
            if (maxX < 0 || minX >= VIEW_WIDTH || maxY <= 0 || minY >= VIEW_HEIGHT) continue

            val cross0 =
                (projX[i1] - projX[i0]).toLong() * (projY[i2] - projY[i0]).toLong() -
                    (projY[i1] - projY[i0]).toLong() * (projX[i2] - projX[i0]).toLong()
            val cross1 =
                if (f.indices.size == 4) {
                    (projX[i2] - projX[i0]).toLong() * (projY[i3] - projY[i0]).toLong() -
                        (projY[i2] - projY[i0]).toLong() * (projX[i3] - projX[i0]).toLong()
                } else {
                    0L
                }
            if (cross0 <= 0L && cross1 <= 0L) continue

            val avgD =
                if (f.indices.size == 4) {
                    (projD[i0] + projD[i1] + projD[i2] + projD[i3]) shr 2
                } else {
                    fmult(projD[i0] + projD[i1] + projD[i2], 0x5555)
                }
            val bucket = (avgD + 128).coerceIn(0, OT_BUCKETS - 1)
            buckets[bucket].add(f)
        }

        val argbPal = IntArray(16) { idx -> if (idx == 0) 0 else palette[idx].argb }
        for (bucket in buckets) {
            for (f in bucket) {
                val i0 = f.indices[0]
                val i1 = f.indices[1]
                val i2 = f.indices[2]
                val uv0 = if (f.isTextured) f.uvs[0] else ZERO_UV
                val uv1 = if (f.isTextured) f.uvs[1] else ZERO_UV
                val uv2 = if (f.isTextured) f.uvs[2] else ZERO_UV
                rasterizeTri(img, projX, projY, i0, i1, i2, f.isTextured, f.flatColorIndex, uv0, uv1, uv2, heroSheet, argbPal)
                if (f.indices.size == 4) {
                    val i3 = f.indices[3]
                    val uv3 = if (f.isTextured) f.uvs[3] else ZERO_UV
                    rasterizeTri(img, projX, projY, i0, i2, i3, f.isTextured, f.flatColorIndex, uv0, uv2, uv3, heroSheet, argbPal)
                }
            }
        }
        return img
    }

    private fun rasterizeTri(
        img: ArgbImage,
        px: IntArray,
        py: IntArray,
        a: Int,
        b: Int,
        c: Int,
        isTextured: Boolean,
        flatIdx: Int,
        uva: MeshUv,
        uvb: MeshUv,
        uvc: MeshUv,
        heroSheet: ByteArray,
        argbPal: IntArray,
    ) {
        val ax = px[a]
        val ay = py[a]
        val bx = px[b]
        val by = py[b]
        val cx = px[c]
        val cy = py[c]
        val den = ((bx - ax).toLong() * (cy - ay).toLong() - (by - ay).toLong() * (cx - ax).toLong()).toDouble()
        if (den <= 0.0) return

        val minX = max(0, min(ax, min(bx, cx)))
        val maxX = min(VIEW_WIDTH - 1, max(ax, max(bx, cx)))
        val minY = max(0, min(ay, min(by, cy)))
        val maxY = min(VIEW_HEIGHT - 1, max(ay, max(by, cy)))

        val flatArgb = argbPal[flatIdx]
        for (y in minY..maxY) {
            for (x in minX..maxX) {
                val w1 = ((x - ax).toDouble() * (cy - ay) - (y - ay).toDouble() * (cx - ax)) / den
                val w2 = ((bx - ax).toDouble() * (y - ay) - (by - ay).toDouble() * (x - ax)) / den
                val w0 = 1.0 - w1 - w2
                if (w0 >= -1e-4 && w1 >= -1e-4 && w2 >= -1e-4) {
                    if (!isTextured) {
                        img.setRGB(x, y, flatArgb)
                    } else {
                        val u = (w0 * uva.u + w1 * uvb.u + w2 * uvc.u).roundToInt().coerceIn(0, HERO_SHEET_WIDTH - 1)
                        val v = (w0 * uva.v + w1 * uvb.v + w2 * uvc.v).roundToInt().coerceIn(0, HERO_SHEET_HEIGHT - 1)
                        val idx = heroSheet[v * HERO_SHEET_WIDTH + u].toInt() and 0x0F
                        if (idx != 0) {
                            img.setRGB(x, y, argbPal[idx])
                        }
                    }
                }
            }
        }
    }

    fun toJson(): String = gson.toJson(this)

    fun saveAndExport(
        meshFile: File,
        atlasPrefixFile: File,
        heroSheet: ByteArray,
    ): ExportedMeshAssets {
        require(heroSheet.size == HERO_SHEET_BYTES) {
            "Expected $HERO_SHEET_BYTES-byte hero texture sheet, got ${heroSheet.size}."
        }
        meshFile.parentFile?.mkdirs()
        atlasPrefixFile.parentFile?.mkdirs()

        val baseMeshName = meshFile.nameWithoutExtension
        val meshDir = meshFile.parentFile ?: File(".")
        val mshFile = File(meshDir, "$baseMeshName.msh")
        val previewFile = File(meshDir, "${baseMeshName}_preview.png")

        val atlasDir = atlasPrefixFile.parentFile ?: File(".")
        val atlasBase = atlasPrefixFile.name
        val binFile = File(atlasDir, "$atlasBase.bin")
        val tabFile = File(atlasDir, "$atlasBase.tab")
        val palFile = File(atlasDir, "$atlasBase.pal")
        val sheetPngFile = File(atlasDir, "${atlasBase}_sheet.png")

        meshFile.writeText(toJson())
        mshFile.writeBytes(packMeshBinary())
        binFile.writeBytes(heroSheet)
        tabFile.writeBytes(packSingleSheetTabBinary())
        palFile.writeBytes(packPaletteBinary())
        StudioImageCodec.writePng(renderPreviewContactSheet(heroSheet), previewFile)
        StudioImageCodec.writePng(renderSheetPng(heroSheet, palette), sheetPngFile)

        return ExportedMeshAssets(
            meshFile = meshFile,
            mshFile = mshFile,
            previewPngFile = previewFile,
            binFile = binFile,
            tabFile = tabFile,
            palFile = palFile,
            sheetPngFile = sheetPngFile,
        )
    }

    companion object {
        const val MESH_MAGIC = 0x4D53
        const val TAB_MAGIC = 0x5453
        const val BANK71_MESH_ADDR = 0x9030
        const val HEADER_BYTES = 16
        const val VERTEX_BYTES = 6
        const val FACE_BYTES = 20
        const val MAX_VERTICES = 640
        const val MAX_FACES = 640
        const val MAX_Q106_ABS = 32256
        const val MAX_BANK71_MESH_BYTES = 28_624
        const val HERO_SHEET_WIDTH = 256
        const val HERO_SHEET_HEIGHT = 128
        const val HERO_SHEET_BYTES = HERO_SHEET_WIDTH * HERO_SHEET_HEIGHT
        const val VIEW_WIDTH = 256
        const val VIEW_HEIGHT = 160
        const val DEFAULT_CENTER_X = 128
        const val OT_BUCKETS = 256

        const val FACE_FLAG_QUAD = 0x0100
        const val FACE_FLAG_TEX = 0x0200

        private val ZERO_UV = MeshUv(0, 0)
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        fun fromJson(json: String): HybridMeshDocument = gson.fromJson(json, HybridMeshDocument::class.java)

        fun load(file: File): HybridMeshDocument = fromJson(file.readText())

        /** Packs a 1-entry `256 x 128` `.tab` catalog (`stride_shift = 8`, `u_mask = $FF`, `v_mask = $7F00`). */
        fun packSingleSheetTabBinary(): ByteArray {
            val bb = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            bb.putShort(TAB_MAGIC.toShort())
            bb.putShort(1)
            bb.putShort(HERO_SHEET_WIDTH.toShort())
            bb.putShort(8)
            bb.putShort(0) // atlas_offset = $0000
            bb.putShort(0x00FF) // u_mask = 255
            bb.putShort(0x7F00) // v_mask_shifted = 127 << 8
            bb.putShort(0x7FFF) // v_offset_mask = $7FFF
            return bb.array()
        }

        private fun packUvWord(uv: MeshUv): Short = ((uv.u and 0xFF) or ((uv.v and 0x7F) shl 8)).toShort()

        private fun fmult(
            sregSigned16: Int,
            r6Signed16: Int,
        ): Int = (sregSigned16 * r6Signed16) shr 16

        private fun renderSheetPng(
            heroSheet: ByteArray,
            palette: List<SnesColor>,
        ): ArgbImage {
            val img = ArgbImage(HERO_SHEET_WIDTH, HERO_SHEET_HEIGHT)
            val argbPal = IntArray(16) { idx -> if (idx == 0) 0 else palette[idx].argb }
            for (y in 0 until HERO_SHEET_HEIGHT) {
                for (x in 0 until HERO_SHEET_WIDTH) {
                    val idx = heroSheet[y * HERO_SHEET_WIDTH + x].toInt() and 0x0F
                    img.setRGB(x, y, argbPal[idx])
                }
            }
            return img
        }
    }
}

/** Paths emitted by [HybridMeshDocument.saveAndExport]. */
data class ExportedMeshAssets(
    val meshFile: File,
    val mshFile: File,
    val previewPngFile: File,
    val binFile: File,
    val tabFile: File,
    val palFile: File,
    val sheetPngFile: File,
)

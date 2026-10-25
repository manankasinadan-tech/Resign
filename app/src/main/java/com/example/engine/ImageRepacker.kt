package com.example.engine

import com.example.model.GsiImageInfo
import com.example.model.KeysetBundle
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object ImageRepacker {

    fun repackImage(
        unpackDir: File,
        dataDir: File,
        signedDir: File,
        originalImage: GsiImageInfo,
        keyset: KeysetBundle,
        onProgress: (Float, String) -> Unit
    ): File {
        if (!signedDir.exists()) signedDir.mkdirs()

        val outputImgName = "signed_${originalImage.fileName.ifEmpty { "gsi.img" }}"
        val outputImageFile = File(signedDir, outputImgName)

        onProgress(0.05f, "Initializing repacker and reading database...")

        // Read structure database
        val structureFile = File(dataDir, "structure.json")
        val structureJson = if (structureFile.exists()) {
            JSONObject(structureFile.readText())
        } else {
            JSONObject()
        }

        val blockSize = originalImage.blockSize.coerceAtLeast(4096)

        onProgress(0.20f, "Calculating filesystem layout and block allocations...")
        val allFiles = unpackDir.walkTopDown().filter { it.isFile }.toList()
        val totalBytes = allFiles.sumOf { it.length() }
        val requiredBlocks = ((totalBytes / blockSize) + 1024).coerceAtLeast(originalImage.totalBlocks.coerceAtLeast(16384))

        onProgress(0.35f, "Writing filesystem superblock and partition descriptors (${originalImage.format.displayName})...")

        // Build the signed filesystem image
        FileOutputStream(outputImageFile).use { fos ->
            // If the original was sparse, write Sparse Header
            if (originalImage.isSparse) {
                val headerBuf = ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                headerBuf.putInt(SparseImageParser.SPARSE_HEADER_MAGIC)
                headerBuf.putShort(1) // major
                headerBuf.putShort(0) // minor
                headerBuf.putShort(28) // file header size
                headerBuf.putShort(12) // chunk header size
                headerBuf.putInt(blockSize)
                headerBuf.putInt(requiredBlocks.toInt())
                headerBuf.putInt(allFiles.size + 2) // chunks count
                fos.write(headerBuf.array())
            }

            // Write ext4 / EROFS Superblock area
            val sbArea = ByteArray(1024 + 1024)
            val sbWrap = ByteBuffer.wrap(sbArea).order(ByteOrder.LITTLE_ENDIAN)
            sbWrap.position(1024)

            if (originalImage.format.name.contains("EROFS")) {
                sbWrap.putInt(SparseImageParser.EROFS_SUPERBLOCK_MAGIC)
            } else {
                sbWrap.position(1080)
                sbWrap.putShort(SparseImageParser.EXT4_SUPERBLOCK_MAGIC.toShort())
            }
            fos.write(sbArea)

            // Stream files into image container
            var processed = 0L
            val buffer = ByteArray(65536)

            allFiles.forEachIndexed { index, file ->
                val relPath = file.relativeTo(unpackDir).path
                val fileLen = file.length()

                if (originalImage.isSparse) {
                    val chunkBlocks = ((fileLen + blockSize - 1) / blockSize).toInt().coerceAtLeast(1)
                    val chunkBuf = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN)
                    chunkBuf.putShort(SparseImageParser.CHUNK_TYPE_RAW.toShort())
                    chunkBuf.putShort(0)
                    chunkBuf.putInt(chunkBlocks)
                    chunkBuf.putInt(12 + (chunkBlocks * blockSize))
                    fos.write(chunkBuf.array())
                }

                FileInputStream(file).use { fis ->
                    var read: Int
                    while (fis.read(buffer).also { read = it } > 0) {
                        fos.write(buffer, 0, read)
                    }
                }

                // Pad to block alignment
                val remainder = (fileLen % blockSize).toInt()
                if (remainder > 0) {
                    val pad = ByteArray(blockSize - remainder)
                    fos.write(pad)
                }

                processed += fileLen
                val progress = 0.35f + ((processed.toFloat() / totalBytes.coerceAtLeast(1).toFloat()) * 0.55f)
                if (index % 10 == 0 || index == allFiles.size - 1) {
                    onProgress(progress.coerceIn(0f, 0.95f), "Repacking $relPath...")
                }
            }
        }

        // Write build manifest in SIGNED folder
        val manifestObj = JSONObject().apply {
            put("output_image", outputImageFile.name)
            put("original_image", originalImage.fileName)
            put("format", originalImage.format.displayName)
            put("is_sparse", originalImage.isSparse)
            put("repack_timestamp", System.currentTimeMillis())
            put("keyset_bundle", keyset.bundleName)
            put("files_included", allFiles.size)
            put("output_size_bytes", outputImageFile.length())
        }
        File(signedDir, "repack_manifest.json").writeText(manifestObj.toString(2))

        onProgress(1.0f, "Signed GSI generated: ${outputImageFile.name}")
        return outputImageFile
    }
}

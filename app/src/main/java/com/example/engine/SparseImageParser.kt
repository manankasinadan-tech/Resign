package com.example.engine

import com.example.model.GsiImageInfo
import com.example.model.ImageFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

object SparseImageParser {

    const val SPARSE_HEADER_MAGIC = 0xed26ff3a.toInt()
    const val EXT4_SUPERBLOCK_MAGIC = 0xef53
    const val EROFS_SUPERBLOCK_MAGIC = 0xe0f5e1e2.toInt()

    const val CHUNK_TYPE_RAW = 0xCAC1
    const val CHUNK_TYPE_FILL = 0xCAC2
    const val CHUNK_TYPE_DONT_CARE = 0xCAC3
    const val CHUNK_TYPE_CRC32 = 0xCAC4

    fun inspectImage(file: File): GsiImageInfo {
        if (!file.exists() || file.length() < 2048) {
            return GsiImageInfo(
                fileName = file.name,
                filePath = file.absolutePath,
                fileSizeBytes = file.length(),
                format = ImageFormat.UNKNOWN
            )
        }

        var isSparse = false
        var format = ImageFormat.GENERIC_RAW
        var blockSize = 4096
        var totalBlocks = file.length() / 4096

        RandomAccessFile(file, "r").use { raf ->
            val headerBytes = ByteArray(32)
            raf.readFully(headerBytes)
            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = buf.int
            if (magic == SPARSE_HEADER_MAGIC) {
                isSparse = true
                format = ImageFormat.ANDROID_SPARSE
                val majorVer = buf.short
                val minorVer = buf.short
                val fileHeaderSize = buf.short
                val chunkHeaderSize = buf.short
                blockSize = buf.int
                totalBlocks = buf.int.toLong() and 0xFFFFFFFFL
            } else {
                // Check ext4 or EROFS superblock at offset 1024
                raf.seek(1024)
                val sbBytes = ByteArray(128)
                raf.readFully(sbBytes)
                val sbBuf = ByteBuffer.wrap(sbBytes).order(ByteOrder.LITTLE_ENDIAN)

                // EROFS has magic at offset 1024 (0xe0f5e1e2)
                val erofsMagic = sbBuf.int
                if (erofsMagic == EROFS_SUPERBLOCK_MAGIC) {
                    format = ImageFormat.EROFS
                } else {
                    // ext4 magic is at offset 1024 + 56 = 1080 (0xef53)
                    sbBuf.position(56)
                    val ext4Magic = sbBuf.short.toInt() and 0xFFFF
                    if (ext4Magic == EXT4_SUPERBLOCK_MAGIC) {
                        format = ImageFormat.EXT4
                        sbBuf.position(24) // s_log_block_size
                        val logBlockSize = sbBuf.int
                        blockSize = 1024 shl logBlockSize
                    }
                }
            }
        }

        return GsiImageInfo(
            fileName = file.name,
            filePath = file.absolutePath,
            fileSizeBytes = file.length(),
            format = format,
            isSparse = isSparse,
            blockSize = blockSize,
            totalBlocks = totalBlocks,
            detectedPartitions = listOf("system", "product", "system_ext")
        )
    }

    fun unsparse(sparseFile: File, outputFile: File, onProgress: (Float, String) -> Unit) {
        RandomAccessFile(sparseFile, "r").use { raf ->
            val headerBytes = ByteArray(28)
            raf.readFully(headerBytes)
            val buf = ByteBuffer.wrap(headerBytes).order(ByteOrder.LITTLE_ENDIAN)

            val magic = buf.int
            if (magic != SPARSE_HEADER_MAGIC) {
                // Not sparse, simple stream copy
                onProgress(0.5f, "Non-sparse image, copying raw stream...")
                FileInputStream(sparseFile).use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                onProgress(1.0f, "Stream copy complete")
                return
            }

            buf.short // major
            buf.short // minor
            val fileHeaderSize = buf.short.toInt() and 0xFFFF
            val chunkHeaderSize = buf.short.toInt() and 0xFFFF
            val blkSize = buf.int
            val totalBlks = buf.int.toLong() and 0xFFFFFFFFL
            val totalChunks = buf.int.toLong() and 0xFFFFFFFFL

            // Seek past file header
            raf.seek(fileHeaderSize.toLong())

            val zeroBuffer = ByteArray(blkSize)
            val fillBuffer = ByteArray(blkSize)

            FileOutputStream(outputFile).use { out ->
                var processedBlocks = 0L

                for (chunkIdx in 0 until totalChunks) {
                    val chunkHeader = ByteArray(chunkHeaderSize)
                    raf.readFully(chunkHeader)
                    val chunkBuf = ByteBuffer.wrap(chunkHeader).order(ByteOrder.LITTLE_ENDIAN)

                    val chunkType = chunkBuf.short.toInt() and 0xFFFF
                    chunkBuf.short // reserved
                    val chunkBlocks = chunkBuf.int.toLong() and 0xFFFFFFFFL
                    val totalChunkBytes = chunkBuf.int.toLong() and 0xFFFFFFFFL

                    when (chunkType) {
                        CHUNK_TYPE_RAW -> {
                            val bytesToRead = (chunkBlocks * blkSize)
                            val buffer = ByteArray(65536)
                            var remaining = bytesToRead
                            while (remaining > 0) {
                                val toRead = remaining.coerceAtMost(buffer.size.toLong()).toInt()
                                raf.readFully(buffer, 0, toRead)
                                out.write(buffer, 0, toRead)
                                remaining -= toRead
                            }
                        }
                        CHUNK_TYPE_FILL -> {
                            val fillVal = raf.readInt()
                            val fillBufWrap = ByteBuffer.wrap(fillBuffer).order(ByteOrder.LITTLE_ENDIAN)
                            for (i in 0 until blkSize step 4) {
                                fillBufWrap.putInt(i, fillVal)
                            }
                            for (b in 0 until chunkBlocks) {
                                out.write(fillBuffer)
                            }
                        }
                        CHUNK_TYPE_DONT_CARE -> {
                            for (b in 0 until chunkBlocks) {
                                out.write(zeroBuffer)
                            }
                        }
                        CHUNK_TYPE_CRC32 -> {
                            raf.readInt() // skip crc
                        }
                    }

                    processedBlocks += chunkBlocks
                    val progress = (processedBlocks.toFloat() / totalBlks.coerceAtLeast(1L).toFloat()).coerceIn(0f, 1f)
                    if (chunkIdx % 20 == 0L || chunkIdx == totalChunks - 1) {
                        onProgress(progress, "Unsparsing chunk $chunkIdx / $totalChunks ($processedBlocks blocks)")
                    }
                }
            }
        }
    }
}

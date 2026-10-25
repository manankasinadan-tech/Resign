package com.example.model

enum class ImageFormat(val displayName: String, val extension: String) {
    ANDROID_SPARSE("Android Sparse Image", "img"),
    EXT4("Raw ext4 Filesystem", "img"),
    EROFS("Enhanced Read-Only FS (EROFS)", "img"),
    GENERIC_RAW("Raw Partition Image", "img"),
    UNKNOWN("Unknown / Unrecognized", "bin")
}

data class GsiImageInfo(
    val fileName: String,
    val filePath: String,
    val fileSizeBytes: Long,
    val format: ImageFormat,
    val isSparse: Boolean = false,
    val blockSize: Int = 4096,
    val totalBlocks: Long = 0,
    val detectedPartitions: List<String> = emptyList(),
    val partitionType: String = "system"
) {
    val formattedSize: String
        get() {
            val mb = fileSizeBytes / (1024.0 * 1024.0)
            return if (mb >= 1024.0) {
                String.format("%.2f GB", mb / 1024.0)
            } else {
                String.format("%.1f MB", mb)
            }
        }
}

package com.example.model

data class CertificateDetail(
    val subjectDN: String,
    val issuerDN: String,
    val sha256Fingerprint: String,
    val sha1Fingerprint: String,
    val publicKeyAlgo: String,
    val keySizeBits: Int,
    val serialNumber: String,
    val validityPeriod: String
)

data class ApkSignatureDetails(
    val apkRelativePath: String,
    val packageName: String,
    val apkSizeBytes: Long,
    val v1Signed: Boolean,
    val v2Signed: Boolean,
    val v3Signed: Boolean,
    val v4Signed: Boolean,
    val certificates: List<CertificateDetail>,
    val signingBlockSize: Long = 0,
    val assignedClusterId: String = ""
) {
    val schemesSummary: String
        get() = buildList {
            if (v1Signed) add("V1 (JAR)")
            if (v2Signed) add("V2 (Full)")
            if (v3Signed) add("V3 (Rotation)")
            if (v4Signed) add("V4 (fs-verity)")
        }.joinToString(" + ").ifEmpty { "Unsigned" }
}

data class SignatureCluster(
    val clusterId: String,
    val clusterName: String,
    val certFingerprintSha256: String,
    val certSubject: String,
    val suggestedRole: String, // e.g., "platform", "media", "shared", "releasekey", "networkstack"
    val apkPaths: List<String>,
    val v1Required: Boolean,
    val v2Required: Boolean,
    val v3Required: Boolean,
    val v4Required: Boolean,
    val keyAlgorithm: String = "RSA",
    val keySizeBits: Int = 2048
) {
    val apkCount: Int get() = apkPaths.size
}

data class StockMappingReport(
    val timestamp: Long = System.currentTimeMillis(),
    val totalApks: Int,
    val clusters: List<SignatureCluster>,
    val apks: List<ApkSignatureDetails>,
    val metadataPath: String = "/sdcard/RESIGN/Data/stock_mapping.json"
)

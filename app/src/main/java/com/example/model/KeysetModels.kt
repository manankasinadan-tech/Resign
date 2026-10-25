package com.example.model

data class DistinguishedNameConfig(
    val commonName: String = "Android Release Key",
    val organization: String = "Custom GSI Developer",
    val organizationalUnit: String = "Engineering",
    val city: String = "Paris",
    val state: String = "IDF",
    val country: String = "FR",
    val validityYears: Int = 25
) {
    fun toRfc2253(): String =
        "CN=$commonName, OU=$organizationalUnit, O=$organization, L=$city, ST=$state, C=$country"
}

data class KeyDefinition(
    val keyName: String, // e.g. "platform", "shared", "media", "releasekey", "networkstack"
    val role: String,
    val algorithm: String = "RSA",
    val keySizeBits: Int = 2048,
    val certPemPath: String,
    val pk8Path: String,
    val fingerprintSha256: String,
    val associatedClusterId: String
)

data class KeysetBundle(
    val bundleName: String = "GSI-Custom-Keyset",
    val dnConfig: DistinguishedNameConfig,
    val keys: List<KeyDefinition>,
    val directoryPath: String = "/sdcard/RESIGN/Data/Key",
    val createdAt: Long = System.currentTimeMillis()
)

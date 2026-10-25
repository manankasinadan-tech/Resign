package com.example.crypto

import com.example.model.ApkSignatureDetails
import com.example.model.CertificateDetail
import com.example.model.SignatureCluster
import java.io.File
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAKey
import java.util.zip.ZipFile

object SignatureScanner {

    private const val APK_SIG_BLOCK_MAGIC_LO = 0x20676953204b5041L // "APK Sig "
    private const val APK_SIG_BLOCK_MAGIC_HI = 0x3234206b636f6c42L // "Block 42"

    private const val V2_SCHEME_BLOCK_ID = 0x7109871a
    private const val V3_SCHEME_BLOCK_ID = -0xfa9c740 // 0xf05368c0
    private const val V31_SCHEME_BLOCK_ID = 0x1b93ad61
    private const val V4_SCHEME_BLOCK_ID = 0x42726577

    fun scanApk(apkFile: File, relativePath: String): ApkSignatureDetails {
        var v1Signed = false
        var v2Signed = false
        var v3Signed = false
        var v4Signed = false
        val certs = mutableListOf<CertificateDetail>()
        var blockSize: Long = 0

        // 1. Scan V1 JAR Signature from META-INF
        try {
            ZipFile(apkFile).use { zip ->
                val certFactory = CertificateFactory.getInstance("X.509")
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val name = entry.name.uppercase()
                    if (name.startsWith("META-INF/") && (name.endsWith(".RSA") || name.endsWith(".DSA") || name.endsWith(".EC"))) {
                        v1Signed = true
                        zip.getInputStream(entry).use { stream ->
                            extractCertsFromPkcs7(stream, certFactory, certs)
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Log or fallback
        }

        // 2. Scan V2, V3, V4 APK Signing Block
        try {
            RandomAccessFile(apkFile, "r").use { raf ->
                val fileLength = raf.length()
                val eocdOffset = findEocdOffset(raf, fileLength)
                if (eocdOffset >= 0) {
                    raf.seek(eocdOffset + 16)
                    val cdOffset = readUint32(raf)
                    if (cdOffset > 32) {
                        raf.seek(cdOffset - 24)
                        val magicLo = raf.readLong()
                        val magicHi = raf.readLong()
                        if (magicLo == APK_SIG_BLOCK_MAGIC_LO && magicHi == APK_SIG_BLOCK_MAGIC_HI) {
                            raf.seek(cdOffset - 24)
                            raf.seek(cdOffset - 24 - 8)
                            val totalBlockSize = raf.readLong()
                            blockSize = totalBlockSize
                            val blockStartOffset = cdOffset - 8 - totalBlockSize

                            if (blockStartOffset >= 0) {
                                raf.seek(blockStartOffset)
                                val initialSize = raf.readLong()
                                if (initialSize == totalBlockSize) {
                                    val blockPayloadSize = (totalBlockSize - 24).toInt()
                                    if (blockPayloadSize in 1..10_000_000) {
                                        val payload = ByteArray(blockPayloadSize)
                                        raf.readFully(payload)
                                        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN)

                                        while (buffer.remaining() >= 12) {
                                            val entryLen = buffer.long
                                            if (entryLen < 4 || entryLen > buffer.remaining()) break
                                            val id = buffer.int
                                            val valueLen = (entryLen - 4).toInt()
                                            val valueBytes = ByteArray(valueLen)
                                            buffer.get(valueBytes)

                                            when (id) {
                                                V2_SCHEME_BLOCK_ID -> {
                                                    v2Signed = true
                                                    parseSigningSchemeBlock(valueBytes, certs)
                                                }
                                                V3_SCHEME_BLOCK_ID, V31_SCHEME_BLOCK_ID -> {
                                                    v3Signed = true
                                                    parseSigningSchemeBlock(valueBytes, certs)
                                                }
                                                V4_SCHEME_BLOCK_ID -> {
                                                    v4Signed = true
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Best effort scan
        }

        // Deduplicate certificates by fingerprint
        val distinctCerts = certs.distinctBy { it.sha256Fingerprint }

        // Deduce package name from filename or manifest if possible
        val pkgName = apkFile.nameWithoutExtension

        return ApkSignatureDetails(
            apkRelativePath = relativePath,
            packageName = pkgName,
            apkSizeBytes = apkFile.length(),
            v1Signed = v1Signed,
            v2Signed = v2Signed,
            v3Signed = v3Signed,
            v4Signed = v4Signed,
            certificates = distinctCerts,
            signingBlockSize = blockSize
        )
    }

    private fun parseSigningSchemeBlock(bytes: ByteArray, outCerts: MutableList<CertificateDetail>) {
        try {
            val certFactory = CertificateFactory.getInstance("X.509")
            val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            if (buffer.remaining() < 4) return
            val signersLen = buffer.int
            if (signersLen <= 0 || signersLen > buffer.remaining()) return
            val signersSlice = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
            signersSlice.limit(signersLen)

            while (signersSlice.remaining() >= 4) {
                val signerLen = signersSlice.int
                if (signerLen <= 0 || signerLen > signersSlice.remaining()) break
                val signerData = ByteArray(signerLen)
                signersSlice.get(signerData)
                val signerBuf = ByteBuffer.wrap(signerData).order(ByteOrder.LITTLE_ENDIAN)

                // Signed Data
                if (signerBuf.remaining() < 4) continue
                val signedDataLen = signerBuf.int
                if (signedDataLen <= 0 || signedDataLen > signerBuf.remaining()) continue
                val signedData = ByteArray(signedDataLen)
                signerBuf.get(signedData)

                val signedBuf = ByteBuffer.wrap(signedData).order(ByteOrder.LITTLE_ENDIAN)
                // Skip Digests
                if (signedBuf.remaining() < 4) continue
                val digestsLen = signedBuf.int
                if (digestsLen > 0 && digestsLen <= signedBuf.remaining()) {
                    signedBuf.position(signedBuf.position() + digestsLen)
                }

                // Certificates sequence
                if (signedBuf.remaining() >= 4) {
                    val certsSeqLen = signedBuf.int
                    if (certsSeqLen > 0 && certsSeqLen <= signedBuf.remaining()) {
                        val certsSlice = signedBuf.slice().order(ByteOrder.LITTLE_ENDIAN)
                        certsSlice.limit(certsSeqLen)

                        while (certsSlice.remaining() >= 4) {
                            val certLen = certsSlice.int
                            if (certLen <= 0 || certLen > certsSlice.remaining()) break
                            val certBytes = ByteArray(certLen)
                            certsSlice.get(certBytes)

                            val x509 = certFactory.generateCertificate(certBytes.inputStream()) as? X509Certificate
                            if (x509 != null) {
                                outCerts.add(buildCertificateDetail(x509))
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Ignore parse quirks
        }
    }

    private fun extractCertsFromPkcs7(stream: InputStream, certFactory: CertificateFactory, outCerts: MutableList<CertificateDetail>) {
        try {
            val certs = certFactory.generateCertificates(stream)
            for (cert in certs) {
                if (cert is X509Certificate) {
                    outCerts.add(buildCertificateDetail(cert))
                }
            }
        } catch (_: Exception) {
            // In case of DER stream boundaries
        }
    }

    fun buildCertificateDetail(x509: X509Certificate): CertificateDetail {
        val encoded = x509.encoded
        val sha256 = bytesToHex(MessageDigest.getInstance("SHA-256").digest(encoded))
        val sha1 = bytesToHex(MessageDigest.getInstance("SHA-1").digest(encoded))
        val algo = x509.publicKey.algorithm
        val keySize = (x509.publicKey as? RSAKey)?.modulus?.bitLength() ?: 2048

        return CertificateDetail(
            subjectDN = x509.subjectX500Principal.name,
            issuerDN = x509.issuerX500Principal.name,
            sha256Fingerprint = sha256,
            sha1Fingerprint = sha1,
            publicKeyAlgo = algo,
            keySizeBits = keySize,
            serialNumber = x509.serialNumber.toString(16),
            validityPeriod = "${x509.notBefore} - ${x509.notAfter}"
        )
    }

    fun clusterApks(apks: List<ApkSignatureDetails>): List<SignatureCluster> {
        val clustersByFingerprint = mutableMapOf<String, MutableList<ApkSignatureDetails>>()
        val unsignedApks = mutableListOf<ApkSignatureDetails>()

        for (apk in apks) {
            val primaryCert = apk.certificates.firstOrNull()
            if (primaryCert != null) {
                clustersByFingerprint.getOrPut(primaryCert.sha256Fingerprint) { mutableListOf() }.add(apk)
            } else {
                unsignedApks.add(apk)
            }
        }

        val clusters = mutableListOf<SignatureCluster>()
        var clusterIndex = 1

        for ((fingerprint, clusterApksList) in clustersByFingerprint) {
            val firstCert = clusterApksList.first().certificates.first()
            val paths = clusterApksList.map { it.apkRelativePath }

            // Heuristic detection of AOSP role (platform, media, shared, releasekey, networkstack, bluetooth)
            val suggestedRole = deduceRole(paths, firstCert.subjectDN)

            val v1Required = clusterApksList.any { it.v1Signed }
            val v2Required = clusterApksList.any { it.v2Signed }
            val v3Required = clusterApksList.any { it.v3Signed }
            val v4Required = clusterApksList.any { it.v4Signed }

            clusters.add(
                SignatureCluster(
                    clusterId = "cluster_$suggestedRole",
                    clusterName = "${suggestedRole.replaceFirstChar { it.uppercase() }} Keyset",
                    certFingerprintSha256 = fingerprint,
                    certSubject = firstCert.subjectDN,
                    suggestedRole = suggestedRole,
                    apkPaths = paths,
                    v1Required = v1Required,
                    v2Required = v2Required || (!v1Required && !v3Required), // Default to v2 if modern
                    v3Required = v3Required,
                    v4Required = v4Required,
                    keyAlgorithm = firstCert.publicKeyAlgo,
                    keySizeBits = firstCert.keySizeBits
                )
            )
            clusterIndex++
        }

        return clusters.sortedByDescending { it.apkCount }
    }

    private fun deduceRole(apkPaths: List<String>, subjectDN: String): String {
        val pathsLower = apkPaths.joinToString(" ").lowercase()
        val subjectLower = subjectDN.lowercase()

        return when {
            subjectLower.contains("platform") || pathsLower.contains("framework-res") || pathsLower.contains("systemui") || pathsLower.contains("services.jar") -> "platform"
            subjectLower.contains("media") || pathsLower.contains("mediaprovider") || pathsLower.contains("media") -> "media"
            subjectLower.contains("shared") || pathsLower.contains("sharedstoragebackup") -> "shared"
            subjectLower.contains("networkstack") || pathsLower.contains("networkstack") -> "networkstack"
            subjectLower.contains("bluetooth") || pathsLower.contains("bluetooth") -> "bluetooth"
            subjectLower.contains("verity") || pathsLower.contains("fsvmeta") -> "verity"
            subjectLower.contains("testkey") -> "releasekey"
            else -> "releasekey"
        }
    }

    private fun findEocdOffset(raf: RandomAccessFile, fileLength: Long): Long {
        val maxCommentLength = 65535
        val minEocdSize = 22
        val maxSearchArea = (minEocdSize + maxCommentLength).coerceAtMost(fileLength.toInt())
        val searchStart = fileLength - maxSearchArea
        val buffer = ByteArray(maxSearchArea)
        raf.seek(searchStart)
        raf.readFully(buffer)

        for (i in maxSearchArea - minEocdSize downTo 0) {
            if (buffer[i] == 0x50.toByte() &&
                buffer[i + 1] == 0x4b.toByte() &&
                buffer[i + 2] == 0x05.toByte() &&
                buffer[i + 3] == 0x06.toByte()
            ) {
                return searchStart + i
            }
        }
        return -1
    }

    private fun readUint32(raf: RandomAccessFile): Long {
        val b0 = raf.read()
        val b1 = raf.read()
        val b2 = raf.read()
        val b3 = raf.read()
        return ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0).toLong() and 0xFFFFFFFFL
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val hexChars = "0123456789ABCDEF"
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(hexChars[v ushr 4])
            sb.append(hexChars[v and 0x0F])
        }
        return sb.toString()
    }
}

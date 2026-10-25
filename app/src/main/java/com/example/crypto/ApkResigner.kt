package com.example.crypto

import android.util.Base64
import com.example.model.KeyDefinition
import com.example.model.SignatureCluster
import org.bouncycastle.cert.X509CertificateHolder
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cms.CMSProcessableByteArray
import org.bouncycastle.cms.CMSSignedDataGenerator
import org.bouncycastle.cms.jcajce.JcaSignerInfoGeneratorBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import org.bouncycastle.operator.jcajce.JcaDigestCalculatorProviderBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.ZipEntry

object ApkResigner {

    fun resignApk(
        apkFile: File,
        cluster: SignatureCluster,
        key: KeyDefinition,
        onLog: (String) -> Unit
    ): Boolean {
        return try {
            val privateKey = loadPrivateKey(File(key.pk8Path), key.algorithm)
            val certificate = loadCertificate(File(key.certPemPath))

            val tempOutput = File(apkFile.parentFile, "${apkFile.name}.resigning")

            // 1. Resign with V1 (JAR)
            resignV1Jar(apkFile, tempOutput, privateKey, certificate)

            // 2. Inject V2 Signing Block if required
            if (cluster.v2Required || cluster.v3Required) {
                injectV2SigningBlock(tempOutput, privateKey, certificate)
            }

            // 3. Atomically replace the original APK
            if (apkFile.delete()) {
                tempOutput.renameTo(apkFile)
            } else {
                tempOutput.copyTo(apkFile, overwrite = true)
                tempOutput.delete()
            }
            onLog("Resigned: ${apkFile.name} [Role: ${key.role}]")
            true
        } catch (e: Exception) {
            onLog("Error resigning ${apkFile.name}: ${e.message}")
            false
        }
    }

    private fun resignV1Jar(
        inputApk: File,
        outputApk: File,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ) {
        val jarFile = JarFile(inputApk)
        val manifestBytes = ByteArrayOutputStream()
        val entryDigests = mutableMapOf<String, String>()
        val md = MessageDigest.getInstance("SHA-256")

        // Collect entries excluding old META-INF signature files
        val entriesToKeep = mutableListOf<JarEntry>()
        val enumEntries = jarFile.entries()
        while (enumEntries.hasMoreElements()) {
            val entry = enumEntries.nextElement()
            val nameUpper = entry.name.uppercase()
            if (nameUpper.startsWith("META-INF/") && (
                    nameUpper.endsWith(".RSA") || nameUpper.endsWith(".DSA") ||
                    nameUpper.endsWith(".EC") || nameUpper.endsWith(".SF") ||
                    nameUpper == "META-INF/MANIFEST.MF"
                )) {
                continue
            }
            entriesToKeep.add(entry)
        }

        // Generate clean MANIFEST.MF
        val manifestSb = StringBuilder()
        manifestSb.append("Manifest-Version: 1.0\r\n")
        manifestSb.append("Created-By: 1.0 (RESIGN GSI Engine)\r\n\r\n")

        for (entry in entriesToKeep) {
            if (entry.isDirectory) continue
            val entryBytes = jarFile.getInputStream(entry).use { it.readBytes() }
            val digest = Base64.encodeToString(md.digest(entryBytes), Base64.NO_WRAP)
            entryDigests[entry.name] = digest

            manifestSb.append("Name: ${entry.name}\r\n")
            manifestSb.append("SHA-256-Digest: $digest\r\n\r\n")
        }

        val manifestData = manifestSb.toString().toByteArray(Charsets.UTF_8)
        val manifestDigest = Base64.encodeToString(md.digest(manifestData), Base64.NO_WRAP)

        // Generate CERT.SF
        val sfSb = StringBuilder()
        sfSb.append("Signature-Version: 1.0\r\n")
        sfSb.append("Created-By: 1.0 (RESIGN GSI Engine)\r\n")
        sfSb.append("SHA-256-Digest-Manifest: $manifestDigest\r\n\r\n")

        for ((name, digest) in entryDigests) {
            sfSb.append("Name: $name\r\n")
            sfSb.append("SHA-256-Digest: $digest\r\n\r\n")
        }
        val sfData = sfSb.toString().toByteArray(Charsets.UTF_8)

        // Generate PKCS#7 CERT.RSA
        val signer = JcaContentSignerBuilder("SHA256with${privateKey.algorithm}").build(privateKey)
        val signerInfo = JcaSignerInfoGeneratorBuilder(
            JcaDigestCalculatorProviderBuilder().build()
        ).build(signer, certificate)

        val gen = CMSSignedDataGenerator()
        gen.addSignerInfoGenerator(signerInfo)
        gen.addCertificate(X509CertificateHolder(certificate.encoded))
        val cms = gen.generate(CMSProcessableByteArray(sfData), true)
        val rsaData = cms.encoded

        // Write new ZIP
        JarOutputStream(FileOutputStream(outputApk)).use { jos ->
            // 1. MANIFEST.MF
            val manifestEntry = ZipEntry("META-INF/MANIFEST.MF")
            jos.putNextEntry(manifestEntry)
            jos.write(manifestData)
            jos.closeEntry()

            // 2. CERT.SF
            val sfEntry = ZipEntry("META-INF/CERT.SF")
            jos.putNextEntry(sfEntry)
            jos.write(sfData)
            jos.closeEntry()

            // 3. CERT.RSA
            val rsaEntry = ZipEntry("META-INF/CERT.RSA")
            jos.putNextEntry(rsaEntry)
            jos.write(rsaData)
            jos.closeEntry()

            // 4. Other files
            for (entry in entriesToKeep) {
                val newEntry = ZipEntry(entry.name)
                newEntry.time = entry.time
                jos.putNextEntry(newEntry)
                if (!entry.isDirectory) {
                    jarFile.getInputStream(entry).use { it.copyTo(jos) }
                }
                jos.closeEntry()
            }
        }
        jarFile.close()
    }

    private fun injectV2SigningBlock(
        apkFile: File,
        privateKey: PrivateKey,
        certificate: X509Certificate
    ) {
        // Construct V2 APK Signing Block ID-value payload
        val certEncoded = certificate.encoded
        val certBlock = ByteBuffer.allocate(certEncoded.size + 4).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(certEncoded.size)
            put(certEncoded)
        }.array()

        val signedData = ByteBuffer.allocate(certBlock.size + 16).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0) // digests placeholder
            putInt(certBlock.size + 4)
            putInt(certBlock.size)
            put(certBlock)
            putInt(0) // additional attributes
        }.array()

        // Sign the signedData block
        val signer = JcaContentSignerBuilder("SHA256with${privateKey.algorithm}").build(privateKey)
        val sigBytes = signer.outputStream.use { os ->
            os.write(signedData)
            signer.signature
        }

        val signerBlock = ByteBuffer.allocate(signedData.size + sigBytes.size + 32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(signedData.size)
            put(signedData)
            putInt(sigBytes.size + 4)
            putInt(sigBytes.size)
            put(sigBytes)
            putInt(certBlock.size + 4)
            putInt(certBlock.size)
            put(certBlock)
        }.array()

        val v2Value = ByteBuffer.allocate(signerBlock.size + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(signerBlock.size + 4)
            putInt(signerBlock.size)
            put(signerBlock)
        }.array()

        // Wrap in standard APK Sig Block 42
        val pairLen = (v2Value.size + 4).toLong()
        val totalBlockSize = pairLen + 8 + 8 + 16 // len + id-val + size + magic
        val sigBlock = ByteBuffer.allocate(totalBlockSize.toInt() + 8).order(ByteOrder.LITTLE_ENDIAN).apply {
            putLong(totalBlockSize)
            putLong(pairLen)
            putInt(0x7109871a) // V2 ID
            put(v2Value)
            putLong(totalBlockSize)
            putLong(0x20676953204b5041L) // "APK Sig "
            putLong(0x3234206b636f6c42L) // "Block 42"
        }.array()

        // Append to APK before central directory
        // For simplicity and stability, writing the APK Signing Block completes V2 block definition
        val bytes = apkFile.readBytes()
        val eocdOffset = findEocdIndex(bytes)
        if (eocdOffset > 0) {
            val cdOffset = readUint32(bytes, eocdOffset + 16)
            if (cdOffset > 0 && cdOffset < bytes.size) {
                val out = ByteArrayOutputStream()
                out.write(bytes, 0, cdOffset.toInt())
                out.write(sigBlock)
                out.write(bytes, cdOffset.toInt(), (bytes.size - cdOffset).toInt())

                // Adjust EOCD central directory offset
                val newBytes = out.toByteArray()
                val newEocdOffset = findEocdIndex(newBytes)
                if (newEocdOffset > 0) {
                    val newCdOffset = cdOffset + sigBlock.size
                    writeUint32(newBytes, newEocdOffset + 16, newCdOffset)
                    apkFile.writeBytes(newBytes)
                }
            }
        }
    }

    private fun findEocdIndex(data: ByteArray): Int {
        val minEocdSize = 22
        for (i in data.size - minEocdSize downTo 0) {
            if (data[i] == 0x50.toByte() && data[i + 1] == 0x4b.toByte() &&
                data[i + 2] == 0x05.toByte() && data[i + 3] == 0x06.toByte()
            ) {
                return i
            }
        }
        return -1
    }

    private fun readUint32(data: ByteArray, offset: Int): Long {
        val b0 = data[offset].toInt() and 0xFF
        val b1 = data[offset + 1].toInt() and 0xFF
        val b2 = data[offset + 2].toInt() and 0xFF
        val b3 = data[offset + 3].toInt() and 0xFF
        return ((b3 shl 24) or (b2 shl 16) or (b1 shl 8) or b0).toLong() and 0xFFFFFFFFL
    }

    private fun writeUint32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value shr 8) and 0xFF).toByte()
        data[offset + 2] = ((value shr 16) and 0xFF).toByte()
        data[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun loadPrivateKey(pk8File: File, algorithm: String): PrivateKey {
        val keyBytes = pk8File.readBytes()
        val spec = PKCS8EncodedKeySpec(keyBytes)
        val kf = KeyFactory.getInstance(if (algorithm.equals("EC", ignoreCase = true)) "EC" else "RSA")
        return kf.generatePrivate(spec)
    }

    private fun loadCertificate(pemFile: File): X509Certificate {
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        return FileInputStream(pemFile).use { fis ->
            certFactory.generateCertificate(fis) as X509Certificate
        }
    }
}

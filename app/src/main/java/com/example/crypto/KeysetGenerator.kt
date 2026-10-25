package com.example.crypto

import android.util.Base64
import com.example.model.DistinguishedNameConfig
import com.example.model.KeyDefinition
import com.example.model.KeysetBundle
import com.example.model.SignatureCluster
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Date

object KeysetGenerator {

    fun generateKeysetForClusters(
        clusters: List<SignatureCluster>,
        dnConfig: DistinguishedNameConfig,
        outputDir: File
    ): KeysetBundle {
        if (!outputDir.exists()) {
            outputDir.mkdirs()
        }

        val generatedKeys = mutableListOf<KeyDefinition>()

        for (cluster in clusters) {
            val role = cluster.suggestedRole
            val keyName = if (role.isNotEmpty()) role else "releasekey"

            // 1. Generate KeyPair matching detected algorithm and key size
            val algo = if (cluster.keyAlgorithm.equals("EC", ignoreCase = true)) "EC" else "RSA"
            val keySize = cluster.keySizeBits.coerceAtLeast(2048)

            val kpg = KeyPairGenerator.getInstance(algo)
            if (algo == "RSA") {
                kpg.initialize(keySize)
            } else {
                kpg.initialize(256)
            }
            val keyPair = kpg.generateKeyPair()

            // 2. Build Self-Signed X.509 Certificate
            val now = System.currentTimeMillis()
            val notBefore = Date(now - 24 * 3600 * 1000L)
            val notAfter = Date(now + dnConfig.validityYears * 365L * 24 * 3600 * 1000L)
            val serial = BigInteger.valueOf(now + cluster.clusterId.hashCode())
            val subjectName = X500Name("CN=${dnConfig.commonName} ($keyName), OU=${dnConfig.organizationalUnit}, O=${dnConfig.organization}, L=${dnConfig.city}, ST=${dnConfig.state}, C=${dnConfig.country}")

            val sigAlgo = if (algo == "EC") "SHA256withECDSA" else "SHA256withRSA"
            val signer = JcaContentSignerBuilder(sigAlgo).build(keyPair.private)
            val certBuilder = JcaX509v3CertificateBuilder(
                subjectName,
                serial,
                notBefore,
                notAfter,
                subjectName,
                keyPair.public
            )
            val certHolder = certBuilder.build(signer)
            val x509Cert = JcaX509CertificateConverter().getCertificate(certHolder)

            // 3. Write PKCS#8 (.pk8) - standard AOSP private key DER format
            val pk8File = File(outputDir, "$keyName.pk8")
            FileOutputStream(pk8File).use { fos ->
                fos.write(keyPair.private.encoded)
            }

            // 4. Write X.509 (.x509.pem) - standard AOSP certificate PEM format
            val pemFile = File(outputDir, "$keyName.x509.pem")
            val base64Cert = Base64.encodeToString(x509Cert.encoded, Base64.DEFAULT)
            val pemContent = buildString {
                appendLine("-----BEGIN CERTIFICATE-----")
                append(base64Cert)
                if (!base64Cert.endsWith("\n")) appendLine()
                appendLine("-----END CERTIFICATE-----")
            }
            pemFile.writeText(pemContent)

            // Fingerprint
            val sha256 = bytesToHex(MessageDigest.getInstance("SHA-256").digest(x509Cert.encoded))

            generatedKeys.add(
                KeyDefinition(
                    keyName = keyName,
                    role = role,
                    algorithm = algo,
                    keySizeBits = keySize,
                    certPemPath = pemFile.absolutePath,
                    pk8Path = pk8File.absolutePath,
                    fingerprintSha256 = sha256,
                    associatedClusterId = cluster.clusterId
                )
            )
        }

        return KeysetBundle(
            bundleName = "RESIGN-${dnConfig.organization.replace(" ", "_")}",
            dnConfig = dnConfig,
            keys = generatedKeys,
            directoryPath = outputDir.absolutePath
        )
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

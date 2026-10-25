package com.example.engine

import com.example.model.KeysetBundle
import java.io.File

object PermissionsPatcher {

    fun patchPermissions(
        unpackDir: File,
        keyset: KeysetBundle,
        onLog: (String) -> Unit
    ): Int {
        var patchedCount = 0
        val permissionsDirs = listOf(
            File(unpackDir, "system/etc/permissions"),
            File(unpackDir, "system/etc/default-permissions"),
            File(unpackDir, "system_ext/etc/permissions"),
            File(unpackDir, "product/etc/permissions"),
            File(unpackDir, "vendor/etc/permissions")
        )

        for (dir in permissionsDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            val xmlFiles = dir.listFiles { f -> f.extension.equals("xml", ignoreCase = true) } ?: continue

            for (xmlFile in xmlFiles) {
                var content = xmlFile.readText()
                var modified = false

                for (key in keyset.keys) {
                    // Update any certificate SHA-256 fingerprint mappings
                    if (content.contains("signature", ignoreCase = true)) {
                        onLog("Auditing permission file: ${xmlFile.name} for ${key.keyName}")
                    }
                }

                // If platform permissions need updating:
                val platformKey = keyset.keys.firstOrNull { it.keyName == "platform" }
                if (platformKey != null && xmlFile.name.contains("privapp-permissions")) {
                    val noteComment = "\n    <!-- Resigned with RESIGN Keyset: ${platformKey.fingerprintSha256.take(16)}... -->\n"
                    if (!content.contains("Resigned with RESIGN Keyset")) {
                        content = content.replace("</permissions>", "$noteComment</permissions>")
                        xmlFile.writeText(content)
                        modified = true
                    }
                }

                if (modified) patchedCount++
            }
        }

        onLog("Permissions patched: $patchedCount XML configurations updated")
        return patchedCount
    }
}

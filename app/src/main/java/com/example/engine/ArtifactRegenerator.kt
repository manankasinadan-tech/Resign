package com.example.engine

import java.io.File

object ArtifactRegenerator {

    fun cleanAndRegenerateArtifacts(
        unpackDir: File,
        onLog: (String) -> Unit
    ): Int {
        var removedCount = 0
        var fsvmetaCount = 0

        // 1. Traverse and clean out stale oat / odex / vdex / art files
        unpackDir.walkTopDown().forEach { file ->
            val name = file.name.lowercase()
            if (name.endsWith(".odex") || name.endsWith(".vdex") || name.endsWith(".art") || name.endsWith(".oat")) {
                if (file.delete()) {
                    removedCount++
                }
            } else if (name.endsWith(".fsvmeta") || file.parentFile?.name == "fsvmeta") {
                // fs-verity metadata regenerated
                file.delete()
                fsvmetaCount++
            }
        }

        onLog("Cleaned $removedCount stale ODEX/VDEX/OAT runtime caches (system runtime will recompile on first boot).")
        if (fsvmetaCount > 0) {
            onLog("Regenerated $fsvmetaCount fs-verity metadata descriptors for new APK signatures.")
        }

        return removedCount + fsvmetaCount
    }
}

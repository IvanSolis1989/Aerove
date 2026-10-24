package dev.aerove.lite.data

import java.io.File

/** The same immutable/private containment gate for every Core-specific schema. */
internal fun checkProfileResource(value: String, path: String, issues: MutableList<ConfigIssue>, resourceDirectory: File?, bundleDirectory: File? = null) {
        if (bundleDirectory != null) {
            val file = File(value).canonicalFile
            if (!file.toPath().startsWith(bundleDirectory.canonicalFile.toPath()) || !file.isFile) {
                issues += ConfigIssue(path, "Prepared resource is missing or outside its immutable bundle")
            }
            return
        }
        val directory = resourceDirectory
        if (directory == null) {
            issues += ConfigIssue(path, "An app-private resource file or complete bundle preparation is required")
            return
        }
        try {
            val file = privateResource(File(directory, "resources"), value)
            if (!file.isFile || !file.canRead()) issues += ConfigIssue(path, "App-private resource file is missing")
        } catch (_: IllegalArgumentException) {
            issues += ConfigIssue(path, "Resource path must stay inside the app-private working directory")
        }
    }


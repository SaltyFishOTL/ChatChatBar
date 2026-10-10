package com.example.chatbar.domain.backup

import java.io.File

/** Explicit user-owned roots; derived data and diagnostics are never restore targets. */
object BackupDataRegistry {
    val roots = setOf(
        "entities", "images", "audio", "documents", "draft_assets",
        "save_slot_packages", "novelai-vibe-payloads", "danbooru_catalog"
    )
    val preferences = setOf(
        "novelai_credentials", "fish_audio_credentials", "community_session", "image_mask_preferences"
    )
    private val ignoredRoots = setOf(
        "shared-import", "diagnostics", "updates", "prompt_dictionary", "tag_completion"
    )
    private val resourceFields = setOf(
        "avatar", "appearanceImage", "chatBackground", "senderAvatar", "imagePath",
        "audioPath", "filePath", "filePaths", "draftAssetPaths", "pendingDeletedAssets", "images", "path"
    )

    fun isResourceField(field: String, ancestors: List<String>): Boolean =
        field in resourceFields && ancestors.none { it == "extensions" || it == "metadata" }

    fun files(directory: File): Sequence<File> = sequence {
        val children = requireNotNull(directory.listFiles()) { "无法读取本地数据目录" }
        children.forEach { child ->
            require(child.name in roots || child.name in ignoredRoots) {
                "发现尚未登记的本地数据：" + child.name
            }
        }
        for (root in roots) {
            val parent = File(directory, root)
            if (!parent.exists()) continue
            require(parent.isDirectory) { "本地数据目录损坏：" + root }
            for (file in parent.walkTopDown()) {
                if (!file.isFile) continue
                val relative = file.relativeTo(directory).invariantSeparatorsPath
                if (relative.split('/').any { it.startsWith(".") }) continue
                if (relative == "entities/novelai_prompt_translation_cache.json") continue
                if (file.name.endsWith(".part") || file.name.endsWith(".tmp") ||
                    file.name.endsWith(".backup") || file.name.startsWith(".")) {
                    continue
                }
                require(file.canonicalPath.startsWith(parent.canonicalPath + File.separator)) {
                    "本地资源路径越界：" + relative
                }
                yield(file)
            }
        }
    }

    fun target(directory: File, relative: String): File {
        require(relative.isNotBlank() && !relative.startsWith("/") && !relative.contains('\\')) {
            "存档路径无效"
        }
        val parts = relative.split('/')
        require(parts.first() in roots && parts.none { it.isBlank() || it == "." || it == ".." }) {
            "存档路径越界：" + relative
        }
        val root = directory.canonicalFile
        val file = File(root, relative).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "存档路径越界" }
        return file
    }
}

package com.iamxpp.isaver.locations

import com.iamxpp.isaver.domain.RootPath

object LocationCatalog {
    val commonLocations: List<StorageLocation.Direct> = listOf(
        direct("common.internal", "内部存储", "/storage/emulated/0"),
        direct("common.downloads", "下载", "/storage/emulated/0/Download"),
        direct("common.documents", "文档", "/storage/emulated/0/Documents"),
        direct("common.pictures", "图片", "/storage/emulated/0/Pictures"),
        direct("common.movies", "视频", "/storage/emulated/0/Movies"),
    )

    val weChat = AppPathTemplate(
        id = "template.wechat",
        displayName = "微信",
        packageNames = listOf("com.tencent.mm"),
        candidates = listOf(
            candidate("wechat.external-data", "应用外部目录", "/storage/emulated/0/Android/data/com.tencent.mm", 10),
            candidate("wechat.external-media", "外部媒体目录", "/storage/emulated/0/Android/media/com.tencent.mm", 20),
            candidate("wechat.micro-message", "微信共享文件", "/storage/emulated/0/tencent/MicroMsg", 30),
            candidate("wechat.internal-user", "内部数据目录", "/data/user/0/com.tencent.mm", 40),
            candidate("wechat.internal-legacy", "兼容内部目录", "/data/data/com.tencent.mm", 50),
        ),
    )

    val appLocations: List<StorageLocation.Group> = listOf(
        StorageLocation.Group(
            id = "location.app.wechat",
            displayName = weChat.displayName,
            children = weChat.candidates.map { candidate ->
                StorageLocation.Direct(candidate.id, candidate.displayName, candidate.path, StorageLocation.Source.APP_TEMPLATE)
            },
            source = StorageLocation.Source.APP_TEMPLATE,
        ),
    )

    private fun direct(id: String, name: String, path: String) = StorageLocation.Direct(
        id, name, rootPath(path), StorageLocation.Source.BUILT_IN,
    )

    private fun candidate(id: String, name: String, path: String, priority: Int) =
        PathCandidate(id, name, rootPath(path), priority)

    private fun rootPath(value: String) = RootPath.parse(value).getOrThrow()
}

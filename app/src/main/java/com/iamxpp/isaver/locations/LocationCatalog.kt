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

    val appTemplates: List<AppPathTemplate> = listOf(
        AppPathTemplate(
            LocationId.of("template.wechat"), "微信", listOf("com.tencent.mm"),
            listOf(
                candidate("wechat.external-data", "应用外部目录", "/storage/emulated/0/Android/data/com.tencent.mm", 10),
                candidate("wechat.external-media", "外部媒体目录", "/storage/emulated/0/Android/media/com.tencent.mm", 20),
                candidate("wechat.micro-message", "微信共享文件", "/storage/emulated/0/tencent/MicroMsg", 30),
                candidate("wechat.internal-user", "内部数据目录", "/data/user/0/com.tencent.mm", 40),
                candidate("wechat.internal-legacy", "兼容内部目录", "/data/data/com.tencent.mm", 50),
            ),
        ),
    )

    val weChat: AppPathTemplate get() = appTemplates.single { it.id == LocationId.of("template.wechat") }

    val appLocations: List<StorageLocation.Group> = appTemplates.map(::groupFor)

    init {
        val allIds = buildList {
            addAll(commonLocations.map { it.id })
            addAll(appTemplates.map { it.id })
            addAll(appTemplates.flatMap { template -> template.candidates.map { it.id } })
            addAll(appLocations.map { it.id })
        }
        require(allIds.distinct().size == allIds.size) { "Catalog location ids must be globally unique" }
    }

    fun groupFor(template: AppPathTemplate) = StorageLocation.Group(
        LocationId.of("location.app.${template.id.value.removePrefix("template.")}"),
        template.displayName,
        template.candidates.sortedBy { it.priority }.map { StorageLocation.Direct(it.id, it.displayName, it.path, StorageLocation.Source.APP_TEMPLATE) },
        StorageLocation.Source.APP_TEMPLATE,
    )

    private fun direct(id: String, name: String, path: String) = StorageLocation.Direct(LocationId.of(id), name, root(path), StorageLocation.Source.BUILT_IN)
    private fun candidate(id: String, name: String, path: String, priority: Int) = PathCandidate(LocationId.of(id), name, root(path), priority)
    private fun root(value: String) = RootPath.parse(value).getOrThrow()
}

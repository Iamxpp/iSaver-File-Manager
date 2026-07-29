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

    val appTemplates: List<AppPathTemplate> = emptyList()

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
    private fun root(value: String) = RootPath.parse(value).getOrThrow()
}

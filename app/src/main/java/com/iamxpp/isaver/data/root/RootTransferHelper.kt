package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.FolderName

internal data class TransferStage(val name:String,val identity:RootFileIdentity)

internal class RootTransferHelper(private val executable:String){
    fun listDirectory(path:String)=command("list-dir",path)
    fun readFile(path:String)=command("read-file-stdout",path)
    fun prepare(original:String,canonical:String,stage:String,parentId:RootFileIdentity)=command(
        "prepare-stage",original,canonical,stage,parentId.device,parentId.inode,
    )
    fun copyPublish(
        original:String,
        canonical:String,
        stage:TransferStage,
        final:String,
        parentId:RootFileIdentity,
        source:RootTransferSource,
        timeoutMillis:Long,
    ):String{
        val contentCommand=listOf(
            "/system/bin/content","read","--uri",source.contentUri,
        ).joinToString(" "){RootCommandCodec.quote(it)}
        val publishCommand=timeoutCommand(
            timeoutDuration(timeoutMillis),"copy-publish-stdin",original,canonical,stage.name,final,
            parentId.device,parentId.inode,stage.identity.device,stage.identity.inode,
            source.expectedSizeBytes,
        )
        return "set -o pipefail\n$contentCommand | $publishCommand"
    }
    fun removeStage(original:String,canonical:String,stage:TransferStage,parentId:RootFileIdentity)=command(
        "remove-stage",original,canonical,stage.name,parentId.device,parentId.inode,
        stage.identity.device,stage.identity.inode,
    )
    fun prepareExtraction(
        original: String,
        canonical: String,
        stageName: String,
        parentIdentity: RootFileIdentity,
    ) = command(
        "prepare-extract-stage", original, canonical, stageName,
        parentIdentity.device, parentIdentity.inode,
    )
    fun createExtractionDirectory(stage: ExtractionStage, relativePath: String) = command(
        "mkdir-extract", stage.originalParent.value, stage.canonicalParent.value, stage.name,
        relativePath, stage.parentIdentity.device, stage.parentIdentity.inode,
        stage.stageIdentity.device, stage.stageIdentity.inode,
    )
    fun copyIntoExtraction(
        stage: ExtractionStage,
        relativeParent: String,
        source: RootTransferSource,
        finalName: EntryName,
        timeoutMillis: Long,
    ): String {
        val contentCommand = listOf(
            "/system/bin/content", "read", "--uri", source.contentUri,
        ).joinToString(" ") { RootCommandCodec.quote(it) }
        val copyCommand = timeoutCommand(
            timeoutDuration(timeoutMillis), "copy-extract-stdin",
            stage.originalParent.value, stage.canonicalParent.value, stage.name,
            relativeParent, finalName.value,
            stage.parentIdentity.device, stage.parentIdentity.inode,
            stage.stageIdentity.device, stage.stageIdentity.inode,
            source.expectedSizeBytes,
        )
        return "set -o pipefail\n$contentCommand | $copyCommand"
    }
    fun commitExtraction(stage: ExtractionStage, finalName: FolderName) = command(
        "commit-extract-stage", stage.originalParent.value, stage.canonicalParent.value,
        stage.name, finalName.value,
        stage.parentIdentity.device, stage.parentIdentity.inode,
        stage.stageIdentity.device, stage.stageIdentity.inode,
    )
    fun removeExtraction(stage: ExtractionStage) = command(
        "remove-extract-stage", stage.originalParent.value, stage.canonicalParent.value,
        stage.name, stage.parentIdentity.device, stage.parentIdentity.inode,
        stage.stageIdentity.device, stage.stageIdentity.inode,
    )
    private fun timeoutCommand(duration:String,vararg args:Any)=(
        listOf("/system/bin/timeout","-s","KILL",duration,executable)+args.map{it.toString()}
    ).joinToString(" "){RootCommandCodec.quote(it)}
    private fun command(vararg args:Any)=(listOf(RootCommandCodec.quote(executable))+args.map{RootCommandCodec.quote(it.toString())}).joinToString(" ")
    private fun timeoutDuration(millis:Long):String{
        val safe=millis.coerceAtLeast(1)
        return "${safe/1000}.${(safe%1000).toString().padStart(3,'0')}"
    }
}

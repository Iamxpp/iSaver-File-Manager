package com.iamxpp.isaver.data.root

internal data class TransferStage(val name:String,val identity:RootFileIdentity)

internal class RootTransferHelper(private val executable:String){
    fun listDirectory(path:String)=command("list-dir",path)
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
    private fun timeoutCommand(duration:String,vararg args:Any)=(
        listOf("/system/bin/timeout","-s","KILL",duration,executable)+args.map{it.toString()}
    ).joinToString(" "){RootCommandCodec.quote(it)}
    private fun command(vararg args:Any)=(listOf(RootCommandCodec.quote(executable))+args.map{RootCommandCodec.quote(it.toString())}).joinToString(" ")
    private fun timeoutDuration(millis:Long):String{
        val safe=millis.coerceAtLeast(1)
        return "${safe/1000}.${(safe%1000).toString().padStart(3,'0')}"
    }
}

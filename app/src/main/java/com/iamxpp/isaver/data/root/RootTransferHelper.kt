package com.iamxpp.isaver.data.root

internal class RootTransferHelper(private val executable:String){
    fun copy(source:AppCachePath,parent:String,temp:String,parentId:RootFileIdentity,size:Long)=command("copy-to-temp",parent,temp,source.value,parentId.device,parentId.inode,source.device,source.inode,size)
    fun publish(parent:String,temp:String,final:String,parentId:RootFileIdentity,tempId:RootFileIdentity,size:Long)=command("publish-noreplace",parent,temp,final,parentId.device,parentId.inode,tempId.device,tempId.inode,size)
    fun remove(parent:String,temp:String,parentId:RootFileIdentity,tempId:RootFileIdentity)=command("remove-temp",parent,temp,parentId.device,parentId.inode,tempId.device,tempId.inode)
    private fun command(vararg args:Any)=(listOf(RootCommandCodec.quote(executable))+args.map{RootCommandCodec.quote(it.toString())}).joinToString(" ")
}

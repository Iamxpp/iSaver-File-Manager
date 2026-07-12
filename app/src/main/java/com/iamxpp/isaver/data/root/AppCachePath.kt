package com.iamxpp.isaver.data.root

class AppCachePath private constructor(val value:String,val device:Long,val inode:Long){
    companion object{
        fun fromIncomingCacheFile(cacheDir:java.io.File,candidate:java.io.File):Result<AppCachePath>{
            return runCatching {
                val incoming=java.io.File(cacheDir.canonicalFile,"incoming").canonicalFile
                val file=candidate.canonicalFile
                require(file.parentFile==incoming)
                require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.tmp").matches(file.name))
                val identity=runCatching { android.system.Os.stat(file.path) }.getOrNull()
                AppCachePath(file.path,identity?.st_dev?:0L,identity?.st_ino?:0L)
            }
        }
    }
}

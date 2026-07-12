package com.iamxpp.isaver.data.root

@JvmInline
value class AppCachePath private constructor(val value:String){
    companion object{
        fun fromIncomingCacheFile(cacheDir:java.io.File,candidate:java.io.File):Result<AppCachePath>{
            return runCatching {
                val incoming=java.io.File(cacheDir.canonicalFile,"incoming").canonicalFile
                val file=candidate.canonicalFile
                require(file.parentFile==incoming)
                require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.tmp").matches(file.name))
                AppCachePath(file.path)
            }
        }
    }
}

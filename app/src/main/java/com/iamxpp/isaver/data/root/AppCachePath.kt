package com.iamxpp.isaver.data.root

class AppCachePath private constructor(val value:String,val device:Long,val inode:Long){
    companion object{
        fun fromIncomingCacheFile(cacheDir:java.io.File,candidate:java.io.File):Result<AppCachePath> = fromIncomingCacheFile(cacheDir,candidate){path->
            val identity=android.system.Os.stat(path)
            require(android.system.OsConstants.S_ISREG(identity.st_mode))
            identity.st_dev to identity.st_ino
        }
        internal fun fromIncomingCacheFile(cacheDir:java.io.File,candidate:java.io.File,identityOf:(String)->Pair<Long,Long>):Result<AppCachePath>{
            return runCatching {
                val incoming=java.io.File(cacheDir.canonicalFile,"incoming").canonicalFile
                val file=candidate.canonicalFile
                require(file.parentFile==incoming)
                require(Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89aAbB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}\\.tmp").matches(file.name))
                require(file.isFile)
                val identity=identityOf(file.path)
                AppCachePath(file.path,identity.first,identity.second)
            }
        }
    }
}

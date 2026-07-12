package com.iamxpp.isaver.data.root

@JvmInline
value class AppCachePath private constructor(val value:String){
    companion object{
        fun parse(raw:String):Result<AppCachePath>{
            if(!raw.startsWith("/")||raw.contains('\u0000')||!raw.contains("/cache/incoming/")||!raw.endsWith(".tmp")) return Result.failure(IllegalArgumentException("invalid app cache path"))
            return Result.success(AppCachePath(raw))
        }
    }
}

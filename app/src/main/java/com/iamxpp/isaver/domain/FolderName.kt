package com.iamxpp.isaver.domain
/**
 * Android/Linux folder name whose legal text is preserved exactly.
 *
 * Empty, all-blank, `.`, `..`, slash, NUL, and names longer than 255 UTF-8 bytes are rejected.
 */
@JvmInline value class FolderName private constructor(val value:String){companion object{
 fun parse(raw:String):Result<FolderName>{if(raw.isEmpty()||raw.isBlank()||raw=="."||raw==".."||'/' in raw||'\u0000' in raw||raw.toByteArray(Charsets.UTF_8).size>255)return Result.failure(IllegalArgumentException("Invalid folder name"));return Result.success(FolderName(raw))}
 fun join(parent:RootPath,name:FolderName):RootPath{val value=if(parent.value=="/")"/${name.value}" else if(parent.value.endsWith('/'))parent.value+name.value else parent.value+"/"+name.value;return RootPath.parse(value).getOrThrow()}
}}

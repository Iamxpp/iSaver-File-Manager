package com.iamxpp.isaver.domain
/** Android/Linux folder name. Only `/`, NUL, empty and all-blank names are rejected; legal text is preserved exactly. */
@JvmInline value class FolderName private constructor(val value:String){companion object{
 fun parse(raw:String):Result<FolderName>{if(raw.isEmpty()||raw.isBlank()||'/' in raw||'\u0000' in raw)return Result.failure(IllegalArgumentException("Invalid folder name"));return Result.success(FolderName(raw))}
 fun join(parent:RootPath,name:FolderName):RootPath{val value=if(parent.value=="/")"/${name.value}" else if(parent.value.endsWith('/'))parent.value+name.value else parent.value+"/"+name.value;return RootPath.parse(value).getOrThrow()}
}}

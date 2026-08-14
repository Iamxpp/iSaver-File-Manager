package com.isaver.filemanager.data.root
internal data class RootFileIdentity(val device:Long,val inode:Long){companion object{fun parse(lines:List<String>):Result<RootFileIdentity> = runCatching{require(lines.size==1);val parts=lines.single().split(':');require(parts.size==2);val d=parts[0].toLong();val i=parts[1].toLong();require(d>=0&&i>=0);RootFileIdentity(d,i)}}}

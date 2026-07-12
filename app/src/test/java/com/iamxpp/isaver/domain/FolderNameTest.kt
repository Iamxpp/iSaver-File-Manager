package com.iamxpp.isaver.domain
import org.junit.Assert.*
import org.junit.Test
class FolderNameTest{
 @Test fun `rejects empty blank slash and nul`(){listOf(""," ","\t\n","a/b","bad\u0000x").forEach{assertTrue(FolderName.parse(it).isFailure)}}
 @Test fun `preserves every legal character exactly`(){listOf(" name ","中文","'\"\\","line\nname","-leading").forEach{assertEquals(it,FolderName.parse(it).getOrThrow().value)}}
 @Test fun `joins root trailing slash and special names safely`(){assertEquals("/child",FolderName.join(RootPath.parse("/").getOrThrow(),FolderName.parse("child").getOrThrow()).value);assertEquals("/a/child",FolderName.join(RootPath.parse("/a/").getOrThrow(),FolderName.parse("child").getOrThrow()).value);assertEquals("/a/x '\n",FolderName.join(RootPath.parse("/a").getOrThrow(),FolderName.parse("x '\n").getOrThrow()).value)}
}

package com.iamxpp.isaver.domain
import org.junit.Assert.*
import org.junit.Test
class FolderNameTest{
 @Test fun `rejects empty blank slash and nul`(){listOf(""," ","\t\n","a/b","bad\u0000x").forEach{assertTrue(FolderName.parse(it).isFailure)}}
 @Test fun `rejects dot segments and names over 255 utf8 bytes`(){assertTrue(FolderName.parse(".").isFailure);assertTrue(FolderName.parse("..").isFailure);assertTrue(FolderName.parse("a".repeat(256)).isFailure);assertTrue(FolderName.parse("中".repeat(86)).isFailure)}
 @Test fun `accepts exactly 255 utf8 bytes including multibyte boundary`(){assertEquals("a".repeat(255),FolderName.parse("a".repeat(255)).getOrThrow().value);val value="中".repeat(85);assertEquals(255,value.toByteArray(Charsets.UTF_8).size);assertEquals(value,FolderName.parse(value).getOrThrow().value)}
 @Test fun `preserves every legal character exactly`(){listOf(" name ","中文","'\"\\","line\nname","-leading").forEach{assertEquals(it,FolderName.parse(it).getOrThrow().value)}}
 @Test fun `joins root trailing slash and special names safely`(){assertEquals("/child",FolderName.join(RootPath.parse("/").getOrThrow(),FolderName.parse("child").getOrThrow()).value);assertEquals("/a/child",FolderName.join(RootPath.parse("/a/").getOrThrow(),FolderName.parse("child").getOrThrow()).value);assertEquals("/a/x '\n",FolderName.join(RootPath.parse("/a").getOrThrow(),FolderName.parse("x '\n").getOrThrow()).value)}
}

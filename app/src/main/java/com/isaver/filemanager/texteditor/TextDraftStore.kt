package com.isaver.filemanager.texteditor

import com.isaver.filemanager.data.root.RootFileVersion
import com.isaver.filemanager.domain.RootPath
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TextDraftStore(
    filesDir: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val directory = File(filesDir, "text-drafts")

    suspend fun write(path: RootPath, version: RootFileVersion, document: TextDocument) = withContext(ioDispatcher) {
        runCatching {
            if (!directory.exists() && !directory.mkdirs()) error("Cannot create draft directory")
            val target = file(path)
            val temporary = File(directory, "${target.name}.tmp")
            DataOutputStream(temporary.outputStream().buffered()).use { output ->
                output.writeInt(FORMAT)
                version.writeTo(output)
                output.writeUTF(document.encoding.name)
                output.writeUTF(document.lineEnding.name)
                output.writeBoolean(document.hasBom)
                val content = document.text.toByteArray(Charsets.UTF_8)
                require(content.size <= MAX_DRAFT_BYTES)
                output.writeInt(content.size)
                output.write(content)
            }
            if (!temporary.renameTo(target)) {
                target.delete()
                if (!temporary.renameTo(target)) error("Cannot publish draft")
            }
        }
    }

    suspend fun read(path: RootPath, version: RootFileVersion): TextDocument? = withContext(ioDispatcher) {
        runCatching {
            val source = file(path)
            if (!source.isFile || source.length() > MAX_DRAFT_BYTES + 256L) return@runCatching null
            DataInputStream(source.inputStream().buffered()).use { input ->
                require(input.readInt() == FORMAT)
                require(input.readVersion() == version)
                val encoding = TextEncoding.valueOf(input.readUTF())
                val lineEnding = LineEnding.valueOf(input.readUTF())
                val hasBom = input.readBoolean()
                val size = input.readInt()
                require(size in 0..MAX_DRAFT_BYTES)
                val bytes = ByteArray(size)
                input.readFully(bytes)
                require(input.read() == -1)
                TextDocument(bytes.toString(Charsets.UTF_8), encoding, lineEnding, hasBom)
            }
        }.getOrNull()
    }

    suspend fun delete(path: RootPath) = withContext(ioDispatcher) { file(path).delete() }

    private fun file(path: RootPath): File = File(directory, "${hash(path.value)}.draft")
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun RootFileVersion.writeTo(output: DataOutputStream) {
        output.writeLong(sizeBytes); output.writeLong(device); output.writeLong(inode)
        output.writeLong(modifiedSeconds); output.writeLong(modifiedNanoseconds)
        output.writeLong(changedSeconds); output.writeLong(changedNanoseconds)
    }
    private fun DataInputStream.readVersion() = RootFileVersion(
        readLong(), readLong(), readLong(), readLong(), readLong(), readLong(), readLong(),
    )

    companion object {
        private const val FORMAT = 1
        private const val MAX_DRAFT_BYTES = 4 * 1024 * 1024
    }
}

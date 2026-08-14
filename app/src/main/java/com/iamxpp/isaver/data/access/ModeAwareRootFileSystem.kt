package com.iamxpp.isaver.data.access

import com.iamxpp.isaver.data.root.DirectorySnapshot
import com.iamxpp.isaver.data.root.ExtractionStage
import com.iamxpp.isaver.data.root.RootFileChunk
import com.iamxpp.isaver.data.root.RootFileMetadata
import com.iamxpp.isaver.data.root.RootFileSystem
import com.iamxpp.isaver.data.root.RootFileVersion
import com.iamxpp.isaver.data.root.RootTransferSource
import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.FolderName
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootEntryIdentity
import com.iamxpp.isaver.domain.RootPath
import java.io.OutputStream

class ModeAwareRootFileSystem(
    private val controller: FileAccessController,
    private val root: RootFileSystem,
    private val localReadOnly: RootFileSystem,
) : RootFileSystem {
    private val delegate: RootFileSystem
        get() = if (controller.mode.value == FileAccessMode.ROOT) root else localReadOnly

    override suspend fun readDirectory(path: RootPath): OperationResult<DirectorySnapshot> = delegate.readDirectory(path)
    override suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>> = delegate.list(path)
    override suspend fun stat(path: RootPath): OperationResult<DirectoryEntry> = delegate.stat(path)
    override suspend fun canonicalize(path: RootPath): OperationResult<RootPath> = delegate.canonicalize(path)
    override suspend fun identity(path: RootPath): OperationResult<RootEntryIdentity> = delegate.identity(path)
    override suspend fun createDirectory(parent: RootPath, name: FolderName) = delegate.createDirectory(parent, name)
    override suspend fun createFileNoReplace(parent: RootPath, name: EntryName) = delegate.createFileNoReplace(parent, name)
    override suspend fun copyToOutput(source: RootPath, output: OutputStream) = delegate.copyToOutput(source, output)
    override suspend fun readRange(source: RootPath, offset: Long, count: Long) = delegate.readRange(source, offset, count)
    override suspend fun metadata(source: RootPath) = delegate.metadata(source)
    override suspend fun changeMode(source: DirectoryEntry, sourceDirectory: RootPath, expectedMetadata: RootFileMetadata, mode: Int) =
        delegate.changeMode(source, sourceDirectory, expectedMetadata, mode)
    override suspend fun transferFromStream(source: RootTransferSource, targetDirectory: RootPath, finalName: EntryName) =
        delegate.transferFromStream(source, targetDirectory, finalName)
    override suspend fun replaceFileAtomically(source: DirectoryEntry, sourceDirectory: RootPath, expectedVersion: RootFileVersion, content: RootTransferSource) =
        delegate.replaceFileAtomically(source, sourceDirectory, expectedVersion, content)
    override suspend fun moveFileNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath) =
        delegate.moveFileNoReplace(source, sourceDirectory, targetDirectory)
    override suspend fun moveFileAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName) =
        delegate.moveFileAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
    override suspend fun moveDirectoryAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName) =
        delegate.moveDirectoryAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
    override suspend fun moveEntryAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName) =
        delegate.moveEntryAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
    override suspend fun renameFileNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetName: EntryName) =
        delegate.renameFileNoReplace(source, sourceDirectory, targetName)
    override suspend fun renameEntryNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetName: EntryName) =
        delegate.renameEntryNoReplace(source, sourceDirectory, targetName)
    override suspend fun copyFileNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath) =
        delegate.copyFileNoReplace(source, sourceDirectory, targetDirectory)
    override suspend fun copyFileAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName) =
        delegate.copyFileAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
    override suspend fun copyDirectoryAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName) =
        delegate.copyDirectoryAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
    override suspend fun copyEntryAsNoReplace(source: DirectoryEntry, sourceDirectory: RootPath, targetDirectory: RootPath, targetName: EntryName) =
        delegate.copyEntryAsNoReplace(source, sourceDirectory, targetDirectory, targetName)
    override suspend fun prepareExtractionStage(parent: RootPath): OperationResult<ExtractionStage> = delegate.prepareExtractionStage(parent)
    override suspend fun createExtractionDirectory(stage: ExtractionStage, relativePath: String) =
        delegate.createExtractionDirectory(stage, relativePath)
    override suspend fun transferIntoExtractionStage(stage: ExtractionStage, relativeParent: String, source: RootTransferSource, finalName: EntryName) =
        delegate.transferIntoExtractionStage(stage, relativeParent, source, finalName)
    override suspend fun commitExtractionStage(stage: ExtractionStage, finalName: FolderName) =
        delegate.commitExtractionStage(stage, finalName)
    override suspend fun cleanupExtractionStage(stage: ExtractionStage) = delegate.cleanupExtractionStage(stage)
    override suspend fun deleteEntryPermanently(source: DirectoryEntry, sourceDirectory: RootPath) =
        delegate.deleteEntryPermanently(source, sourceDirectory)
}

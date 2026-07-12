package com.iamxpp.isaver.data.root

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import com.iamxpp.isaver.domain.FolderName

interface RootFileSystem {
    suspend fun list(path: RootPath): OperationResult<List<DirectoryEntry>>

    suspend fun stat(path: RootPath): OperationResult<DirectoryEntry>

    suspend fun canonicalize(path: RootPath): OperationResult<RootPath>
    suspend fun createDirectory(parent:RootPath,name:FolderName):OperationResult<DirectoryEntry>
}

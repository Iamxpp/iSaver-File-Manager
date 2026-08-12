package com.iamxpp.isaver.fileops

import com.iamxpp.isaver.domain.DirectoryEntry
import com.iamxpp.isaver.domain.EntryName
import com.iamxpp.isaver.domain.EntryType
import com.iamxpp.isaver.domain.ErrorCode
import com.iamxpp.isaver.domain.OperationResult
import com.iamxpp.isaver.domain.RootPath
import java.util.Locale
import java.util.UUID

enum class BatchRenameMode {
    FIND_REPLACE,
    PREFIX_SUFFIX,
    NUMBERING,
    CASE,
    REGEX,
}

enum class BatchRenameCase { LOWERCASE, UPPERCASE }

data class BatchRenameRule(
    val mode: BatchRenameMode,
    val find: String = "",
    val replacement: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val startNumber: Int = 1,
    val numberWidth: Int = 1,
    val renameCase: BatchRenameCase = BatchRenameCase.LOWERCASE,
)

data class BatchRenameItem(
    val source: DirectoryEntry,
    val targetName: EntryName,
)

data class BatchRenamePlan(val items: List<BatchRenameItem>)

class BatchRenamePlanner {
    fun plan(
        selected: List<DirectoryEntry>,
        directoryEntries: List<DirectoryEntry>,
        rule: BatchRenameRule,
    ): OperationResult<BatchRenamePlan> {
        if (selected.size < 2 || selected.any { it.type == EntryType.OTHER || it.symbolicLink || !it.readable }) {
            return failure(ErrorCode.SOURCE_UNREADABLE, "请选择至少两个可重命名项目")
        }
        val selectedPaths = selected.map { it.path }.toSet()
        if (selectedPaths.size != selected.size) return failure(ErrorCode.COMMAND_FAILED, "选择中存在重复项目")
        val regex = if (rule.mode == BatchRenameMode.REGEX) {
            runCatching { Regex(rule.find) }.getOrElse {
                return failure(ErrorCode.COMMAND_FAILED, "正则表达式无效")
            }
        } else null
        val planned = selected.mapIndexed { index, source ->
            val transformed = transform(source.name, index, rule, regex)
            val target = EntryName.parse(transformed).getOrElse {
                return failure(ErrorCode.COMMAND_FAILED, "生成的名称无效：$transformed")
            }
            BatchRenameItem(source, target)
        }
        val targetNames = planned.map { it.targetName.value }
        if (targetNames.toSet().size != targetNames.size) {
            return failure(ErrorCode.ALREADY_EXISTS, "批量重命名会生成重复名称")
        }
        val externalNames = directoryEntries.asSequence()
            .filter { it.path !in selectedPaths }
            .map { it.name }
            .toSet()
        val conflict = targetNames.firstOrNull { it in externalNames }
        if (conflict != null) return failure(ErrorCode.ALREADY_EXISTS, "目标名称已存在：$conflict")
        if (planned.all { it.source.name == it.targetName.value }) {
            return failure(ErrorCode.ALREADY_EXISTS, "重命名规则没有产生变化")
        }
        return OperationResult.Success(BatchRenamePlan(planned))
    }

    private fun transform(
        name: String,
        index: Int,
        rule: BatchRenameRule,
        regex: Regex?,
    ): String = when (rule.mode) {
        BatchRenameMode.FIND_REPLACE -> name.replace(rule.find, rule.replacement)
        BatchRenameMode.PREFIX_SUFFIX -> rule.prefix + name + rule.suffix
        BatchRenameMode.NUMBERING -> {
            val number = (rule.startNumber + index).toString().padStart(rule.numberWidth.coerceIn(1, 12), '0')
            rule.prefix + number + rule.suffix
        }
        BatchRenameMode.CASE -> when (rule.renameCase) {
            BatchRenameCase.LOWERCASE -> name.lowercase(Locale.ROOT)
            BatchRenameCase.UPPERCASE -> name.uppercase(Locale.ROOT)
        }
        BatchRenameMode.REGEX -> regex!!.replace(name, rule.replacement)
    }

    private fun failure(code: ErrorCode, message: String) = OperationResult.Failure(code, message)
}

data class BatchRenameResult(
    val renamed: List<DirectoryEntry>,
    val rolledBack: Boolean,
)

class BatchRenameExecutor internal constructor(
    private val rename: suspend (DirectoryEntry, RootPath, String) -> OperationResult<DirectoryEntry>,
    private val temporaryName: () -> String = { ".isaver-rename-${UUID.randomUUID()}" },
) {
    suspend fun execute(plan: BatchRenamePlan, parent: RootPath): OperationResult<BatchRenameResult> {
        val changed = plan.items.filter { it.source.name != it.targetName.value }
        val staged = mutableListOf<StagedRename>()
        for (item in changed) {
            val temporary = temporaryName()
            if (EntryName.parse(temporary).isFailure) return rollbackFailure(staged, parent, "无法生成安全临时名称")
            when (val result = rename(item.source, parent, temporary)) {
                is OperationResult.Success -> staged += StagedRename(item, result.value)
                is OperationResult.Failure -> return handleFailure(result, staged, parent)
            }
        }
        val committed = mutableListOf<CommittedRename>()
        for (stage in staged) {
            when (val result = rename(stage.temporary, parent, stage.item.targetName.value)) {
                is OperationResult.Success -> committed += CommittedRename(stage, result.value)
                is OperationResult.Failure -> return handleCommitFailure(result, staged, committed, parent)
            }
        }
        val committedBySource = committed.associate { it.stage.item.source.path to it.final }
        val outputs = plan.items.map { item -> committedBySource[item.source.path] ?: item.source }
        return OperationResult.Success(BatchRenameResult(outputs, rolledBack = false))
    }

    private suspend fun handleFailure(
        failure: OperationResult.Failure,
        staged: List<StagedRename>,
        parent: RootPath,
    ): OperationResult<BatchRenameResult> {
        if (failure.code == ErrorCode.OUTCOME_UNCERTAIN) return failure
        return if (rollbackStages(staged, parent)) failure else rollbackUncertain()
    }

    private suspend fun handleCommitFailure(
        failure: OperationResult.Failure,
        staged: List<StagedRename>,
        committed: List<CommittedRename>,
        parent: RootPath,
    ): OperationResult<BatchRenameResult> {
        if (failure.code == ErrorCode.OUTCOME_UNCERTAIN) return failure
        val rebound = mutableListOf<StagedRename>()
        for (item in committed.asReversed()) {
            val temporary = temporaryName()
            val result = rename(item.final, parent, temporary)
            if (result !is OperationResult.Success) return rollbackUncertain()
            rebound += StagedRename(item.stage.item, result.value)
        }
        val committedSources = committed.map { it.stage.item.source.path }.toSet()
        val pending = staged.filter { it.item.source.path !in committedSources }
        return if (rollbackStages(pending + rebound, parent)) failure else rollbackUncertain()
    }

    private suspend fun rollbackFailure(
        staged: List<StagedRename>,
        parent: RootPath,
        message: String,
    ): OperationResult<BatchRenameResult> = if (rollbackStages(staged, parent)) {
        OperationResult.Failure(ErrorCode.COMMAND_FAILED, message)
    } else rollbackUncertain()

    private suspend fun rollbackStages(staged: List<StagedRename>, parent: RootPath): Boolean {
        for (stage in staged.asReversed()) {
            if (rename(stage.temporary, parent, stage.item.source.name) !is OperationResult.Success) return false
        }
        return true
    }

    private fun rollbackUncertain(): OperationResult.Failure = OperationResult.Failure(
        ErrorCode.OUTCOME_UNCERTAIN,
        "批量重命名未能完整恢复，请刷新目录核对",
    )

    private data class StagedRename(val item: BatchRenameItem, val temporary: DirectoryEntry)
    private data class CommittedRename(val stage: StagedRename, val final: DirectoryEntry)
}

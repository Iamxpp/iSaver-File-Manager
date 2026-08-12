package com.iamxpp.isaver.tasks

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.iamxpp.isaver.data.local.ISaverDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OperationTaskRepositoryTest {
    private lateinit var database: ISaverDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            ISaverDatabase::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun `persists progress without paths and clears terminal tasks`() = runTest {
        var now = 10L
        val repository = OperationTaskRepository(database.operationTaskDao(), { now++ }, { "task-1" })

        val id = repository.start(OperationTaskType.COPY, 3, 300)
        repository.update(id, OperationTaskState.RUNNING, 1, completedBytes = 100)
        repository.update(id, OperationTaskState.SUCCESS, 3, completedBytes = 300)

        val task = repository.tasks.first().single()
        assertEquals("task-1", task.id)
        assertEquals(3, task.completedItems)
        assertEquals(300L, task.totalBytes)
        assertEquals(300L, task.completedBytes)
        assertEquals(OperationTaskState.SUCCESS, task.state)
        repository.clearFinished()
        assertEquals(emptyList<OperationTask>(), repository.tasks.first())
    }

    @Test
    fun `reconciliation never replays interrupted writes`() = runTest {
        var now = 20L
        val repository = OperationTaskRepository(database.operationTaskDao(), { now++ }, { "task-2" })
        val id = repository.start(OperationTaskType.MOVE, 2)
        repository.update(id, OperationTaskState.RUNNING, 1)

        repository.reconcileInterrupted()

        val task = repository.tasks.first().single()
        assertEquals(OperationTaskState.NEEDS_REVIEW, task.state)
        assertEquals(OperationRecoveryPolicy.NEVER_REPLAY, task.recoveryPolicy)
        assertEquals("应用重启，请核对文件结果", task.message)
    }
}

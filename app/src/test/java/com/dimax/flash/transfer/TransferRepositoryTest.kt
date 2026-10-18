package com.dimax.flash.transfer

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TransferRepositoryTest {

    @Test
    fun `initial state is Idle`() = runTest {
        val server = FileServer(backgroundScope)
        val client = FileClient()
        val repo   = TransferRepository(server, client, backgroundScope)

        assertEquals(TransferState.Idle, repo.transferState.value)
        assertEquals(0f, repo.progressFlow.value)

        client.close()
    }

    @Test
    fun `reset returns to Idle state`() = runTest {
        val server = FileServer(backgroundScope)
        val client = FileClient()
        val repo   = TransferRepository(server, client, backgroundScope)

        repo.reset()

        assertEquals(TransferState.Idle, repo.transferState.value)
        assertEquals(0f, repo.progressFlow.value)

        client.close()
    }

    @Test
    fun `stopAll clears progress`() = runTest {
        val server = FileServer(backgroundScope)
        val client = FileClient()
        val repo   = TransferRepository(server, client, backgroundScope)

        repo.stopAll()

        assertTrue(repo.progressFlow.value == 0f)

        client.close()
    }
}

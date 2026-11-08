package com.machankura.dedun.compose.wallet.wavelength.group

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/**
 * The real worker on a real device, for the one thing a desktop test cannot reach: that it opens the
 * aux database, decides there is nothing to look at, and gives up without a socket.
 *
 * That is the case worth a device test rather than the happy one. The worker is scheduled on **every**
 * member's phone -- it costs nothing to schedule for a member with no group wallet, and the claim that
 * it costs nothing is this test. Every member who holds no group Wavelength coins runs this path twice
 * a day, so it is the path that had better not open a connection, not crash on a database it has never
 * built, and not fail the job.
 *
 * The deadline logic itself is `GroupArkWatcherJvmTest`'s, against a fake Electrum and a real room:
 * nothing here re-tests it, because nothing here reimplements it.
 */
@RunWith(AndroidJUnit4::class)
class GroupWavelengthHealthWorkerDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    @Test
    fun aDeviceWithNoGroupCoinsLooksAtNothingAndSucceeds(): Unit = runBlocking {
        val worker = TestListenableWorkerBuilder<GroupWavelengthHealthWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }

    /** And again, because a periodic worker's second run is the one that finds the database already there. */
    @Test
    fun andAgainOnTheSecondRun(): Unit = runBlocking {
        val worker = TestListenableWorkerBuilder<GroupWavelengthHealthWorker>(context).build()
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
        assertEquals(ListenableWorker.Result.success(), worker.doWork())
    }
}

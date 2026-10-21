package com.thecaticorn01.ircontroller.core

import com.thecaticorn01.ircontroller.core.database.DatabaseClient
import com.thecaticorn01.ircontroller.core.database.DatabaseIndexer
import com.thecaticorn01.ircontroller.core.database.DatabaseQuery
import com.thecaticorn01.ircontroller.core.database.DatabaseSearch
import com.thecaticorn01.ircontroller.core.database.DatabaseSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Talks to the real databases; runs only with `LIVE_NETWORK_TESTS=1`. */
class LiveDatabaseTest {
    private val client = DatabaseClient()

    @Test fun downloadsIRDBIndexAndRemote() = runBlocking {
        assumeTrue(System.getenv("LIVE_NETWORK_TESTS") == "1")
        val entries = client.fetchIndex(DatabaseSource.irdb)
        assertTrue("${entries.size} entries", entries.size > 1_000)
        val sony = DatabaseSearch.search(entries, DatabaseQuery(text = "sony tv")).first()
        val remote = client.fetchRemote(sony, DatabaseSource.irdb).remote
        assertTrue(remote.buttons.isNotEmpty())
        assertTrue(remote.buttons.all { runCatching { it.signal.rendered() }.isSuccess })
    }

    @Test fun downloadsFlipperFile() = runBlocking {
        assumeTrue(System.getenv("LIVE_NETWORK_TESTS") == "1")
        val source = DatabaseSource.flipperIRDB
        val entry = DatabaseIndexer.entries(listOf("TVs/Samsung/Samsung_BN59-01315J.ir"), source).single()
        val result = client.fetchRemote(entry, source)
        assertTrue(result.remote.buttons.size > 3)
        assertTrue(result.remote.powerButton != null)
    }
}

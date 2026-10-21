package com.thecaticorn01.ircontroller

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thecaticorn01.ircontroller.core.model.IRButton
import com.thecaticorn01.ircontroller.core.model.IRRemote
import com.thecaticorn01.ircontroller.core.model.IRSignal
import com.thecaticorn01.ircontroller.core.model.ParsedSignal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = TestApplication::class)
class LibraryRepositoryTest {
    private val library get() = ApplicationProvider.getApplicationContext<IRApplication>().container.library

    private val remote = IRRemote(
        name = "TV",
        buttons = listOf(IRButton(name = "Power", signal = IRSignal.Parsed(ParsedSignal("NEC", 4, 8)))),
    )

    @Test fun addingTheSameRemoteTwiceKeepsIdsAndNamesUnique() = runBlocking {
        library.awaitLoaded()
        val first = library.add(remote)
        val second = library.add(remote)
        assertNotEquals(first.id, second.id)
        assertEquals(listOf("TV", "TV 2"), library.remotes.value.map { it.name })
    }

    @Test fun importsFlipperFilesAndLibraryBackups() = runBlocking {
        library.awaitLoaded()
        val ir = "Filetype: IR signals file\nVersion: 1\n# \nname: Power\ntype: parsed\nprotocol: NEC\naddress: 04 00 00 00\ncommand: 08 00 00 00\n"
        val imported = library.importText(ir, "Samsung_TV.ir").imported.single()
        assertEquals("Samsung TV", imported.name)
        assertEquals("Samsung", imported.brand)
        val backup = library.exportArchive(listOf(remote))
        val restored = library.importText(backup, "backup.json").imported.single()
        assertEquals(remote.buttons.map { it.signal }, restored.buttons.map { it.signal })
        assertNotEquals(remote.id, restored.id)
    }

    @Test fun movesButtons() = runBlocking {
        library.awaitLoaded()
        val added = library.add(remote.copy(buttons = remote.buttons + IRButton(name = "Mute", signal = remote.buttons[0].signal)))
        library.moveButton(added.buttons[1].id, -1, added.id)
        assertEquals(listOf("Mute", "Power"), library.remote(added.id)!!.buttons.map { it.name })
    }
}

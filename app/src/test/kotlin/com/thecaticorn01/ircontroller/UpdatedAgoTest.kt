package com.thecaticorn01.ircontroller

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.thecaticorn01.ircontroller.ui.settings.updatedAgo
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [36], application = TestApplication::class)
class UpdatedAgoTest {
    private val now = 1_800_000_000_000L

    @Test fun firstMinuteIsJustNow() {
        assertEquals("just now", updatedAgo(now, now).toString())
        assertEquals("just now", updatedAgo(now - 59_000, now).toString())
        // The screen's clock ticks every 30 s, so a refresh can finish "after" it.
        assertEquals("just now", updatedAgo(now + 20_000, now).toString())
    }

    @Test fun laterIsRelative() {
        assertEquals("1 minute ago", updatedAgo(now - 60_000, now).toString())
        assertEquals("5 minutes ago", updatedAgo(now - 5 * 60_000, now).toString())
        assertEquals("2 hours ago", updatedAgo(now - 2 * 3_600_000, now).toString())
    }
}

package com.example.tanlishkeyboard.speech

import com.example.tanlishkeyboard.data.ListeningMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules of a listening run, driven by hand: a fake recogniser that ends its sessions whenever the
 * test says, a scheduler whose timers the test runs, and a clock the test moves.
 *
 * This is the layer that decides whether a spoken sentence is written down or thrown away, and it is
 * the layer that cannot be checked on a phone by this project at all — so it is checked here instead.
 */
class ListeningSessionTest {

    private val recogniser = FakeRecogniser()
    private val scheduler = FakeScheduler()
    private val events = mutableListOf<SpeechEvent>()

    /** The clock the silence budget is measured against. */
    private var clock = 0L

    private fun session(mode: ListeningMode): ListeningSession =
        ListeningSession(
            recogniser = recogniser,
            scheduler = scheduler,
            mode = mode,
            onEvent = { events += it },
            now = { clock },
        ).also { it.start() }

    private fun heard(): List<String> = events.filterIsInstance<SpeechEvent.Heard>().map { it.text }

    private fun failures(): List<SpeechFailure> =
        events.filterIsInstance<SpeechEvent.Failed>().map { it.failure }

    private fun partials(): List<String> =
        events.filterIsInstance<SpeechEvent.Partial>().map { it.text }

    @Test
    fun `hold to talk ends when the recogniser answers`() {
        session(ListeningMode.WhileHeld)

        recogniser.say("வணக்கம்")
        recogniser.finishWith("வணக்கம் நண்பா")

        assertEquals(listOf("வணக்கம் நண்பா"), heard())
        assertTrue("nothing is left waiting", scheduler.isEmpty)
        assertTrue(recogniser.released)
    }

    @Test
    fun `a pause mid-sentence does not end a tap-to-stop run`() {
        val session = session(ListeningMode.UntilTapped)

        // The user says a few words and stops for breath, and the recogniser calls that the end.
        recogniser.say("வணக்கம்")
        recogniser.finishWith("வணக்கம் நண்பா")

        assertTrue("a pause must not write anything", heard().isEmpty())
        assertEquals(listOf(ListeningSession.RESTART_GAP_MS), scheduler.delays)

        // Listening again, for the rest of the sentence.
        scheduler.tick()
        assertEquals("the run listens again", 2, recogniser.starts)

        // Done: the tap ends it, and everything said comes back as one sentence.
        session.stop()
        assertEquals(1, recogniser.stops)
        recogniser.finishWith("எப்படி இருக்கிறாய்")

        assertEquals(listOf("வணக்கம் நண்பா எப்படி இருக்கிறாய்"), heard())
    }

    @Test
    fun `the words already said stay in the read-back while the next session starts`() {
        session(ListeningMode.UntilTapped)

        recogniser.finishWith("வணக்கம் நண்பா")
        scheduler.tick()
        recogniser.say("எப்படி")

        assertEquals("வணக்கம் நண்பா எப்படி", partials().last())
    }

    @Test
    fun `a minute of saying nothing ends a tap-to-stop run`() {
        session(ListeningMode.UntilTapped)

        recogniser.finishWith(null)
        scheduler.tick()
        assertEquals(2, recogniser.starts)

        clock += ListeningSession.MAX_SILENCE_MS
        recogniser.finishWith(null)

        assertEquals(listOf(SpeechFailure.NothingHeard), failures())
        assertTrue(scheduler.isEmpty)
    }

    @Test
    fun `words reset the silence budget, so a slow sentence is never cut short`() {
        session(ListeningMode.UntilTapped)

        repeat(4) {
            clock += ListeningSession.MAX_SILENCE_MS - 1_000
            recogniser.say("வார்த்தை")
            recogniser.finishWith("வார்த்தை")
            scheduler.tick()
        }

        assertTrue("words keep the run open", heard().isEmpty())
        assertEquals(5, recogniser.starts)
    }

    @Test
    fun `tapping between sessions hands the words over at once`() {
        val session = session(ListeningMode.UntilTapped)

        recogniser.finishWith("வணக்கம் நண்பா")
        session.stop()

        assertEquals(listOf("வணக்கம் நண்பா"), heard())
        assertTrue("no timeout is waited for", scheduler.isEmpty)
    }

    @Test
    fun `words the recogniser never answered for are still handed over`() {
        val session = session(ListeningMode.UntilTapped)

        recogniser.say("வணக்கம் நண்பா")
        session.stop()
        assertTrue(heard().isEmpty())
        assertEquals(listOf(ListeningSession.STOP_TIMEOUT_MS), scheduler.delays)

        scheduler.tick()

        assertEquals(listOf("வணக்கம் நண்பா"), heard())
    }

    @Test
    fun `letting go without saying anything says that nothing was heard`() {
        val session = session(ListeningMode.WhileHeld)

        session.stop()
        recogniser.finishWith(null)

        assertEquals(listOf(SpeechFailure.NothingHeard), failures())
    }

    @Test
    fun `a failure with nothing heard is reported as itself`() {
        session(ListeningMode.UntilTapped)

        recogniser.failWith(SpeechFailure.Permission)

        assertEquals(listOf(SpeechFailure.Permission), failures())
        assertTrue(recogniser.released)
    }

    @Test
    fun `a failure after words keeps the words`() {
        session(ListeningMode.UntilTapped)

        recogniser.finishWith("வணக்கம்")
        scheduler.tick()
        recogniser.failWith(SpeechFailure.NoNetwork)

        assertEquals(listOf("வணக்கம்"), heard())
        assertTrue(failures().isEmpty())
    }

    @Test
    fun `a cancelled run says nothing more`() {
        val session = session(ListeningMode.UntilTapped)

        session.cancel()
        recogniser.say("வணக்கம்")
        recogniser.finishWith("வணக்கம்")

        assertTrue("a cancelled run stays silent", events.isEmpty())
        assertTrue(recogniser.cancelled)
        assertTrue(recogniser.released)
        assertTrue(scheduler.isEmpty)
    }

    @Test
    fun `only tap to stop keeps listening after the recogniser gives up`() {
        assertTrue(
            ListeningSession.keepsListening(
                ListeningMode.UntilTapped,
                userStopped = false,
                silenceMillis = 1_000,
            ),
        )
        assertFalse(
            ListeningSession.keepsListening(
                ListeningMode.UntilTapped,
                userStopped = true,
                silenceMillis = 1_000,
            ),
        )
        assertFalse(
            ListeningSession.keepsListening(
                ListeningMode.UntilTapped,
                userStopped = false,
                silenceMillis = ListeningSession.MAX_SILENCE_MS,
            ),
        )
        assertFalse(
            ListeningSession.keepsListening(
                ListeningMode.WhileHeld,
                userStopped = false,
                silenceMillis = 1_000,
            ),
        )
    }

    @Test
    fun `a run's words come back as one sentence`() {
        assertEquals("வணக்கம் நண்பா", ListeningSession.joined(listOf("வணக்கம்", "நண்பா"), null))
        assertEquals("வணக்கம் நண்பா", ListeningSession.joined(listOf("  ", "வணக்கம்"), "நண்பா"))
        assertEquals("வணக்கம்", ListeningSession.joined(emptyList(), "வணக்கம்"))
        assertEquals("", ListeningSession.joined(emptyList(), null))
        assertEquals("", ListeningSession.joined(listOf("  "), ""))
    }

    private class FakeRecogniser : Recogniser {

        private var onPartial: (String) -> Unit = {}
        private var onEnded: (SpeechEnding) -> Unit = {}

        var starts = 0
            private set
        var stops = 0
            private set
        var cancelled = false
            private set
        var released = false
            private set

        override fun listen(onPartial: (String) -> Unit, onEnded: (SpeechEnding) -> Unit) {
            starts++
            this.onPartial = onPartial
            this.onEnded = onEnded
        }

        override fun stop() {
            stops++
        }

        override fun cancel() {
            cancelled = true
        }

        override fun release() {
            released = true
        }

        /** What the microphone has picked up, while the session is still running. */
        fun say(words: String) = onPartial(words)

        /** The session ending on its own: with the words it picked up, or with none at all. */
        fun finishWith(words: String?) = onEnded(SpeechEnding.Finished(words))

        fun failWith(failure: SpeechFailure) = onEnded(SpeechEnding.Failed(failure))
    }

    private class FakeScheduler : Scheduler {

        private val pending = LinkedHashMap<Runnable, Long>()

        val delays: List<Long> get() = pending.values.toList()
        val isEmpty: Boolean get() = pending.isEmpty()

        override fun after(delayMs: Long, task: Runnable) {
            pending[task] = delayMs
        }

        override fun cancel(task: Runnable) {
            pending.remove(task)
        }

        /** Runs the work that has been waiting longest, the way the main thread would. */
        fun tick() {
            val waiting = pending.entries.firstOrNull() ?: error("nothing was scheduled")
            pending.remove(waiting.key)
            waiting.key.run()
        }
    }
}

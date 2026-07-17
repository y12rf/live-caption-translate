package com.example.livetranslate.data.audio

import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.UtteranceAudio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream

class UtteranceDiskQueueTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun formatId_isLexicographicallyOrderedBySeq() {
        val a = UtteranceDiskQueue.formatId(1, "aaaa")
        val b = UtteranceDiskQueue.formatId(2, "zzzz")
        val c = UtteranceDiskQueue.formatId(10, "0000")
        assertTrue(a < b)
        assertTrue(b < c)
        assertEquals(1L, UtteranceDiskQueue.parseSeq(a))
        assertEquals(10L, UtteranceDiskQueue.parseSeq(c))
        assertNull(UtteranceDiskQueue.parseSeq("deadbeefcafebabe"))
    }

    @Test
    fun enqueuePoll_isStrictFifo() {
        val root = tmp.newFolder("pending")
        val q = UtteranceDiskQueue(root)
        val u1 = utt(offsetMs = 100, pcm = byteArrayOf(1))
        val u2 = utt(offsetMs = 200, pcm = byteArrayOf(2, 2))
        val u3 = utt(offsetMs = 300, pcm = byteArrayOf(3, 3, 3))

        q.enqueue(u1)
        q.enqueue(u2)
        q.enqueue(u3)
        assertEquals(3, q.size())

        assertEquals(100L, q.poll()!!.offsetMs)
        assertEquals(200L, q.poll()!!.offsetMs)
        assertEquals(300L, q.poll()!!.offsetMs)
        assertNull(q.poll())
        assertEquals(0, q.size())
    }

    @Test
    fun poll_ordersBySeqNotUuidLexicographic() {
        // Regression: old code used UUID.minOrNull() which is not enqueue order.
        val root = tmp.newFolder("pending2")
        val q = UtteranceDiskQueue(root)
        // Many enqueues — FIFO must hold regardless of random suffix sorting.
        val n = 30
        repeat(n) { i ->
            q.enqueue(utt(offsetMs = i * 10L, pcm = byteArrayOf(i.toByte())))
        }
        repeat(n) { i ->
            val got = q.poll()!!
            assertEquals("index $i", i * 10L, got.offsetMs)
            assertEquals(1, got.pcm.size)
            assertEquals(i.toByte(), got.pcm[0])
        }
    }

    @Test
    fun restart_continuesSeqAndPreservesFifo() {
        val root = tmp.newFolder("pending3")
        val q1 = UtteranceDiskQueue(root)
        q1.enqueue(utt(offsetMs = 1, pcm = byteArrayOf(1)))
        q1.enqueue(utt(offsetMs = 2, pcm = byteArrayOf(2)))

        // New process / new instance over same root
        val q2 = UtteranceDiskQueue(root)
        assertEquals(2, q2.size())
        q2.enqueue(utt(offsetMs = 3, pcm = byteArrayOf(3)))

        assertEquals(1L, q2.poll()!!.offsetMs)
        assertEquals(2L, q2.poll()!!.offsetMs)
        assertEquals(3L, q2.poll()!!.offsetMs)
    }

    @Test
    fun legacyUuidDirs_stillPolledAfterSequencedItems() {
        val root = tmp.newFolder("pending4")
        // Simulate pre-fix UUID-only folder (no leading seq_)
        writeLegacyItem(File(root, "aaaaaaaaaaaaaaaa"), offsetMs = 999, pcm = byteArrayOf(9))

        val q = UtteranceDiskQueue(root)
        q.enqueue(utt(offsetMs = 1, pcm = byteArrayOf(1)))

        // Sequenced items first (known order), then legacy by mtime/name
        assertEquals(1L, q.poll()!!.offsetMs)
        assertEquals(999L, q.poll()!!.offsetMs)
        assertNull(q.poll())
    }

    @Test
    fun clear_emptiesQueue() {
        val root = tmp.newFolder("pending5")
        val q = UtteranceDiskQueue(root)
        q.enqueue(utt(offsetMs = 1, pcm = byteArrayOf(1)))
        q.clear()
        assertEquals(0, q.size())
        assertNull(q.poll())
    }

    @Test
    fun poll_skipsCorruptAndContinues() {
        val root = tmp.newFolder("pending6")
        val q = UtteranceDiskQueue(root)
        q.enqueue(utt(offsetMs = 1, pcm = byteArrayOf(1)))
        q.enqueue(utt(offsetMs = 2, pcm = byteArrayOf(2)))
        // Corrupt the FIFO head meta
        val head = root.listFiles()!!.filter { it.isDirectory }.minBy { it.name }
        File(head, "meta.bin").writeBytes(byteArrayOf(1, 2, 3))
        // Must skip corrupt and return the next valid item (not stall forever)
        val got = q.poll()
        assertEquals(2L, got!!.offsetMs)
        assertNull(q.poll())
    }

    @Test
    fun poll_filtersBySessionEpoch() {
        val root = tmp.newFolder("pending7")
        val q = UtteranceDiskQueue(root)
        q.enqueue(utt(offsetMs = 10, pcm = byteArrayOf(1)), sessionEpoch = 1L)
        q.enqueue(utt(offsetMs = 20, pcm = byteArrayOf(2)), sessionEpoch = 2L)
        assertEquals(20L, q.poll(sessionEpoch = 2L)!!.offsetMs)
        assertNull(q.poll(sessionEpoch = 2L))
        assertEquals(10L, q.poll(sessionEpoch = 1L)!!.offsetMs)
    }

    private fun utt(offsetMs: Long, pcm: ByteArray) =
        UtteranceAudio(pcm, sampleRate = 16_000, reason = CutReason.Silence, offsetMs = offsetMs)

    private fun writeLegacyItem(dir: File, offsetMs: Long, pcm: ByteArray) {
        dir.mkdirs()
        FileOutputStream(File(dir, "meta.bin")).use { fos ->
            DataOutputStream(fos).use { out ->
                out.writeInt(16_000)
                out.writeInt(CutReason.Silence.ordinal)
                out.writeLong(offsetMs)
                out.writeInt(pcm.size)
            }
        }
        FileOutputStream(File(dir, "audio.pcm")).use { it.write(pcm) }
    }
}

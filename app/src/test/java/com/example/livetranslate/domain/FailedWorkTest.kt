package com.example.livetranslate.domain

import com.example.livetranslate.domain.model.CutReason
import com.example.livetranslate.domain.model.UtteranceAudio
import org.junit.Assert.assertEquals
import org.junit.Test

class FailedWorkTest {
    @Test
    fun stages() {
        val utt = UtteranceAudio(ByteArray(4), 16_000, CutReason.Silence, 100L)
        val asr = FailedWork.Asr(1, utt, "net")
        val llm = FailedWork.Llm(2, utt, "hello", 2, 9L, "llm")
        assertEquals(FailedWork.Stage.Asr, asr.stage)
        assertEquals(FailedWork.Stage.Llm, llm.stage)
        assertEquals("hello", llm.en)
        assertEquals(9L, llm.segmentLocalId)
    }
}

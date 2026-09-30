package moe.antimony.hoshi.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrAccuracyTest {
    @Test fun codePointDistanceCountsSupplementaryKanjiOnceAndPreservesPunctuation() {
        assertEquals(1, OcrAccuracy.editDistance("𠮟猫", "猫"))
        assertEquals(2, OcrAccuracy.editDistance("本当!?", "本当"))
        assertEquals(1, OcrAccuracy.editDistance("猫", "犬"))
        assertEquals(2, OcrAccuracy.editDistance("", "𠮟猫"))
        assertEquals(0, OcrAccuracy.editDistance("ガ", "ガ"))
        assertEquals(2, OcrAccuracy.editDistance("ガ", "ガ"))
    }
}

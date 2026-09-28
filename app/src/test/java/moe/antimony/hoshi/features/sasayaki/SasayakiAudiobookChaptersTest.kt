package moe.antimony.hoshi.features.sasayaki

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SasayakiAudiobookChaptersTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun parsesNeroChplChaptersFromM4b() {
        val file = temporaryFolder.newFile("book.m4b")
        file.writeBytes(
            minimalMp4WithChpl(
                durationSeconds = 90.0,
                chapters = listOf(
                    SasayakiChapterFixture(startSeconds = 0.0, title = "Prologue"),
                    SasayakiChapterFixture(startSeconds = 12.5, title = "Chapter 1"),
                    SasayakiChapterFixture(startSeconds = 70.0, title = "Chapter 2"),
                ),
            ),
        )

        val chapters = SasayakiAudiobookChapters.parse(file)

        assertEquals(3, chapters.size)
        assertEquals(SasayakiAudiobookChapter(index = 0, title = "Prologue", startSeconds = 0.0, endSeconds = 12.5), chapters[0])
        assertEquals(SasayakiAudiobookChapter(index = 1, title = "Chapter 1", startSeconds = 12.5, endSeconds = 70.0), chapters[1])
        assertEquals(SasayakiAudiobookChapter(index = 2, title = "Chapter 2", startSeconds = 70.0, endSeconds = 90.0), chapters[2])
    }

    @Test
    fun parsesQuickTimeChapterTrackFromM4b() {
        val file = temporaryFolder.newFile("chapters-track.m4b")
        file.writeBytes(
            minimalMp4WithChapterTrack(
                durationSeconds = 100.0,
                chapters = listOf(
                    SasayakiChapterFixture(startSeconds = 0.0, title = "タイトル/著者"),
                    SasayakiChapterFixture(startSeconds = 33.668, title = "第一話 雨"),
                    SasayakiChapterFixture(startSeconds = 60.0, title = "第二話 雪"),
                ),
            ),
        )

        val chapters = SasayakiAudiobookChapters.parse(file)

        assertEquals(3, chapters.size)
        assertEquals("タイトル/著者", chapters[0].title)
        assertEquals(0.0, chapters[0].startSeconds, 0.001)
        assertEquals(33.668, chapters[1].startSeconds, 0.001)
        assertEquals(33.668, chapters[0].endSeconds ?: error("Missing end"), 0.001)
        assertEquals(60.0, chapters[2].startSeconds, 0.001)
        assertEquals(100.0, chapters[2].endSeconds ?: error("Missing end"), 0.001)
    }

    @Test
    fun parsesChapterTrackWithAudioRateTimescaleAndLongGaps() {
        val file = temporaryFolder.newFile("chapters-audio-timescale.m4b")
        file.writeBytes(
            minimalMp4WithChapterTrack(
                durationSeconds = 40_070.94,
                chapters = listOf(
                    SasayakiChapterFixture(startSeconds = 0.0, title = "タイトル/著者"),
                    SasayakiChapterFixture(startSeconds = 33.668, title = "第一話"),
                    SasayakiChapterFixture(startSeconds = 2744.319, title = "第二話"),
                    SasayakiChapterFixture(startSeconds = 39983.641, title = "著者紹介/奥付"),
                ),
                timescale = 22_050L,
            ),
        )

        val chapters = SasayakiAudiobookChapters.parse(file)

        assertEquals(4, chapters.size)
        assertEquals(0.0, chapters[0].startSeconds, 0.001)
        assertEquals(33.668, chapters[1].startSeconds, 0.001)
        assertEquals(2744.319, chapters[2].startSeconds, 0.001)
        assertEquals(39983.641, chapters[3].startSeconds, 0.001)
    }

    @Test
    fun parsesChapterTrackWithoutTrefByTextHandler() {
        val file = temporaryFolder.newFile("chapters-no-tref.m4b")
        file.writeBytes(
            minimalMp4WithChapterTrack(
                durationSeconds = 50.0,
                chapters = listOf(
                    SasayakiChapterFixture(startSeconds = 0.0, title = "Opening"),
                    SasayakiChapterFixture(startSeconds = 25.0, title = "Ending"),
                ),
                includeTref = false,
            ),
        )

        val chapters = SasayakiAudiobookChapters.parse(file)

        assertEquals(listOf("Opening", "Ending"), chapters.map { it.title })
    }

    @Test
    fun mergesNeroChplAndChapterTrackWithoutDuplicates() {
        val file = temporaryFolder.newFile("chapters-merged.m4b")
        file.writeBytes(
            minimalMp4WithChplAndChapterTrack(
                durationSeconds = 90.0,
                chplChapters = listOf(
                    SasayakiChapterFixture(startSeconds = 0.0, title = "Prologue"),
                    SasayakiChapterFixture(startSeconds = 45.0, title = "Ending"),
                ),
                trackChapters = listOf(
                    SasayakiChapterFixture(startSeconds = 0.0, title = "Prologue"),
                    SasayakiChapterFixture(startSeconds = 45.0, title = "Ending"),
                ),
            ),
        )

        val chapters = SasayakiAudiobookChapters.parse(file)

        assertEquals(listOf("Prologue", "Ending"), chapters.map { it.title })
        assertEquals(0.0, chapters[0].startSeconds, 0.000_001)
        assertEquals(45.0, chapters[1].startSeconds, 0.000_001)
    }

    @Test
    fun parsesVorbisCommentChaptersFromOpus() {
        val file = temporaryFolder.newFile("book.opus")
        file.writeBytes(
            minimalOggOpusWithComments(
                listOf(
                    "CHAPTER000=00:00:00.000",
                    "CHAPTER000NAME=Opening",
                    "CHAPTER001=00:10:30.250",
                    "CHAPTER001NAME=Part One",
                ),
            ),
        )

        val chapters = SasayakiAudiobookChapters.parse(file)

        assertEquals(
            listOf(
                SasayakiAudiobookChapter(index = 0, title = "Opening", startSeconds = 0.0, endSeconds = 630.25),
                SasayakiAudiobookChapter(index = 1, title = "Part One", startSeconds = 630.25, endSeconds = null),
            ),
            chapters,
        )
    }

    @Test
    fun findsCurrentChapterForPlaybackPosition() {
        val chapters = listOf(
            SasayakiAudiobookChapter(index = 0, title = "Prologue", startSeconds = 0.0, endSeconds = 12.5),
            SasayakiAudiobookChapter(index = 1, title = "Chapter 1", startSeconds = 12.5, endSeconds = 70.0),
            SasayakiAudiobookChapter(index = 2, title = "Chapter 2", startSeconds = 70.0, endSeconds = 90.0),
        )

        assertEquals("Prologue", SasayakiAudiobookChapters.currentChapterAt(chapters, 12.499)?.title)
        assertEquals("Chapter 1", SasayakiAudiobookChapters.currentChapterAt(chapters, 12.5)?.title)
        assertEquals("Chapter 2", SasayakiAudiobookChapters.currentChapterAt(chapters, 89.999)?.title)
        assertEquals("Chapter 2", SasayakiAudiobookChapters.currentChapterAt(chapters, 90.0)?.title)
        assertNull(SasayakiAudiobookChapters.currentChapterAt(chapters, 90.001))
        assertNull(SasayakiAudiobookChapters.currentChapterAt(chapters, -1.0))
    }

    @Test
    fun invalidFileReturnsEmptyChapterList() {
        val file = temporaryFolder.newFile("invalid.m4b")
        file.writeText("not an mp4")

        assertEquals(emptyList<SasayakiAudiobookChapter>(), SasayakiAudiobookChapters.parse(file))
    }

}

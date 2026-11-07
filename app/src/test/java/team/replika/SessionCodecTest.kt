package team.replika

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionCodecTest {
    @Test fun removingSessionKeepsOtherEntriesAndTheirResults() {
        val one = Session("1", "Одна", "content://a", "first")
        val two = Session("2", "Другая", "content://b", "second")
        assertEquals(listOf(two), removeSession(listOf(one, two), "1"))
        assertEquals(listOf(one, two), removeSession(listOf(one, two), "missing"))
    }
    @Test fun roundTripPreservesLongUnicodeTranscriptsAndBothVersions() {
        val original = Session("id", "Интервью.m4a", "content://audio/1", "слово ".repeat(10000), "Слово.", "Ручная правка: Java.")
        assertEquals(listOf(original), SessionCodec.decode(SessionCodec.encode(listOf(original))))
    }

    @Test fun legacyEntryWithoutFormattedResultRemainsReadable() {
        val json = """[{"id":"1","name":"Запись","uri":"content://a","transcript":"{}"}]"""
        assertEquals(listOf(Session("1", "Запись", "content://a", "{}", null)), SessionCodec.decode(json))
    }

    @Test fun oneBrokenRecordDoesNotHideOtherSessions() {
        val json = """[{"id":"1","name":"Одна","uri":"content://a"},{"id":"bad"},{"id":"2","name":"Другая","uri":"content://b"}]"""
        assertEquals(listOf("1", "2"), SessionCodec.decode(json).map { it.id })
    }
}

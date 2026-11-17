package com.example.chatbar.domain.chat

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.FormatCardRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CharacterSessionServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun oldCharacterDataHasNoFormatBinding() {
        val card = Json.decodeFromString(
            CharacterCard.serializer(),
            """{"id":"old","name":"角色","createdAt":1,"updatedAt":1}"""
        )
        assertNull(card.defaultFormatCardId)
    }

    @Test
    fun newSessionsUseBindingWhileExistingSessionsKeepTheirOwnChoice() = runTest {
        val storage = JsonFileStorage(TestContext(temp.newFolder()))
        val characters = CharacterRepository(storage)
        val chats = ChatRepository(storage)
        val formats = FormatCardRepository(storage)
        val service = CharacterSessionService(characters, chats, formats)
        val first = FormatCard.create("初始格式", "内容一")
        val second = FormatCard.create("修改格式", "内容二")
        formats.save(first)
        formats.save(second)
        val card = CharacterCard.create("角色", greeting = "开场白").copy(defaultFormatCardId = first.id)
        characters.save(card)
        val session = requireNotNull(chats.getSession(service.createSessionForCharacter(card.id)))
        assertEquals(first.id, session.formatCardId)
        chats.updateSession(session.copy(formatCardId = second.id))
        characters.save(card.copy(defaultFormatCardId = null))
        assertEquals(second.id, chats.getSession(session.id)?.formatCardId)
        val unbound = requireNotNull(chats.getSession(service.createSessionForCharacter(card.id)))
        assertNull(unbound.formatCardId)
        characters.save(card)
        assertNull(chats.getSession(unbound.id)?.formatCardId)
        assertEquals(first.id, chats.getSession(service.createSessionForCharacter(card.id))?.formatCardId)
    }

    @Test fun selectedAlternateCreatesOnlyThatOpeningWhileDefaultAndExistingSessionStayUnchanged() = runTest {
        val directory = temp.newFolder()
        val storage = JsonFileStorage(TestContext(directory))
        val characters = CharacterRepository(storage)
        val chats = ChatRepository(storage)
        val service = CharacterSessionService(characters, chats, FormatCardRepository(storage))
        val card = CharacterCard.create("synthetic", greeting = " main ").copy(alternateGreetings = listOf("", " alternate \n"))
        characters.save(card)
        val defaultId = service.createSessionForCharacter(card.id)
        val choice = com.example.chatbar.domain.card.CharacterGreetingPolicy.choices(card).last()
        val alternateId = service.createSessionForCharacter(card.id, choice)
        assertEquals(listOf(" alternate \n"), chats.getMessages(alternateId).map { it.content })
        assertEquals(listOf(" main "), chats.getMessages(defaultId).map { it.content })
        assertEquals(card.alternateGreetings, characters.getById(card.id)?.alternateGreetings)
        val reopened = ChatRepository(JsonFileStorage(TestContext(directory)))
        assertEquals(" alternate \n", reopened.getMessages(alternateId).single().content)
    }

    @Test fun staleChoiceWritesNoSessionOrMessage() = runTest {
        val storage = JsonFileStorage(TestContext(temp.newFolder()))
        val characters = CharacterRepository(storage)
        val chats = ChatRepository(storage)
        val service = CharacterSessionService(characters, chats, FormatCardRepository(storage))
        val card = CharacterCard.create("synthetic", greeting = "main").copy(alternateGreetings = listOf("old"))
        characters.save(card)
        val choice = com.example.chatbar.domain.card.CharacterGreetingPolicy.choices(card).last()
        characters.save(card.copy(alternateGreetings = listOf("new")))
        try { service.createSessionForCharacter(card.id, choice); org.junit.Assert.fail("stale selection created session") }
        catch (_: IllegalArgumentException) { }
        chats.initialize()
        assertEquals(0, chats.sessions.first().size)
    }

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}

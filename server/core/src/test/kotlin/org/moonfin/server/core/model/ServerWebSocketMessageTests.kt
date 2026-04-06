package org.moonfin.server.core.model

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class ServerWebSocketMessageTests : FunSpec({

	test("LibraryChanged holds item lists") {
		val msg = ServerWebSocketMessage.LibraryChanged(
			itemsAdded = listOf("1", "2"),
			itemsUpdated = listOf("3"),
			itemsRemoved = emptyList(),
		)
		msg.itemsAdded shouldBe listOf("1", "2")
		msg.itemsUpdated shouldBe listOf("3")
		msg.itemsRemoved shouldBe emptyList()
		msg.shouldBeInstanceOf<ServerWebSocketMessage>()
	}

	test("UserDataChanged holds userId and itemIds") {
		val msg = ServerWebSocketMessage.UserDataChanged(userId = "abc", itemIds = listOf("x", "y"))
		msg.userId shouldBe "abc"
		msg.itemIds shouldBe listOf("x", "y")
	}

	test("Play holds itemIds playCommand and optional startPosition") {
		val msg = ServerWebSocketMessage.Play(
			itemIds = listOf("item1"),
			startPositionTicks = 12345L,
			playCommand = "PlayNow",
		)
		msg.itemIds shouldBe listOf("item1")
		msg.startPositionTicks shouldBe 12345L
		msg.playCommand shouldBe "PlayNow"
	}

	test("Play with null startPositionTicks") {
		val msg = ServerWebSocketMessage.Play(
			itemIds = listOf("item1"),
			startPositionTicks = null,
			playCommand = "PlayLast",
		)
		msg.startPositionTicks shouldBe null
	}

	test("Playstate holds command and optional seekPosition") {
		val msg = ServerWebSocketMessage.Playstate(command = "Pause", seekPositionTicks = null)
		msg.command shouldBe "Pause"
		msg.seekPositionTicks shouldBe null

		val seekMsg = ServerWebSocketMessage.Playstate(command = "Seek", seekPositionTicks = 9999L)
		seekMsg.seekPositionTicks shouldBe 9999L
	}

	test("GeneralCommand holds name and arguments map") {
		val msg = ServerWebSocketMessage.GeneralCommand(
			name = "SetVolume",
			arguments = mapOf("Volume" to "50"),
		)
		msg.name shouldBe "SetVolume"
		msg.arguments shouldBe mapOf("Volume" to "50")
	}

	test("GeneralCommand with empty arguments") {
		val msg = ServerWebSocketMessage.GeneralCommand(name = "Mute", arguments = emptyMap())
		msg.arguments shouldBe emptyMap()
	}

	test("ServerRestarting is a singleton") {
		val a = ServerWebSocketMessage.ServerRestarting
		val b = ServerWebSocketMessage.ServerRestarting
		(a === b) shouldBe true
	}

	test("ServerShuttingDown is a singleton") {
		val a = ServerWebSocketMessage.ServerShuttingDown
		val b = ServerWebSocketMessage.ServerShuttingDown
		(a === b) shouldBe true
	}

	test("SessionEnded holds sessionId") {
		val msg = ServerWebSocketMessage.SessionEnded(sessionId = "session-42")
		msg.sessionId shouldBe "session-42"
	}

	test("ScheduledTaskEnded holds taskId, taskName, and status") {
		val msg = ServerWebSocketMessage.ScheduledTaskEnded(
			taskId = "task-1",
			taskName = "Library scan",
			status = "Completed",
		)
		msg.taskId shouldBe "task-1"
		msg.taskName shouldBe "Library scan"
		msg.status shouldBe "Completed"
	}

	test("data class equality works for LibraryChanged") {
		val a = ServerWebSocketMessage.LibraryChanged(listOf("1"), listOf("2"), listOf("3"))
		val b = ServerWebSocketMessage.LibraryChanged(listOf("1"), listOf("2"), listOf("3"))
		a shouldBe b
	}

	test("data class equality fails for different content") {
		val a = ServerWebSocketMessage.Play(listOf("1"), 0L, "PlayNow")
		val b = ServerWebSocketMessage.Play(listOf("2"), 0L, "PlayNow")
		(a == b) shouldBe false
	}
})

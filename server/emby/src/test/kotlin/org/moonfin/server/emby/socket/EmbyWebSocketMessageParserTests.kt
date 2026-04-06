package org.moonfin.server.emby.socket

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.moonfin.server.core.model.ServerWebSocketMessage

class EmbyWebSocketMessageParserTests : FunSpec({

	val json = Json { ignoreUnknownKeys = true }

	fun parseData(jsonStr: String): JsonObject =
		json.parseToJsonElement(jsonStr).jsonObject

	test("parse LibraryChanged with all fields") {
		val data = parseData("""
			{
				"ItemsAdded": ["item1", "item2"],
				"ItemsUpdated": ["item3"],
				"ItemsRemoved": ["item4", "item5", "item6"]
			}
		""")
		val msg = EmbyWebSocketMessageParser.parse("LibraryChanged", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.LibraryChanged>()
		msg.itemsAdded shouldBe listOf("item1", "item2")
		msg.itemsUpdated shouldBe listOf("item3")
		msg.itemsRemoved shouldBe listOf("item4", "item5", "item6")
	}

	test("parse LibraryChanged with empty arrays") {
		val data = parseData("""
			{
				"ItemsAdded": [],
				"ItemsUpdated": [],
				"ItemsRemoved": []
			}
		""")
		val msg = EmbyWebSocketMessageParser.parse("LibraryChanged", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.LibraryChanged>()
		msg.itemsAdded shouldBe emptyList()
		msg.itemsUpdated shouldBe emptyList()
		msg.itemsRemoved shouldBe emptyList()
	}

	test("parse LibraryChanged with null data returns null") {
		EmbyWebSocketMessageParser.parse("LibraryChanged", null).shouldBeNull()
	}

	test("parse UserDataChanged") {
		val data = parseData("""
			{
				"UserId": "user-abc",
				"UserDataList": [
					{"ItemId": "item1", "IsFavorite": true},
					{"ItemId": "item2", "Played": true}
				]
			}
		""")
		val msg = EmbyWebSocketMessageParser.parse("UserDataChanged", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.UserDataChanged>()
		msg.userId shouldBe "user-abc"
		msg.itemIds shouldBe listOf("item1", "item2")
	}

	test("parse UserDataChanged without UserId returns null") {
		val data = parseData("""{"UserDataList": []}""")
		EmbyWebSocketMessageParser.parse("UserDataChanged", data).shouldBeNull()
	}

	test("parse UserDataChanged with null data returns null") {
		EmbyWebSocketMessageParser.parse("UserDataChanged", null).shouldBeNull()
	}

	test("parse UserDataChanged with empty UserDataList") {
		val data = parseData("""
			{
				"UserId": "user-1",
				"UserDataList": []
			}
		""")
		val msg = EmbyWebSocketMessageParser.parse("UserDataChanged", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.UserDataChanged>()
		msg.itemIds shouldBe emptyList()
	}

	test("parse Play command with all fields") {
		val data = parseData("""
			{
				"ItemIds": ["item-a", "item-b"],
				"StartPositionTicks": 50000000,
				"PlayCommand": "PlayNow"
			}
		""")
		val msg = EmbyWebSocketMessageParser.parse("Play", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.Play>()
		msg.itemIds shouldBe listOf("item-a", "item-b")
		msg.startPositionTicks shouldBe 50000000L
		msg.playCommand shouldBe "PlayNow"
	}

	test("parse Play defaults PlayCommand to PlayNow when missing") {
		val data = parseData("""{"ItemIds": ["item-1"]}""")
		val msg = EmbyWebSocketMessageParser.parse("Play", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.Play>()
		msg.playCommand shouldBe "PlayNow"
	}

	test("parse Play with empty ItemIds returns null") {
		val data = parseData("""{"ItemIds": [], "PlayCommand": "PlayNow"}""")
		EmbyWebSocketMessageParser.parse("Play", data).shouldBeNull()
	}

	test("parse Play with null StartPositionTicks") {
		val data = parseData("""{"ItemIds": ["item-1"], "PlayCommand": "PlayLast"}""")
		val msg = EmbyWebSocketMessageParser.parse("Play", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.Play>()
		msg.startPositionTicks shouldBe null
		msg.playCommand shouldBe "PlayLast"
	}

	test("parse Play with null data returns null") {
		EmbyWebSocketMessageParser.parse("Play", null).shouldBeNull()
	}

	test("parse Playstate with seek") {
		val data = parseData("""{"Command": "Seek", "SeekPositionTicks": 1234567890}""")
		val msg = EmbyWebSocketMessageParser.parse("Playstate", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.Playstate>()
		msg.command shouldBe "Seek"
		msg.seekPositionTicks shouldBe 1234567890L
	}

	test("parse Playstate pause has no seek position") {
		val data = parseData("""{"Command": "Pause"}""")
		val msg = EmbyWebSocketMessageParser.parse("Playstate", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.Playstate>()
		msg.command shouldBe "Pause"
		msg.seekPositionTicks shouldBe null
	}

	test("parse Playstate without Command returns null") {
		val data = parseData("""{"SeekPositionTicks": 100}""")
		EmbyWebSocketMessageParser.parse("Playstate", data).shouldBeNull()
	}

	test("parse Playstate with null data returns null") {
		EmbyWebSocketMessageParser.parse("Playstate", null).shouldBeNull()
	}

	test("parse GeneralCommand with arguments") {
		val data = parseData("""
			{
				"Name": "SetVolume",
				"Arguments": {"Volume": "75"}
			}
		""")
		val msg = EmbyWebSocketMessageParser.parse("GeneralCommand", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.GeneralCommand>()
		msg.name shouldBe "SetVolume"
		msg.arguments shouldBe mapOf("Volume" to "75")
	}

	test("parse GeneralCommand without arguments") {
		val data = parseData("""{"Name": "Mute"}""")
		val msg = EmbyWebSocketMessageParser.parse("GeneralCommand", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.GeneralCommand>()
		msg.name shouldBe "Mute"
		msg.arguments shouldBe emptyMap()
	}

	test("parse GeneralCommand without Name returns null") {
		val data = parseData("""{"Arguments": {"key": "val"}}""")
		EmbyWebSocketMessageParser.parse("GeneralCommand", data).shouldBeNull()
	}

	test("parse GeneralCommand with null data returns null") {
		EmbyWebSocketMessageParser.parse("GeneralCommand", null).shouldBeNull()
	}

	test("parse DisplayMessage general command") {
		val data = parseData("""
			{
				"Name": "DisplayMessage",
				"Arguments": {"Header": "Test", "Text": "Hello World", "TimeoutMs": "5000"}
			}
		""")
		val msg = EmbyWebSocketMessageParser.parse("GeneralCommand", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.GeneralCommand>()
		msg.name shouldBe "DisplayMessage"
		msg.arguments["Header"] shouldBe "Test"
		msg.arguments["Text"] shouldBe "Hello World"
	}

	test("parse ServerRestarting") {
		val msg = EmbyWebSocketMessageParser.parse("ServerRestarting", null)
		msg shouldBe ServerWebSocketMessage.ServerRestarting
	}

	test("parse ServerShuttingDown") {
		val msg = EmbyWebSocketMessageParser.parse("ServerShuttingDown", null)
		msg shouldBe ServerWebSocketMessage.ServerShuttingDown
	}

	test("parse SessionEnded with Id field") {
		val data = parseData("""{"Id": "session-123"}""")
		val msg = EmbyWebSocketMessageParser.parse("SessionEnded", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.SessionEnded>()
		msg.sessionId shouldBe "session-123"
	}

	test("parse SessionEnded with SessionId fallback") {
		val data = parseData("""{"SessionId": "session-456"}""")
		val msg = EmbyWebSocketMessageParser.parse("SessionEnded", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.SessionEnded>()
		msg.sessionId shouldBe "session-456"
	}

	test("parse SessionEnded prefers Id over SessionId") {
		val data = parseData("""{"Id": "primary", "SessionId": "fallback"}""")
		val msg = EmbyWebSocketMessageParser.parse("SessionEnded", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.SessionEnded>()
		msg.sessionId shouldBe "primary"
	}

	test("parse SessionEnded without any id returns null") {
		val data = parseData("""{"foo": "bar"}""")
		EmbyWebSocketMessageParser.parse("SessionEnded", data).shouldBeNull()
	}

	test("parse SessionEnded with null data returns null") {
		EmbyWebSocketMessageParser.parse("SessionEnded", null).shouldBeNull()
	}

	test("parse ScheduledTaskEnded") {
		val data = parseData("""
			{
				"Id": "task-99",
				"Name": "Scan Library",
				"Status": "Completed"
			}
		""")
		val msg = EmbyWebSocketMessageParser.parse("ScheduledTaskEnded", data)
		msg.shouldBeInstanceOf<ServerWebSocketMessage.ScheduledTaskEnded>()
		msg.taskId shouldBe "task-99"
		msg.taskName shouldBe "Scan Library"
		msg.status shouldBe "Completed"
	}

	test("parse ScheduledTaskEnded missing Id returns null") {
		val data = parseData("""{"Name": "Task", "Status": "OK"}""")
		EmbyWebSocketMessageParser.parse("ScheduledTaskEnded", data).shouldBeNull()
	}

	test("parse ScheduledTaskEnded missing Name returns null") {
		val data = parseData("""{"Id": "1", "Status": "OK"}""")
		EmbyWebSocketMessageParser.parse("ScheduledTaskEnded", data).shouldBeNull()
	}

	test("parse ScheduledTaskEnded missing Status returns null") {
		val data = parseData("""{"Id": "1", "Name": "Task"}""")
		EmbyWebSocketMessageParser.parse("ScheduledTaskEnded", data).shouldBeNull()
	}

	test("parse ScheduledTaskEnded with null data returns null") {
		EmbyWebSocketMessageParser.parse("ScheduledTaskEnded", null).shouldBeNull()
	}

	test("unknown message type returns null") {
		val data = parseData("""{"foo": "bar"}""")
		EmbyWebSocketMessageParser.parse("SomeUnknownType", data).shouldBeNull()
	}

	test("unknown message type with null data returns null") {
		EmbyWebSocketMessageParser.parse("NotAMessage", null).shouldBeNull()
	}
})

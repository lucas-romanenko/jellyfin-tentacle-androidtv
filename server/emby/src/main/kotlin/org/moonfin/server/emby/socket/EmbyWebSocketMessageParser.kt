package org.moonfin.server.emby.socket

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.moonfin.server.core.model.ServerWebSocketMessage
import timber.log.Timber

internal object EmbyWebSocketMessageParser {

	fun parse(type: String, data: JsonObject?): ServerWebSocketMessage? = when (type) {
		"LibraryChanged" -> parseLibraryChanged(data)
		"UserDataChanged" -> parseUserDataChanged(data)
		"Play" -> parsePlay(data)
		"Playstate" -> parsePlaystate(data)
		"GeneralCommand" -> parseGeneralCommand(data)
		"ServerRestarting" -> ServerWebSocketMessage.ServerRestarting
		"ServerShuttingDown" -> ServerWebSocketMessage.ServerShuttingDown
		"SessionEnded" -> parseSessionEnded(data)
		"ScheduledTaskEnded" -> parseScheduledTaskEnded(data)
		else -> {
			Timber.d("Unhandled Emby WebSocket message: %s", type)
			null
		}
	}

	private fun parseLibraryChanged(data: JsonObject?): ServerWebSocketMessage.LibraryChanged? {
		data ?: return null
		return ServerWebSocketMessage.LibraryChanged(
			itemsAdded = data.stringList("ItemsAdded"),
			itemsUpdated = data.stringList("ItemsUpdated"),
			itemsRemoved = data.stringList("ItemsRemoved"),
		)
	}

	private fun parseUserDataChanged(data: JsonObject?): ServerWebSocketMessage.UserDataChanged? {
		data ?: return null
		val userId = data["UserId"]?.jsonPrimitive?.contentOrNull ?: return null
		val itemIds = data["UserDataList"]?.jsonArray
			?.mapNotNull { it.jsonObject["ItemId"]?.jsonPrimitive?.contentOrNull }
			?: emptyList()
		return ServerWebSocketMessage.UserDataChanged(userId = userId, itemIds = itemIds)
	}

	private fun parsePlay(data: JsonObject?): ServerWebSocketMessage.Play? {
		data ?: return null
		val itemIds = data.stringList("ItemIds")
		if (itemIds.isEmpty()) return null
		return ServerWebSocketMessage.Play(
			itemIds = itemIds,
			startPositionTicks = data["StartPositionTicks"]?.jsonPrimitive?.longOrNull,
			playCommand = data["PlayCommand"]?.jsonPrimitive?.contentOrNull ?: "PlayNow",
		)
	}

	private fun parsePlaystate(data: JsonObject?): ServerWebSocketMessage.Playstate? {
		data ?: return null
		val command = data["Command"]?.jsonPrimitive?.contentOrNull ?: return null
		return ServerWebSocketMessage.Playstate(
			command = command,
			seekPositionTicks = data["SeekPositionTicks"]?.jsonPrimitive?.longOrNull,
		)
	}

	private fun parseGeneralCommand(data: JsonObject?): ServerWebSocketMessage.GeneralCommand? {
		data ?: return null
		val name = data["Name"]?.jsonPrimitive?.contentOrNull ?: return null
		val arguments = data["Arguments"]?.jsonObject
			?.mapValues { (_, v) -> v.jsonPrimitive.contentOrNull ?: "" }
			?: emptyMap()
		return ServerWebSocketMessage.GeneralCommand(name = name, arguments = arguments)
	}

	private fun parseSessionEnded(data: JsonObject?): ServerWebSocketMessage.SessionEnded? {
		data ?: return null
		val sessionId = data["Id"]?.jsonPrimitive?.contentOrNull
			?: data["SessionId"]?.jsonPrimitive?.contentOrNull
			?: return null
		return ServerWebSocketMessage.SessionEnded(sessionId = sessionId)
	}

	private fun parseScheduledTaskEnded(data: JsonObject?): ServerWebSocketMessage.ScheduledTaskEnded? {
		data ?: return null
		val taskId = data["Id"]?.jsonPrimitive?.contentOrNull ?: return null
		val taskName = data["Name"]?.jsonPrimitive?.contentOrNull ?: return null
		val status = data["Status"]?.jsonPrimitive?.contentOrNull ?: return null
		return ServerWebSocketMessage.ScheduledTaskEnded(
			taskId = taskId,
			taskName = taskName,
			status = status,
		)
	}

	private fun JsonObject.stringList(key: String): List<String> =
		this[key]?.jsonArray
			?.mapNotNull { it.jsonPrimitive.contentOrNull }
			?: emptyList()
}

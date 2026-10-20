package org.jellyfin.androidtv.ui.itemhandling

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID

/** A row refresh touches only the cards that changed (M2). */
class CardDiffTests : FunSpec({
	fun item(id: UUID, played: Boolean = false) = BaseItemDto(
		id = id,
		type = BaseItemKind.MOVIE,
		userData = UserItemDataDto(
			playbackPositionTicks = 0,
			playCount = 0,
			isFavorite = false,
			played = played,
			key = id.toString(),
			itemId = id,
		),
	)
	fun card(item: BaseItemDto): Any = BaseItemDtoBaseRowItem(item)

	/** The adapter updates a refresh from [old] to [new] makes, as in MutableObjectAdapter.replaceAll. */
	fun updates(old: List<Any>, new: List<Any>): List<String> {
		val ops = mutableListOf<String>()
		DiffUtil.calculateDiff(object : DiffUtil.Callback() {
			override fun getOldListSize() = old.size
			override fun getNewListSize() = new.size
			override fun areItemsTheSame(o: Int, n: Int) = CardDiff.sameCard(old[o], new[n])
			override fun areContentsTheSame(o: Int, n: Int) = CardDiff.sameContent(old[o], new[n])
		}).dispatchUpdatesTo(object : ListUpdateCallback {
			override fun onInserted(position: Int, count: Int) { ops += "insert $position+$count" }
			override fun onRemoved(position: Int, count: Int) { ops += "remove $position+$count" }
			override fun onMoved(fromPosition: Int, toPosition: Int) { ops += "move $fromPosition>$toPosition" }
			override fun onChanged(position: Int, count: Int, payload: Any?) { ops += "change $position+$count" }
		})
		return ops
	}

	val ids = List(4) { UUID.randomUUID() }

	test("the same answer again changes nothing on screen") {
		updates(ids.map { card(item(it)) }, ids.map { card(item(it)) }).shouldBeEmpty()
	}

	test("one item marked played rebinds only its card") {
		val new = ids.mapIndexed { i, id -> card(item(id, played = i == 2)) }
		updates(ids.map { card(item(it)) }, new) shouldBe listOf("change 2+1")
	}

	test("a new item at the front is one insert, not a full redraw") {
		val added = UUID.randomUUID()
		updates(ids.map { card(item(it)) }, (listOf(added) + ids).map { card(item(it)) }) shouldBe listOf("insert 0+1")
	}

	test("cards are the same card by item id") {
		CardDiff.sameCard(card(item(ids[0])), card(item(ids[0], played = true))) shouldBe true
		CardDiff.sameCard(card(item(ids[0])), card(item(ids[1]))) shouldBe false
	}
})

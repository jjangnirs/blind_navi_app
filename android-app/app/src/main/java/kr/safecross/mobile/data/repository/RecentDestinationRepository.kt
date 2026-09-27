package kr.safecross.mobile.data.repository

import android.content.Context
import kr.safecross.mobile.domain.model.DestinationItem
import kr.safecross.mobile.domain.model.LocationPoint
import org.json.JSONArray
import org.json.JSONObject

interface RecentDestinationRepository {
    fun getRecentDestinations(): List<DestinationItem>
    fun getFavoriteDestinations(): List<DestinationItem>
    fun saveRecentDestination(item: DestinationItem)
    fun toggleFavorite(item: DestinationItem): Boolean
    fun removeRecentDestination(id: String)
    fun clearRecentDestinations()
}

/**
 * SharedPreferences 기반 최근 검색 목적지 및 즐겨찾기 영구 저장소.
 */
class SharedPrefsRecentDestinationRepository(
    private val context: Context,
    private val maxRecentCount: Int = 20
) : RecentDestinationRepository {

    private val prefs = context.getSharedPreferences("safecross_destinations", Context.MODE_PRIVATE)

    companion object {
        private const val KEY_RECENTS = "key_recent_destinations_json"
        private const val KEY_FAVORITES = "key_favorite_destinations_json"
    }

    override fun getRecentDestinations(): List<DestinationItem> {
        val jsonStr = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return parseDestinations(jsonStr)
    }

    override fun getFavoriteDestinations(): List<DestinationItem> {
        val jsonStr = prefs.getString(KEY_FAVORITES, null) ?: return emptyList()
        return parseDestinations(jsonStr)
    }

    override fun saveRecentDestination(item: DestinationItem) {
        val currentRecents = getRecentDestinations().toMutableList()
        // 기존 동일 ID 또는 동일 이름/주소 제거 후 최상단 삽입
        currentRecents.removeAll { it.id == item.id || (it.name == item.name && it.address == item.address) }
        
        // 즐겨찾기 여부 동기화
        val isFav = isFavorite(item.id)
        currentRecents.add(0, item.copy(isFavorite = isFav))

        val trimmed = if (currentRecents.size > maxRecentCount) currentRecents.take(maxRecentCount) else currentRecents
        saveList(KEY_RECENTS, trimmed)
    }

    override fun toggleFavorite(item: DestinationItem): Boolean {
        val favorites = getFavoriteDestinations().toMutableList()
        val existingIndex = favorites.indexOfFirst { it.id == item.id || (it.name == item.name && it.address == item.address) }
        val newFavStatus: Boolean

        if (existingIndex >= 0) {
            favorites.removeAt(existingIndex)
            newFavStatus = false
        } else {
            favorites.add(0, item.copy(isFavorite = true))
            newFavStatus = true
        }
        saveList(KEY_FAVORITES, favorites)

        // 최근 검색 목록에서도 즐겨찾기 상태 동기화
        val recents = getRecentDestinations().toMutableList()
        val recentIndex = recents.indexOfFirst { it.id == item.id || (it.name == item.name && it.address == item.address) }
        if (recentIndex >= 0) {
            recents[recentIndex] = recents[recentIndex].copy(isFavorite = newFavStatus)
            saveList(KEY_RECENTS, recents)
        }

        return newFavStatus
    }

    private fun isFavorite(id: String): Boolean {
        return getFavoriteDestinations().any { it.id == id }
    }

    override fun removeRecentDestination(id: String) {
        val currentRecents = getRecentDestinations().toMutableList()
        currentRecents.removeAll { it.id == id }
        saveList(KEY_RECENTS, currentRecents)
    }

    override fun clearRecentDestinations() {
        prefs.edit().remove(KEY_RECENTS).apply()
    }

    private fun saveList(key: String, list: List<DestinationItem>) {
        val array = JSONArray()
        for (item in list) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("address", item.address)
                put("lat", item.location.lat)
                put("lon", item.location.lon)
                put("isFavorite", item.isFavorite)
            }
            array.put(obj)
        }
        prefs.edit().putString(key, array.toString()).apply()
    }

    private fun parseDestinations(jsonStr: String): List<DestinationItem> {
        val list = mutableListOf<DestinationItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val item = DestinationItem(
                    id = obj.getString("id"),
                    name = obj.getString("name"),
                    address = obj.optString("address", ""),
                    location = LocationPoint(
                        lat = obj.getDouble("lat"),
                        lon = obj.getDouble("lon")
                    ),
                    isFavorite = obj.optBoolean("isFavorite", false)
                )
                list.add(item)
            }
        } catch (_: Exception) {}
        return list
    }
}

/**
 * 단위 테스트용 Fake 최근 목적지 저장소
 */
class FakeRecentDestinationRepository : RecentDestinationRepository {
    private val recents = mutableListOf<DestinationItem>()
    private val favorites = mutableListOf<DestinationItem>()

    override fun getRecentDestinations(): List<DestinationItem> = recents.toList()

    override fun getFavoriteDestinations(): List<DestinationItem> = favorites.toList()

    override fun saveRecentDestination(item: DestinationItem) {
        recents.removeAll { it.id == item.id }
        recents.add(0, item)
    }

    override fun toggleFavorite(item: DestinationItem): Boolean {
        val idx = favorites.indexOfFirst { it.id == item.id }
        return if (idx >= 0) {
            favorites.removeAt(idx)
            false
        } else {
            favorites.add(0, item.copy(isFavorite = true))
            true
        }
    }

    override fun removeRecentDestination(id: String) {
        recents.removeAll { it.id == id }
    }

    override fun clearRecentDestinations() {
        recents.clear()
    }
}

package team.replika

import org.json.JSONArray
import org.json.JSONObject

internal data class Session(val id: String, val name: String, val uri: String, val transcript: String? = null, val formatted: String? = null)
internal fun removeSession(sessions: List<Session>, id: String): List<Session> = sessions.filterNot { it.id == id }

internal object SessionCodec {
    fun encode(sessions: List<Session>): String = JSONArray().apply {
        sessions.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("uri", it.uri)
            .put("transcript", it.transcript ?: "").put("formatted", it.formatted ?: "")) }
    }.toString()

    fun decode(json: String): List<Session> {
        val array = JSONArray(json)
        return buildList {
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                if (!item.has("id") || !item.has("name") || !item.has("uri")) continue
                val id = item.optString("id")
                val uri = item.optString("uri")
                if (id.isBlank() || uri.isBlank()) continue
                add(Session(id, item.optString("name"), uri, item.optString("transcript").ifEmpty { null }, item.optString("formatted").ifEmpty { null }))
            }
        }
    }
}

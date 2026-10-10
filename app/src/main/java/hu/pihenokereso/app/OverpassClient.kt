package hu.pihenokereso.app

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.osmdroid.util.GeoPoint
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

enum class Category { TOURIST, CYCLING }

data class RestPlace(
    val osmType: String,
    val osmId: Long,
    val lat: Double,
    val lon: Double,
    val tags: Map<String, String>,
    val category: Category,
    val typeLabel: String,
    val distanceM: Double,
) {
    val name: String get() = tags["name"] ?: typeLabel
    val osmUrl: String get() = "https://www.openstreetmap.org/$osmType/$osmId"
}

/** Lekéri az OSM-ből (Overpass API) a turista- és kerékpáros pihenőket egy kör területén. */
object OverpassClient {

    private val endpoints = listOf(
        "https://overpass-api.de/api/interpreter",
        "https://overpass.kumi.systems/api/interpreter",
        "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
    )

    // Ezeket a menedék-típusokat nem tekintjük pihenőnek (pl. buszmegálló-fedél)
    private const val EXCLUDED_SHELTERS =
        "^(public_transport|changing_rooms|field_shelter|parking|smoking_shelter|sun_shelter)$"
    private const val BIKE_OK = "^(yes|designated)$"

    fun buildQuery(lat: Double, lon: Double, radiusM: Int, tourist: Boolean, cycling: Boolean): String {
        val a = String.format(Locale.US, "(around:%d,%.6f,%.6f)", radiusM, lat, lon)
        val parts = mutableListOf<String>()
        if (tourist) {
            parts += """nwr["amenity"="shelter"]["shelter_type"!~"$EXCLUDED_SHELTERS"]$a;"""
            parts += """nwr["tourism"~"^(picnic_site|wilderness_hut|alpine_hut)$"]$a;"""
            parts += """nwr["leisure"~"^(picnic_table|firepit)$"]$a;"""
        }
        if (cycling) {
            parts += """nwr["amenity"="bicycle_repair_station"]$a;"""
            parts += """nwr["amenity"="shelter"]["bicycle"~"$BIKE_OK"]$a;"""
            parts += """nwr["tourism"="picnic_site"]["bicycle"~"$BIKE_OK"]$a;"""
            parts += """nwr["highway"="rest_area"]["bicycle"~"$BIKE_OK"]$a;"""
            parts += """nwr["leisure"="picnic_table"]["bicycle"~"$BIKE_OK"]$a;"""
        }
        return "[out:json][timeout:60];(\n${parts.joinToString("\n")}\n);out center tags;"
    }

    suspend fun fetch(
        center: GeoPoint, radiusM: Int, tourist: Boolean, cycling: Boolean,
    ): List<RestPlace> = withContext(Dispatchers.IO) {
        val query = buildQuery(center.latitude, center.longitude, radiusM, tourist, cycling)
        var lastError: Exception? = null
        for (ep in endpoints) {
            try {
                val json = post(ep, query)
                return@withContext parse(json, center)
                    .filter { (it.category == Category.TOURIST && tourist) || (it.category == Category.CYCLING && cycling) }
                    .filter { it.distanceM <= radiusM * 1.02 }
                    .sortedBy { it.distanceM }
            } catch (e: Exception) {
                lastError = e
            }
        }
        throw lastError ?: IOException("Ismeretlen hiba")
    }

    private fun post(endpoint: String, query: String): String {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            conn.doOutput = true
            conn.setRequestProperty("User-Agent", "Pihenokereso-Android/1.0")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            conn.outputStream.use { it.write(("data=" + URLEncoder.encode(query, "UTF-8")).toByteArray()) }
            val code = conn.responseCode
            if (code != 200) throw IOException("Overpass HTTP $code ($endpoint)")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(json: String, center: GeoPoint): List<RestPlace> {
        val elements = JSONObject(json).optJSONArray("elements") ?: return emptyList()
        val out = ArrayList<RestPlace>(elements.length())
        for (i in 0 until elements.length()) {
            val el = elements.getJSONObject(i)
            val (lat, lon) = when {
                el.has("lat") -> el.getDouble("lat") to el.getDouble("lon")
                el.has("center") -> el.getJSONObject("center").let { it.getDouble("lat") to it.getDouble("lon") }
                else -> continue
            }
            val tagsJson = el.optJSONObject("tags") ?: continue
            val tags = buildMap { tagsJson.keys().forEach { k -> put(k, tagsJson.getString(k)) } }
            out += RestPlace(
                osmType = el.getString("type"),
                osmId = el.getLong("id"),
                lat = lat, lon = lon,
                tags = tags,
                category = classify(tags),
                typeLabel = typeLabel(tags),
                distanceM = center.distanceToAsDouble(GeoPoint(lat, lon)),
            )
        }
        return out
    }

    fun classify(tags: Map<String, String>): Category {
        if (tags["amenity"] == "bicycle_repair_station") return Category.CYCLING
        if (tags["bicycle"] == "yes" || tags["bicycle"] == "designated") return Category.CYCLING
        if (tags.keys.any { it.startsWith("service:bicycle") }) return Category.CYCLING
        return Category.TOURIST
    }

    fun typeLabel(tags: Map<String, String>): String = when {
        tags["amenity"] == "bicycle_repair_station" -> "Kerékpár-szerelő pont"
        tags["amenity"] == "shelter" -> when (tags["shelter_type"]) {
            "picnic_shelter" -> "Fedett pihenő"
            "basic_hut" -> "Kunyhó"
            "weather_shelter" -> "Esőbeálló"
            "lean_to" -> "Félig nyitott menedék"
            "rock_shelter" -> "Sziklamenedék"
            "gazebo" -> "Pavilon"
            else -> "Menedékhely / esőbeálló"
        }
        tags["tourism"] == "picnic_site" -> "Pihenőhely / piknikező"
        tags["tourism"] == "wilderness_hut" -> "Erdei kunyhó"
        tags["tourism"] == "alpine_hut" -> "Turistaház"
        tags["leisure"] == "picnic_table" -> "Piknikasztal"
        tags["leisure"] == "firepit" -> "Tűzrakóhely"
        tags["highway"] == "rest_area" -> "Pihenőhely"
        else -> "Pihenő"
    }

    /** Ember által olvasható részletek a címkékből. */
    fun details(p: RestPlace): List<String> {
        val t = p.tags
        val out = mutableListOf<String>()
        fun yes(k: String) = t[k] == "yes"
        if (yes("covered") || t["amenity"] == "shelter") out += "Fedett"
        if (yes("bench") || t["leisure"] == "picnic_table") out += "Pad / asztal"
        if (yes("fireplace") || yes("bbq") || t["leisure"] == "firepit") out += "Tűzrakási lehetőség"
        if (yes("drinking_water")) out += "Ivóvíz"
        if (yes("toilets")) out += "WC"
        if (yes("bin") || yes("waste_basket")) out += "Szemetes"
        if (yes("service:bicycle:pump")) out += "Pumpa"
        if (yes("service:bicycle:tools")) out += "Szerszámok"
        if (yes("bicycle_parking") || t["bicycle_parking"] != null) out += "Kerékpártároló"
        t["opening_hours"]?.let { out += "Nyitva: $it" }
        t["operator"]?.let { out += "Üzemeltető: $it" }
        (t["description:hu"] ?: t["description"])?.let { out += it }
        return out
    }
}

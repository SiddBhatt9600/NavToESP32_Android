package com.example.navtoesp32

import com.google.gson.JsonElement
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Mappls integration, parallel to OrsApi/RouteRepository in OrsNavTracker.kt.
 * Reuses LatLon, Step, NavPayload, RouteTracker from that file unchanged —
 * only the API + repository layer differs between providers.
 *
 * Response shape confirmed against a real Mappls account — see chat history
 * for the raw sample response this was built against.
 */

// ---------- 1. Route API (route.mappls.com) ----------

interface MapplsRouteApi {
    // {coordinates} is "lon,lat;lon,lat" or "lon,lat;<eLoc>" — built manually,
    // encoded=true so Retrofit doesn't percent-encode the commas/semicolons.
    @GET("route/direction/route_adv/driving/{coordinates}")
    suspend fun getRoute(
        @Path("coordinates", encoded = true) coordinates: String,
        @Query("access_token") accessToken: String,
        @Query("geometries") geometries: String = "polyline",
        @Query("overview") overview: String = "full",
        @Query("steps") steps: Boolean = true
    ): MapplsRouteResponse
}

data class MapplsRouteResponse(val code: String, val routes: List<MapplsRoute>)
data class MapplsRoute(val distance: Double, val duration: Double, val legs: List<MapplsLeg>)
data class MapplsLeg(val distance: Double, val duration: Double, val steps: List<MapplsStep>)
data class MapplsStep(
    val distance: Double,
    val duration: Double,
    val geometry: String,       // encoded polyline for just this step
    val name: String?,
    val maneuver: MapplsManeuver
)
data class MapplsManeuver(
    val type: String,           // "depart", "turn", "arrive", "new name", "continue", "rotary", "exit rotary", etc.
    val modifier: String?,      // "left", "right", "straight", "slight left", "sharp right", etc.
    val location: List<Double> // [lon, lat]
)

fun buildMapplsRouteApi(): MapplsRouteApi = Retrofit.Builder()
    .baseUrl("https://route.mappls.com/")
    .addConverterFactory(GsonConverterFactory.create())
    .build()
    .create(MapplsRouteApi::class.java)

// ---------- 2. Geocode API (search.mappls.com) ----------

interface MapplsSearchApi {
    @GET("search/address/geocode")
    suspend fun geocode(
        @Query("address") address: String,
        @Query("access_token") accessToken: String,
        @Query("itemCount") itemCount: Int = 1
    ): MapplsGeocodeResponse

    @GET("search/places/autosuggest/json")
    suspend fun autosuggest(
        @Query("query") query: String,
        @Query("access_token") accessToken: String,
        @Query("location") location: String? = null // "lat,lon" — improves relevance, optional
    ): MapplsAutosuggestResponse
}

// copResults can be a single object OR an array depending on itemCount/ambiguity —
// parsed as a raw JsonElement and normalized in the repository below.
data class MapplsGeocodeResponse(val copResults: JsonElement?)

data class MapplsAutosuggestResponse(val suggestedLocations: List<MapplsSuggestion>?)
data class MapplsSuggestion(
    val eLoc: String,
    val placeName: String,
    val placeAddress: String,
    val type: String?
)

fun buildMapplsSearchApi(): MapplsSearchApi = Retrofit.Builder()
    .baseUrl("https://search.mappls.com/")
    .addConverterFactory(GsonConverterFactory.create())
    .build()
    .create(MapplsSearchApi::class.java)

// ---------- 3. Google polyline decoder (precision 1e5 — matches geometries=polyline) ----------

fun decodePolyline(encoded: String, precision: Double = 1e5): List<LatLon> {
    val points = mutableListOf<LatLon>()
    var index = 0
    var lat = 0
    var lng = 0

    while (index < encoded.length) {
        var shift = 0
        var result = 0
        var b: Int
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        val dlat = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
        lat += dlat

        shift = 0
        result = 0
        do {
            b = encoded[index++].code - 63
            result = result or ((b and 0x1f) shl shift)
            shift += 5
        } while (b >= 0x20)
        val dlng = if ((result and 1) != 0) (result shr 1).inv() else (result shr 1)
        lng += dlng

        points.add(LatLon(lat = lat / precision, lon = lng / precision))
    }
    return points
}

// ---------- 4. Repository — mirrors RouteRepository's public shape ----------

class MapplsRouteRepository(
    private val routeApi: MapplsRouteApi,
    private val searchApi: MapplsSearchApi,
    private val accessToken: String
) {

    /**
     * Type-as-you-go suggestions for a partial query. Returns an empty list
     * rather than throwing if there are simply no matches yet (e.g. user has
     * only typed 1-2 characters) — that's a normal, expected state here,
     * unlike geocodeAddress()/fetchRoute() where no result is an error.
     */
    suspend fun autosuggest(query: String, currentLocation: LatLon? = null): List<MapplsSuggestion> {
        if (query.isBlank()) return emptyList()
        val locationParam = currentLocation?.let { "${it.lat},${it.lon}" }
        val resp = searchApi.autosuggest(query = query, accessToken = accessToken, location = locationParam)
        return resp.suggestedLocations ?: emptyList()
    }

    /**
     * Returns the destination's eLoc — Mappls' own address code — which can
     * be passed straight into fetchRoute() as the destination identifier.
     * No lat/lon resolution needed; the routing endpoint accepts eLoc directly.
     */
    suspend fun geocodeAddress(address: String): String {
        val resp = searchApi.geocode(address = address, accessToken = accessToken)
        val element = resp.copResults
            ?: throw IllegalStateException("No results found for \"$address\"")

        val obj = if (element.isJsonArray) {
            val arr = element.asJsonArray
            if (arr.size() == 0) throw IllegalStateException("No results found for \"$address\"")
            arr[0].asJsonObject
        } else {
            element.asJsonObject
        }

        return obj.get("eLoc")?.asString
            ?: throw IllegalStateException("No eLoc in geocode result for \"$address\"")
    }

    /**
     * origin: current GPS position.
     * destinationEloc: from geocodeAddress(), or another eLoc (e.g. re-routing
     * keeps using the same destination eLoc captured at trip start).
     */
    suspend fun fetchRoute(origin: LatLon, destinationEloc: String): List<Step> {
        val coordinates = "${origin.lon},${origin.lat};$destinationEloc"
        val resp = routeApi.getRoute(coordinates = coordinates, accessToken = accessToken)

        val route = resp.routes.firstOrNull()
            ?: throw IllegalStateException("No route found")
        val leg = route.legs.firstOrNull()
            ?: throw IllegalStateException("No route legs found")

        return leg.steps.map { s ->
            Step(
                instruction = s.maneuver.type, // Mappls doesn't give a prebuilt instruction string like ORS did
                roadName = s.name?.ifBlank { "Unnamed road" } ?: "Unnamed road",
                distanceMeters = s.distance,
                durationSeconds = s.duration,
                maneuverType = mapplsManeuverToOrsStyleCode(s.maneuver.type, s.maneuver.modifier),
                polyline = decodePolyline(s.geometry)
            )
        }
    }
}

/**
 * RouteTracker/Step expect an Int maneuverType (the old ORS numeric scheme)
 * purely to feed maneuverToCode() in RouteTracker. Mappls gives us a
 * type+modifier string pair instead, so this maps that pair onto the same
 * Int buckets RouteTracker.maneuverToCode() already switches on — no changes
 * needed in RouteTracker itself.
 */
fun mapplsManeuverToOrsStyleCode(type: String, modifier: String?): Int {
    return when {
        type == "depart" -> 11
        type == "arrive" -> 10
        modifier == "left" -> 0
        modifier == "right" -> 1
        modifier == "sharp left" -> 2
        modifier == "sharp right" -> 3
        modifier == "slight left" -> 4
        modifier == "slight right" -> 5
        modifier == "straight" -> 6
        type == "rotary" || type == "roundabout" || type == "exit rotary" -> 7
        else -> 6
    }
}
package hu.pihenokereso.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.ScaleBarOverlay
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import java.io.File
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var map: MapView
    private lateinit var radiusLabel: TextView
    private lateinit var radiusSeek: SeekBar
    private lateinit var cbTourist: CheckBox
    private lateinit var cbCycling: CheckBox
    private lateinit var status: TextView
    private lateinit var progress: ProgressBar

    private var locationOverlay: MyLocationNewOverlay? = null
    private var radiusCircle: Polygon? = null
    private var centerMarker: Marker? = null
    private val poiMarkers = mutableListOf<Marker>()

    /** Hosszú érintéssel kijelölt keresési középpont (null = saját hely). */
    private var customCenter: GeoPoint? = null
    private var results: List<RestPlace> = emptyList()
    private var searchJob: Job? = null
    private var firstFixHandled = false
    private var lastSearchCenter: GeoPoint? = null

    private val prefs by lazy { getSharedPreferences("settings", Context.MODE_PRIVATE) }

    private val radiusM: Int get() = (radiusSeek.progress + 1) * 500

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        if (granted.values.any { it }) enableMyLocation()
        else status.text = "Helyhozzáférés nélkül: hosszan nyomj a térképre a középpont kijelöléséhez."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // osmdroid beállítás: User-Agent (OSM csempe-szabályzat) + alkalmazás-saját gyorsítótár
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(filesDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }

        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        map = findViewById(R.id.map)
        radiusLabel = findViewById(R.id.radiusLabel)
        radiusSeek = findViewById(R.id.radiusSeek)
        cbTourist = findViewById(R.id.cbTourist)
        cbCycling = findViewById(R.id.cbCycling)
        status = findViewById(R.id.status)
        progress = findViewById(R.id.progress)

        setupMap()

        radiusSeek.progress = prefs.getInt("radiusStep", 3)
        cbTourist.isChecked = prefs.getBoolean("tourist", true)
        cbCycling.isChecked = prefs.getBoolean("cycling", true)
        updateRadiusLabel()

        radiusSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                updateRadiusLabel()
                currentCenter()?.let { drawCircle(it, zoom = false) }
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                prefs.edit().putInt("radiusStep", sb.progress).apply()
                currentCenter()?.let { drawCircle(it, zoom = true) }
            }
        })
        cbTourist.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("tourist", c).apply() }
        cbCycling.setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("cycling", c).apply() }

        findViewById<Button>(R.id.btnSearch).setOnClickListener { search() }
        findViewById<Button>(R.id.btnList).setOnClickListener { showList() }
        findViewById<FloatingActionButton>(R.id.fabLocate).setOnClickListener {
            customCenter = null
            val c = currentCenter()
            if (c == null) toast("Még nincs helyadat – kapcsold be a GPS-t.")
            else { drawCircle(c, zoom = true); search() }
        }

        requestLocation()
    }

    private fun setupMap() {
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
        map.controller.setZoom(7.5)
        map.controller.setCenter(GeoPoint(47.16, 19.50)) // Magyarország közepe

        // Hosszú érintés: egyéni keresési középpont
        map.overlays.add(0, MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint?) = false
            override fun longPressHelper(p: GeoPoint?): Boolean {
                p ?: return false
                customCenter = p
                drawCircle(p, zoom = true)
                search()
                return true
            }
        }))
        map.overlays.add(CopyrightOverlay(this))
        map.overlays.add(ScaleBarOverlay(map).apply {
            setAlignBottom(true)
            setCentred(true)
            setScaleBarOffset(resources.displayMetrics.widthPixels / 2, 20)
        })
    }

    // ---------- Helymeghatározás ----------

    private fun hasLocationPermission() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun requestLocation() {
        if (hasLocationPermission()) enableMyLocation()
        else permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    private fun enableMyLocation() {
        if (locationOverlay != null) return
        val provider = GpsMyLocationProvider(this).apply {
            addLocationSource(LocationManager.NETWORK_PROVIDER)
            locationUpdateMinTime = 3000
            locationUpdateMinDistance = 10f
        }
        val overlay = MyLocationNewOverlay(provider, map).apply {
            enableMyLocation()
        }
        locationOverlay = overlay
        map.overlays.add(overlay)
        status.text = "Helymeghatározás folyamatban…"

        // Ha van korábbi ismert hely, azonnal használjuk
        lastKnownLocation()?.let { onFirstFix(it) }
        overlay.runOnFirstFix {
            val loc = overlay.myLocation ?: return@runOnFirstFix
            runOnUiThread {
                val gps = GeoPoint(loc.latitude, loc.longitude)
                val prev = lastSearchCenter
                if (!firstFixHandled) onFirstFix(gps)
                // ha a korábbi (régi) helyadat távol volt a mostani GPS-helytől, keressünk újra
                else if (customCenter == null && prev != null && prev.distanceToAsDouble(gps) > 300) {
                    drawCircle(gps, zoom = true); search()
                }
            }
        }
    }

    private fun onFirstFix(p: GeoPoint) {
        if (firstFixHandled || customCenter != null) return
        firstFixHandled = true
        drawCircle(p, zoom = true)
        search()
    }

    @SuppressLint("MissingPermission")
    private fun lastKnownLocation(): GeoPoint? {
        if (!hasLocationPermission()) return null
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.getProviders(true)
            .mapNotNull { runCatching { lm.getLastKnownLocation(it) }.getOrNull() }
            .maxByOrNull { it.time }
            ?.let { GeoPoint(it.latitude, it.longitude) }
    }

    private fun currentCenter(): GeoPoint? =
        customCenter ?: locationOverlay?.myLocation?.let { GeoPoint(it.latitude, it.longitude) } ?: lastKnownLocation()

    // ---------- Rajzolás ----------

    private fun updateRadiusLabel() {
        radiusLabel.text = String.format(Locale("hu"), "Sugár: %.1f km", radiusM / 1000.0)
    }

    private fun drawCircle(center: GeoPoint, zoom: Boolean) {
        radiusCircle?.let { map.overlays.remove(it) }
        val pts = Polygon.pointsAsCircle(center, radiusM.toDouble())
        radiusCircle = Polygon(map).apply {
            points = pts
            fillPaint.color = Color.argb(40, 46, 125, 50)
            outlinePaint.color = Color.argb(200, 46, 125, 50)
            outlinePaint.strokeWidth = 4f
            setOnClickListener { _, _, _ -> false }
        }
        // a kör a jelölők alá kerüljön (közvetlenül az eseménykezelő után)
        map.overlays.add(1, radiusCircle)

        centerMarker?.let { map.overlays.remove(it) }
        centerMarker = if (customCenter != null) Marker(map).apply {
            position = center
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            icon = ContextCompat.getDrawable(this@MainActivity, R.drawable.ic_pin_center)
            title = "Keresési középpont"
            setOnMarkerClickListener { _, _ -> true }
        }.also { map.overlays.add(it) } else null

        if (zoom) map.post {
            map.zoomToBoundingBox(BoundingBox.fromGeoPoints(pts).increaseByScale(1.15f), true)
        }
        map.invalidate()
    }

    private fun showResults(list: List<RestPlace>) {
        poiMarkers.forEach { map.overlays.remove(it) }
        poiMarkers.clear()
        val iconT = ContextCompat.getDrawable(this, R.drawable.ic_pin_tourist)
        val iconC = ContextCompat.getDrawable(this, R.drawable.ic_pin_cycling)
        for (p in list) {
            val m = Marker(map).apply {
                position = GeoPoint(p.lat, p.lon)
                setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                icon = if (p.category == Category.CYCLING) iconC else iconT
                title = p.name
                setOnMarkerClickListener { _, _ -> showDetails(p); true }
            }
            poiMarkers += m
            map.overlays.add(m)
        }
        map.invalidate()
    }

    // ---------- Keresés ----------

    private fun search() {
        val center = currentCenter()
        if (center == null) {
            toast("Nincs még hely. Várj a GPS-re, vagy nyomj hosszan a térképre.")
            return
        }
        if (!cbTourist.isChecked && !cbCycling.isChecked) {
            toast("Válassz legalább egy kategóriát.")
            return
        }
        drawCircle(center, zoom = false)
        lastSearchCenter = center
        searchJob?.cancel()
        progress.visibility = View.VISIBLE
        status.text = "Keresés az OpenStreetMap adataiban…"
        searchJob = lifecycleScope.launch {
            try {
                val list = OverpassClient.fetch(center, radiusM, cbTourist.isChecked, cbCycling.isChecked)
                results = list
                showResults(list)
                val t = list.count { it.category == Category.TOURIST }
                val c = list.count { it.category == Category.CYCLING }
                status.text = if (list.isEmpty()) "Nincs találat ebben a körben. Próbálj nagyobb sugarat."
                else "$t turista és $c kerékpáros pihenő" +
                    (if (customCenter != null) " (kijelölt pont körül)" else "")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                status.text = "Hiba a lekérdezésnél: ${e.message}"
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    // ---------- Párbeszédablakok ----------

    private fun formatDistance(m: Double) =
        if (m < 1000) "${m.toInt()} m" else String.format(Locale("hu"), "%.1f km", m / 1000)

    private fun showList() {
        if (results.isEmpty()) { toast("Nincs megjeleníthető találat."); return }
        val items = results.map { p ->
            val kind = if (p.category == Category.CYCLING) "🚲" else "🥾"
            "$kind ${p.name} – ${formatDistance(p.distanceM)}"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("Találatok (${results.size})")
            .setItems(items) { _, i -> showDetails(results[i], zoomTo = true) }
            .setNegativeButton("Bezár", null)
            .show()
    }

    private fun showDetails(p: RestPlace, zoomTo: Boolean = false) {
        if (zoomTo) {
            map.controller.animateTo(GeoPoint(p.lat, p.lon), 17.0, 800L)
        }
        val kat = if (p.category == Category.CYCLING) "Kerékpáros pihenő" else "Turista pihenő"
        val lines = mutableListOf(
            "$kat · ${p.typeLabel}",
            "Távolság: ${formatDistance(p.distanceM)}",
        )
        val det = OverpassClient.details(p)
        if (det.isNotEmpty()) lines += "\n" + det.joinToString("\n") { "• $it" }

        MaterialAlertDialogBuilder(this)
            .setTitle(p.name)
            .setMessage(lines.joinToString("\n"))
            .setPositiveButton("Navigáció") { _, _ -> navigateTo(p) }
            .setNeutralButton("OSM") { _, _ -> openUrl(p.osmUrl) }
            .setNegativeButton("Bezár", null)
            .show()
    }

    private fun navigateTo(p: RestPlace) {
        val label = Uri.encode(p.name)
        val uri = Uri.parse(String.format(Locale.US, "geo:%f,%f?q=%f,%f(%s)", p.lat, p.lon, p.lat, p.lon, label))
        try {
            startActivity(Intent(Intent.ACTION_VIEW, uri))
        } catch (e: Exception) {
            openUrl(String.format(Locale.US, "https://www.openstreetmap.org/directions?route=;%f,%f", p.lat, p.lon))
        }
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (e: Exception) { toast("Nincs alkalmazás a link megnyitásához.") }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()

    // ---------- Életciklus ----------

    override fun onResume() {
        super.onResume()
        map.onResume()
        locationOverlay?.enableMyLocation()
    }

    override fun onPause() {
        locationOverlay?.disableMyLocation()
        map.onPause()
        super.onPause()
    }
}

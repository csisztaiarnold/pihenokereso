# Pihenőkereső (Android)

OpenStreetMap-térkép, saját hely, állítható sugár (0,5–20 km), és a körön belüli
**turista** és **kerékpáros** pihenők megjelenítése az OSM adatai alapján (Overpass API).

## Fordítás
- **Android Studio** (Ladybug vagy újabb): File → Open → ez a mappa → Run ▶
- **Parancssor:** `./gradlew assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`
- **GitHub:** töltsd fel egy repóba – az Actions magától lefordítja, az APK az „Artifacts” alatt lesz.

## Használat
- Indításkor engedélyezd a helyhozzáférést – a térkép rááll a helyedre és keres.
- Csúszka: sugár. Jelölőnégyzetek: kategóriák. **Keresés** gomb: új lekérdezés.
- **Hosszú nyomás** a térképen: tetszőleges pont körül keres. A célkereszt gomb visszaállít a saját helyedre.
- Jelölőre bökve: részletek (fedett, pad, ivóvíz, tűzrakó…), **Navigáció**, OSM-link. **Lista**: távolság szerint rendezve.
- Zöld jelölő = turista pihenő, kék = kerékpáros.

## Mit keres az OSM-ben
| Kategória | OSM címkék |
|---|---|
| Turista | `amenity=shelter` (buszmegálló-fedél stb. nélkül), `tourism=picnic_site / wilderness_hut / alpine_hut`, `leisure=picnic_table / firepit` |
| Kerékpáros | `amenity=bicycle_repair_station`, valamint shelter / picnic_site / rest_area / picnic_table `bicycle=yes|designated` címkével, ill. `service:bicycle:*` |

A besorolás az `OverpassClient.kt`-ban (`buildQuery`, `classify`) könnyen módosítható.

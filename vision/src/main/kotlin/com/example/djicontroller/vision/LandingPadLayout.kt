package com.example.djicontroller.vision

/** Słowniki ArUco obsługiwane przez detektor (mapowane na stałe `Objdetect.DICT_*`). */
enum class ArucoDictionary { DICT_4X4_50 }

/**
 * Jeden marker na lądowisku.
 * @property sizeM długość boku markera razem z czarną ramką, m (dokładnie tyle, ile ma wydruk!)
 * @property offsetRightM położenie środka markera względem środka lądowiska wzdłuż osi „w prawo” markera, m
 * @property offsetUpM jw. wzdłuż osi „w górę” markera, m
 * @property nestedIn identyfikator markera, w którego środku leży ten marker (`null` = samodzielny)
 */
data class PadMarker(
    val id: Int,
    val sizeM: Double,
    val offsetRightM: Double = 0.0,
    val offsetUpM: Double = 0.0,
    val nestedIn: Int? = null,
)

/**
 * Układ markerów na płycie stacji (sekcja 5.4): duży marker widoczny z 8–10 m i mały wewnątrz
 * do ostatniej fazy podejścia.
 *
 * Zagnieżdżenie działa dzięki wyborowi identyfikatorów: duży marker [STATION] (DICT_4X4_50, id 16)
 * ma cztery środkowe pola białe, a mały marker (id 3, bok 1,2 pola dużego) leży dokładnie na nich.
 * Białe tło wokół małego markera daje mu wymagany margines, a duży marker nadal dekoduje się
 * poprawnie, gdy mały zostanie zamaskowany (detekcja dwuprzebiegowa w [ArucoDetector]).
 */
data class LandingPadLayout(
    val name: String,
    val dictionary: ArucoDictionary,
    val markers: List<PadMarker>,
    /** Bok białego pola lądowiska (wydruku), m — używany do renderowania i wydruku. */
    val padSizeM: Double,
) {
    init {
        require(markers.isNotEmpty()) { "Lądowisko bez markerów" }
        require(markers.map { it.id }.toSet().size == markers.size) { "Powtórzony identyfikator markera" }
        for (m in markers) {
            require(m.sizeM > 0) { "Marker ${m.id}: rozmiar musi być dodatni" }
            val parentId = m.nestedIn ?: continue
            val parent = requireNotNull(markers.firstOrNull { it.id == parentId }) { "Marker ${m.id}: brak rodzica $parentId" }
            require(parent.nestedIn == null) { "Dozwolony jeden poziom zagnieżdżenia" }
            require(m.sizeM <= parent.sizeM * MAX_NESTED_CELLS / 6.0) {
                "Marker ${m.id} za duży, żeby zmieścić się w środkowych polach markera $parentId"
            }
            require(m.offsetRightM == parent.offsetRightM && m.offsetUpM == parent.offsetUpM) {
                "Marker zagnieżdżony musi być w środku rodzica"
            }
        }
    }

    fun marker(id: Int): PadMarker? = markers.firstOrNull { it.id == id }

    /** Marker, którego środkowe pola zajmuje [child] — do drugiego przebiegu detekcji. */
    fun parentOf(child: PadMarker): PadMarker? = child.nestedIn?.let { marker(it) }

    fun childrenOf(parent: PadMarker): List<PadMarker> = markers.filter { it.nestedIn == parent.id }

    companion object {
        /** Maksymalny bok markera zagnieżdżonego, w polach rodzica (rodzic 4×4 ma 6×6 pól z ramką). */
        const val MAX_NESTED_CELLS = 1.3

        /** Płyta stacji: duży 30 cm (zasięg ok. 10 m przy 720p), mały 6 cm (od ok. 0,4 do 4–6 m). */
        val STATION = LandingPadLayout(
            name = "Stacja 30/6 cm",
            dictionary = ArucoDictionary.DICT_4X4_50,
            markers = listOf(
                PadMarker(id = 16, sizeM = 0.30),
                PadMarker(id = 3, sizeM = 0.06, nestedIn = 16),
            ),
            padSizeM = 0.45,
        )

        /** Wydruk na A4 do testów w pomieszczeniu (R3/R4 z drona trzymanego w ręku lub z pliku). */
        val DESK_A4 = LandingPadLayout(
            name = "A4 15/3 cm",
            dictionary = ArucoDictionary.DICT_4X4_50,
            markers = listOf(
                PadMarker(id = 16, sizeM = 0.15),
                PadMarker(id = 3, sizeM = 0.03, nestedIn = 16),
            ),
            padSizeM = 0.20,
        )

        val PRESETS = listOf(STATION, DESK_A4)
    }
}

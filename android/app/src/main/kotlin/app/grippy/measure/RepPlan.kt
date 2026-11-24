// SPDX-License-Identifier: MPL-2.0
// Grippy: session plans for camera-counted reps — the exercises, their weights and arms, in order.
// Not part of upstream.

package app.grippy.measure

import android.content.Context
import androidx.annotation.DrawableRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import run.nuri.getagrip.R
import java.util.Locale

/** Which arms an exercise is done with, and in which order. */
enum class Arms(val key: String, val label: String, val short: String, val hands: List<Hand>) {
    LeftThenRight("lr", "Left then right", "L → R", listOf(Hand.Left, Hand.Right)),
    RightThenLeft("rl", "Right then left", "R → L", listOf(Hand.Right, Hand.Left)),
    LeftOnly("l", "Left only", "Left", listOf(Hand.Left)),
    RightOnly("r", "Right only", "Right", listOf(Hand.Right));

    companion object {
        fun of(key: String): Arms = entries.firstOrNull { it.key == key } ?: LeftThenRight
    }
}

/** One exercise in a plan. [exerciseId] points at the Measure section's exercise of that name. */
data class PlanItem(val exerciseId: Long, val weightKg: Double, val arms: Arms = Arms.LeftThenRight)

data class SessionPlan(val id: Long, val name: String, val items: List<PlanItem>) {
    val setCount: Int get() = items.sumOf { it.arms.hands.size }
}

/** One set to do: an exercise with one arm, as a session runs through them. */
data class RepSet(val exerciseId: Long?, val exercise: String, val weightKg: Double?, val hand: Hand)

/** Every set of [plan], in order. */
fun SessionPlan.sets(names: (Long) -> String?): List<RepSet> =
    items.flatMap { item -> item.arms.hands.map { RepSet(item.exerciseId, names(item.exerciseId) ?: "Exercise", item.weightKg, it) } }

/** "5 kg", "2.5 kg". */
fun kgText(kg: Double): String =
    if (kg == Math.rint(kg)) "${kg.toInt()} kg" else String.format(Locale.US, "%.1f kg", kg)

/** The plans, kept as JSON on this phone. One per person is the usual; any number works. */
class RepPlans private constructor(private val context: Context) {
    private val prefs = context.getSharedPreferences("grippy-rep-plans", Context.MODE_PRIVATE)
    private val _plans = MutableStateFlow(read())
    val plans: StateFlow<List<SessionPlan>> = _plans.asStateFlow()

    fun plan(id: Long): SessionPlan? = _plans.value.firstOrNull { it.id == id }

    fun save(plan: SessionPlan) {
        val list = _plans.value.toMutableList()
        val i = list.indexOfFirst { it.id == plan.id }
        if (i >= 0) list[i] = plan else list += plan
        write(list)
    }

    fun delete(id: Long) = write(_plans.value.filterNot { it.id == id })

    fun newId(): Long = (_plans.value.maxOfOrNull { it.id } ?: 0) + 1

    /**
     * The first time: a forearm session with the exercises the camera was tuned for (made in the
     * Measure section if they are not there yet). Later calls change nothing.
     */
    suspend fun seedIfEmpty(store: MeasureStore) {
        if (prefs.getBoolean(SEEDED, false) || _plans.value.isNotEmpty()) return
        prefs.edit().putBoolean(SEEDED, true).apply()
        val existing = store.currentExercises()
        suspend fun id(name: String): Long =
            existing.firstOrNull { it.name.equals(name, ignoreCase = true) }?.id ?: store.addExercise(name, null)
        val items = listOf(
            PlanItem(id("Wrist extension (strap)"), 5.0),
            PlanItem(id("Pronation"), 2.0),
            PlanItem(id("Supination"), 2.0),
            PlanItem(id("Rising (radial deviation)"), 3.0),
        )
        save(SessionPlan(newId(), "Forearm session", items))
    }

    private fun read(): List<SessionPlan> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            val items = o.getJSONArray("items")
            SessionPlan(
                id = o.getLong("id"), name = o.optString("name", "Session"),
                items = List(items.length()) { k ->
                    val it = items.getJSONObject(k)
                    PlanItem(it.getLong("exercise"), it.optDouble("kg", 0.0), Arms.of(it.optString("arms", "lr")))
                },
            )
        }
    }.getOrDefault(emptyList())

    private fun write(list: List<SessionPlan>) {
        val array = JSONArray()
        for (p in list) {
            val items = JSONArray()
            for (it in p.items) items.put(JSONObject().put("exercise", it.exerciseId).put("kg", it.weightKg).put("arms", it.arms.key))
            array.put(JSONObject().put("id", p.id).put("name", p.name).put("items", items))
        }
        prefs.edit().putString(KEY, array.toString()).apply()
        _plans.value = list
    }

    companion object {
        private const val KEY = "plans"
        private const val SEEDED = "seeded"
        @Volatile private var instance: RepPlans? = null

        fun get(context: Context): RepPlans {
            val app = context.applicationContext
            instance?.takeIf { it.context === app }?.let { return it }
            return synchronized(this) {
                instance?.takeIf { it.context === app } ?: RepPlans(app).also { instance = it }
            }
        }
    }
}

/** A drawing of the exercise when its name says which one it is; null otherwise. */
@DrawableRes
fun exerciseArt(name: String): Int? {
    val n = name.lowercase(Locale.ROOT)
    return when {
        "pronation" in n && "supination" !in n -> R.drawable.exercise_pronation
        "supination" in n && "pronation" !in n -> R.drawable.exercise_supination
        "rising" in n || "radial" in n -> R.drawable.exercise_rising
        "extension" in n -> R.drawable.exercise_extension
        else -> null
    }
}

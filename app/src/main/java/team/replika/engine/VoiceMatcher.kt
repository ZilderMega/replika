package team.replika.engine

import kotlin.math.sqrt

data class VoiceProfile(var centroid: FloatArray, var supportMs: Long,
    val examples: MutableList<FloatArray> = mutableListOf(centroid.copyOf())) {
    fun snapshot() = VoiceProfile(centroid.copyOf(), supportMs, examples.map { it.copyOf() }.toMutableList())
}

data class VoiceObservation(val localId: Int, val intervals: List<LongRange>, val embeddings: List<FloatArray>, val supportMs: Long) {
    val vector: FloatArray? get() = VoiceMatcher.average(embeddings)
    val consistent: Boolean get() = embeddings.indices.all { a ->
        (a + 1 until embeddings.size).all { b -> VoiceMatcher.cosine(embeddings[a], embeddings[b]) >= .5f }
    }
}

/** Anonymous voice clustering, not proof of a person's identity. Similarity thresholds need corpus calibration. */
object VoiceMatcher {
    const val VERSION = 1
    private const val MATCH = .74f
    private const val FIRST_PROFILE_MATCH = .80f
    private const val MARGIN = .08f
    private const val SAME_LOCAL_VOICE = .88f
    private const val CLEAR_NEW_VOICE = .50f

    fun normalize(vector: FloatArray): FloatArray? {
        if (vector.isEmpty() || vector.any { !it.isFinite() }) return null
        val norm = sqrt(vector.sumOf { it.toDouble() * it }).toFloat()
        return if (norm > 1e-8f) FloatArray(vector.size) { vector[it] / norm } else null
    }
    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.isEmpty() || a.size != b.size) return -1f
        var sum = 0.0
        a.indices.forEach { sum += a[it].toDouble() * b[it] }
        return sum.toFloat().coerceIn(-1f, 1f)
    }
    fun average(vectors: List<FloatArray>): FloatArray? {
        if (vectors.isEmpty() || vectors.any { it.size != vectors.first().size }) return null
        val result = FloatArray(vectors.first().size)
        vectors.forEach { vector -> vector.indices.forEach { result[it] += vector[it] / vectors.size } }
        return normalize(result)
    }

    fun overlap(a: List<LongRange>, b: List<LongRange>) = a.any { x -> b.any { y -> minOf(x.last, y.last) - maxOf(x.first, y.first) >= 120 } }

    private fun score(profile: VoiceProfile, vector: FloatArray): Float {
        val closest = profile.examples.map { cosine(it, vector) }.sortedDescending().take(2)
        return .65f * cosine(profile.centroid, vector) + .35f * (closest.average().takeIf { it.isFinite() }?.toFloat() ?: -1f)
    }

    fun assign(observations: List<VoiceObservation>, profiles: MutableList<VoiceProfile>, expectedSpeakers: Int = 0): Map<Int, Int> {
        val valid = observations.filter { it.vector != null && it.embeddings.isNotEmpty() && it.consistent }
        val vectors = valid.associate { it.localId to requireNotNull(it.vector) }
        val mapping = linkedMapOf<Int, Int>()
        val initialCount = profiles.size
        fun compatible(observation: VoiceObservation, target: Int): Boolean = mapping.filterValues { it == target }.keys.all { otherId ->
            val other = valid.first { it.localId == otherId }
            !overlap(observation.intervals, other.intervals) && cosine(vectors.getValue(observation.localId), vectors.getValue(otherId)) >= SAME_LOCAL_VOICE
        }
        fun ranked(observation: VoiceObservation, limit: Int = profiles.size) = (0 until limit)
            .map { it to score(profiles[it], vectors.getValue(observation.localId)) }.sortedByDescending { it.second }
        // Resolve high-confidence existing matches first, instead of whichever native cluster was enumerated first.
        val ordered = valid.sortedByDescending { observation ->
            val scores = ranked(observation, initialCount)
            scores.firstOrNull()?.second?.minus(scores.getOrNull(1)?.second ?: 0f) ?: -1f
        }
        ordered.forEach { observation ->
            val scores = ranked(observation, initialCount)
            val best = scores.firstOrNull() ?: return@forEach
            val threshold = if (observation.supportMs < 1000) .88f else if (initialCount == 1) FIRST_PROFILE_MATCH else MATCH
            if (best.second >= threshold && best.second - (scores.getOrNull(1)?.second ?: 0f) >= MARGIN && compatible(observation, best.first))
                mapping[observation.localId] = best.first
        }
        valid.sortedBy { it.intervals.minOfOrNull { interval -> interval.first } ?: Long.MAX_VALUE }.forEach { observation ->
            if (observation.localId in mapping) return@forEach
            val scores = ranked(observation)
            val best = scores.firstOrNull()
            // A native over-split can map back to the same global voice when clean samples agree and do not co-occur.
            if (best != null && best.second >= SAME_LOCAL_VOICE && best.second - (scores.getOrNull(1)?.second ?: 0f) >= MARGIN && compatible(observation, best.first)) {
                mapping[observation.localId] = best.first
            } else if (observation.supportMs >= 1000 && (best == null || best.second < CLEAR_NEW_VOICE) &&
                (expectedSpeakers == 0 || profiles.size < expectedSpeakers)) {
                mapping[observation.localId] = profiles.size
                profiles += VoiceProfile(vectors.getValue(observation.localId).copyOf(), observation.supportMs.coerceAtMost(12_000),
                    observation.embeddings.take(5).map { it.copyOf() }.toMutableList())
            }
            // The uncertain band deliberately stays unknown. Never force a new ID or the nearest similar voice.
        }
        mapping.entries.groupBy { it.value }.forEach { (id, matches) ->
            if (id >= initialCount) return@forEach
            val accepted = matches.map { entry -> valid.first { it.localId == entry.key } }
            val vector = average(accepted.map { vectors.getValue(it.localId) }) ?: return@forEach
            val profile = profiles[id]
            val oldWeight = profile.supportMs.coerceAtMost(12_000).coerceAtLeast(1000).toFloat()
            val newWeight = accepted.sumOf { it.supportMs }.coerceAtMost(6000).coerceAtLeast(500).toFloat()
            profile.centroid = normalize(FloatArray(vector.size) { profile.centroid[it] * oldWeight + vector[it] * newWeight }) ?: profile.centroid
            profile.supportMs = (oldWeight + newWeight).toLong().coerceAtMost(18_000)
            accepted.flatMap { it.embeddings }.forEach { example ->
                if (profile.examples.none { cosine(it, example) >= .995f }) {
                    profile.examples += example.copyOf()
                    if (profile.examples.size > 5) profile.examples.removeAt(0)
                }
            }
        }
        return mapping
    }

    /** Exclude overlapping voices before choosing enrollment snippets. */
    fun cleanIntervals(owned: List<LongRange>, otherVoices: List<LongRange>): List<LongRange> {
        var pieces = AudioPreparation.merge(owned)
        AudioPreparation.merge(otherVoices.map { (it.first - 100).coerceAtLeast(0)..(it.last + 100) }).forEach { blocked ->
            pieces = pieces.flatMap { piece ->
                if (blocked.last <= piece.first || blocked.first >= piece.last) listOf(piece)
                else buildList {
                    if (blocked.first > piece.first) add(piece.first..minOf(piece.last, blocked.first))
                    if (blocked.last < piece.last) add(maxOf(piece.first, blocked.last)..piece.last)
                }
            }
        }
        return pieces.filter { it.last - it.first >= 500 }
    }

    fun sampleIntervals(clean: List<LongRange>): List<LongRange> {
        val longest = clean.maxByOrNull { it.last - it.first }
        if (clean.size == 1 && longest != null && longest.last - longest.first >= 6000) {
            val count = minOf(3L, (longest.last - longest.first) / 3000).toInt()
            return List(count) { index ->
                val start = longest.first + index * (longest.last - longest.first - 3000) / (count - 1)
                start..(start + 3000)
            }
        }
        val chosen = clean.sortedByDescending { it.last - it.first }.take(3).map { region ->
            val length = minOf(3000, region.last - region.first)
            val center = (region.first + region.last) / 2
            (center - length / 2)..(center - length / 2 + length)
        }.toMutableList()
        if (longest != null && longest.last - longest.first >= 6000) {
            for (extra in listOf(longest.first..(longest.first + 3000), (longest.last - 3000)..longest.last)) {
                if (chosen.size < 3 && chosen.none { minOf(it.last, extra.last) > maxOf(it.first, extra.first) }) chosen += extra
            }
        }
        return chosen
    }
}

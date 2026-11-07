package team.replika.engine

/** Model evidence is independent of global IDs and can be rematched without reading the audio. */
data class VoiceBlock(val startSample: Long, val endSample: Long, val observations: List<VoiceObservation>,
    val assignments: MutableMap<Int, Int> = linkedMapOf()) {
    init { require(startSample >= 0 && endSample > startSample && observations.map { it.localId }.distinct().size == observations.size) }
}

object VoiceReconciliation {
    fun resolve(blocks: List<VoiceBlock>, profiles: List<VoiceProfile>): Map<Int, Int> {
        if (profiles.isEmpty()) return emptyMap()
        val replacements = linkedMapOf<Int, Int>()
        blocks.forEach { block ->
            val fixed = block.assignments.filterValues { it >= 0 }
            // Never train the bank on this second pass or create a new identity from uncertainty.
            val matches = VoiceMatcher.assign(block.observations, profiles.map { it.snapshot() }.toMutableList(),
                expectedSpeakers = profiles.size, fixedAssignments = fixed)
            block.assignments.filterValues { it < -1 }.forEach { (local, provisional) ->
                matches[local]?.let { replacements[provisional] = it }
            }
        }
        return replacements
    }
}

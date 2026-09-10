package com.muchenhen.gpslog.data

data class RecordingProfileConfig(
    val id: String,
    val targetIntervalSeconds: Int,
    val minIntervalSeconds: Int,
    val maxBatchDelaySeconds: Int,
    val highAccuracy: Boolean,
)

object RecordingProfiles {
    const val ENDURANCE = "endurance"
    const val PRECISION_WALK = "precision_walk"

    private const val DEFAULT_ENDURANCE_INTERVAL_SECONDS = 240
    private const val MIN_ENDURANCE_INTERVAL_SECONDS = 60
    private const val MAX_ENDURANCE_INTERVAL_SECONDS = 300

    fun normalize(profile: String?): String = when (profile) {
        PRECISION_WALK -> PRECISION_WALK
        else -> ENDURANCE
    }

    fun config(profile: String?, enduranceTargetIntervalSeconds: Int): RecordingProfileConfig =
        when (normalize(profile)) {
            PRECISION_WALK -> RecordingProfileConfig(
                id = PRECISION_WALK,
                targetIntervalSeconds = 10,
                minIntervalSeconds = 5,
                maxBatchDelaySeconds = 15,
                highAccuracy = true,
            )

            else -> {
                val target = enduranceTargetIntervalSeconds.coerceIn(
                    MIN_ENDURANCE_INTERVAL_SECONDS,
                    MAX_ENDURANCE_INTERVAL_SECONDS,
                )
                RecordingProfileConfig(
                    id = ENDURANCE,
                    targetIntervalSeconds = target,
                    minIntervalSeconds = minOf(120, target),
                    maxBatchDelaySeconds = 300,
                    highAccuracy = false,
                )
            }
        }

    fun defaultEnduranceIntervalSeconds(): Int = DEFAULT_ENDURANCE_INTERVAL_SECONDS

    fun usesHighAccuracy(profile: String?): Boolean = normalize(profile) == PRECISION_WALK
}

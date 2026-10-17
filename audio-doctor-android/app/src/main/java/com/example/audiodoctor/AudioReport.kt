package com.example.audiodoctor

import org.json.JSONArray
import org.json.JSONObject

data class AudioReport(
    val fileName: String,
    val fileSizeBytes: Long,
    val codec: String,
    val durationSeconds: Double,
    val sampleRateHz: Int,
    val sourceChannels: Int,
    val bitRateBps: Long?,
    val titleTag: String?,
    val artistTag: String?,
    val albumTag: String?,
    val coverArtPresent: Boolean,
    val samplePeakDbfs: Double,
    val truePeakEstimateDbtp: Double,
    val rmsLevelDbfs: Double,
    val integratedLufsEstimate: Double?,
    val crestFactorDb: Double,
    val clippedSampleCount: Long,
    val clippedSampleRatio: Double,
    val hardClipPlateauPairs: Long,
    val dcOffsetLeft: Double,
    val dcOffsetRight: Double,
    val leadingSilenceSeconds: Double,
    val trailingSilenceSeconds: Double,
    val shortTermLevelSpreadDb: Double?,
    val stereoCorrelation: Double?,
    val monoSumLevelChangeDb: Double?,
    val tempoBpmEstimate: Double?,
    val musicalKeyEstimate: String?,
    val keyConfidence: String?,
    val estimatedSpectralCutoffHz: Int?,
    val decodeWarnings: List<String>,
    val waveformMinimum: List<Float>,
    val waveformMaximum: List<Float>,
    val spectrumDb: List<Float>,
    val notes: List<String>
) {
    fun toJson(): String {
        val metrics = JSONObject()
            .put("sample_peak_dbfs", samplePeakDbfs)
            .put("true_peak_estimate_dbtp_4x_linear", truePeakEstimateDbtp)
            .put("rms_level_dbfs", rmsLevelDbfs)
            .put("integrated_lufs_estimate", jsonValue(integratedLufsEstimate))
            .put("crest_factor_db", crestFactorDb)
            .put("clipped_sample_count", clippedSampleCount)
            .put("clipped_sample_ratio", clippedSampleRatio)
            .put("hard_clip_plateau_pairs", hardClipPlateauPairs)
            .put("dc_offset_left", dcOffsetLeft)
            .put("dc_offset_right", dcOffsetRight)
            .put("leading_silence_seconds", leadingSilenceSeconds)
            .put("trailing_silence_seconds", trailingSilenceSeconds)
            .put("short_term_level_spread_db", jsonValue(shortTermLevelSpreadDb))
            .put("stereo_correlation", jsonValue(stereoCorrelation))
            .put("mono_sum_level_change_db", jsonValue(monoSumLevelChangeDb))
            .put("tempo_bpm_estimate", jsonValue(tempoBpmEstimate))
            .put("musical_key_estimate", jsonValue(musicalKeyEstimate))
            .put("key_confidence", jsonValue(keyConfidence))
            .put("estimated_spectral_cutoff_hz", jsonValue(estimatedSpectralCutoffHz))
        val file = JSONObject()
            .put("name", fileName)
            .put("size_bytes", fileSizeBytes)
            .put("codec", codec)
            .put("duration_seconds", durationSeconds)
            .put("sample_rate_hz", sampleRateHz)
            .put("source_channels", sourceChannels)
            .put("bit_rate_bps", jsonValue(bitRateBps))
            .put("title_tag", jsonValue(titleTag))
            .put("artist_tag", jsonValue(artistTag))
            .put("album_tag", jsonValue(albumTag))
            .put("cover_art_detected", coverArtPresent)
        val graph = JSONObject()
            .put("waveform_minimum", floatArray(waveformMinimum))
            .put("waveform_maximum", floatArray(waveformMaximum))
            .put("spectrum_db", floatArray(spectrumDb))
            .put("spectrum_frequency_bin_hz", sampleRateHz.toDouble() / 8192.0)
        return JSONObject()
            .put("tool", "Audio Doctor Studio for Android")
            .put("schema_version", 1)
            .put("file", file)
            .put("metrics", metrics)
            .put("decode_warnings", JSONArray(decodeWarnings))
            .put("graph_data", graph)
            .put("notes", JSONArray(notes))
            .toString(2)
    }

    private fun floatArray(values: List<Float>): JSONArray {
        val output = JSONArray()
        values.forEach { output.put(it.toDouble()) }
        return output
    }

    private fun jsonValue(value: Any?): Any = value ?: JSONObject.NULL
}

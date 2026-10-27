package app.apexview

import android.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.Base64
import java.util.zip.Inflater
import kotlin.math.hypot

/** Which archived session a replay belongs to, from F1 TV's video metadata. */
data class SessionRef(
    val year: Int,
    val meetingKey: Int?,
    val sessionKey: Int?,
    val circuitKey: Int?,
    val meetingName: String,
    val title: String,
)

enum class LapState { UNKNOWN, PIT, OUT_LAP, PUSH, COOL_DOWN, KNOCKED_OUT }

class DriverInfo(
    val number: String,
    val tla: String,
    val color: Int,
    val firstName: String = "",
    val lastName: String = "",
    val team: String = "",
)

/** A team radio recording: when it was published, whose it is, and its file under the session. */
class RadioClip(val utc: Long, val number: String, val path: String)

/** A numbered corner, in archive coordinates. */
class Corner(val number: Int, val x: Float, val y: Float)

/**
 * The circuit as a closed loop, with distance along it. [progress] turns a car's position into
 * how far round the lap it is (0..1), measured from the loop's first point.
 */
class TrackGeometry(val outline: FloatArray) {
    private val n = outline.size / 2
    private val along = FloatArray(n)
    val length: Float

    /**
     * True when the lap runs counter-clockwise as the track map draws it. The map keeps the
     * archive's y-up orientation (rotation only), so the sign of the outline's area decides.
     */
    val counterClockwise: Boolean

    init {
        for (i in 1 until n) along[i] = along[i - 1] + dist(i - 1, i)
        length = if (n > 1) along[n - 1] + dist(n - 1, 0) else 0f
        var area = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            area += outline[i * 2].toDouble() * outline[j * 2 + 1] - outline[j * 2].toDouble() * outline[i * 2 + 1]
        }
        counterClockwise = area > 0
    }

    private fun dist(a: Int, b: Int) =
        hypot(outline[a * 2] - outline[b * 2], outline[a * 2 + 1] - outline[b * 2 + 1])

    fun nearestIndex(x: Float, y: Float): Int {
        var best = 0
        var bestD = Float.MAX_VALUE
        for (i in 0 until n) {
            val dx = outline[i * 2] - x
            val dy = outline[i * 2 + 1] - y
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    /**
     * The point [f] of the way round the lap (0..1 from the loop's first point) and the unit
     * direction of travel there, as x, y, dx, dy.
     */
    fun pointAt(f: Float): FloatArray {
        if (n < 2 || length == 0f) return floatArrayOf(0f, 0f, 1f, 0f)
        val target = f.mod(1f) * length
        for (i in 0 until n) {
            val j = (i + 1) % n
            val end = if (j == 0) length else along[j]
            if (target <= end || j == 0) {
                val span = end - along[i]
                val k = if (span == 0f) 0f else ((target - along[i]) / span).coerceIn(0f, 1f)
                val ax = outline[i * 2]; val ay = outline[i * 2 + 1]
                val bx = outline[j * 2]; val by = outline[j * 2 + 1]
                val len = hypot(bx - ax, by - ay).coerceAtLeast(0.001f)
                return floatArrayOf(ax + (bx - ax) * k, ay + (by - ay) * k, (bx - ax) / len, (by - ay) / len)
            }
        }
        return floatArrayOf(outline[0], outline[1], 1f, 0f)
    }

    /** Projected onto the nearest segment, so the value moves smoothly between outline points. */
    fun progress(x: Float, y: Float): Float {
        if (n < 2 || length == 0f) return 0f
        val i = nearestIndex(x, y)
        var best = along[i]
        var bestD = Float.MAX_VALUE
        for (j in listOf((i - 1 + n) % n, i)) {
            val k = (j + 1) % n
            val ax = outline[j * 2]; val ay = outline[j * 2 + 1]
            val bx = outline[k * 2]; val by = outline[k * 2 + 1]
            val vx = bx - ax; val vy = by - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 == 0f) 0f else (((x - ax) * vx + (y - ay) * vy) / len2).coerceIn(0f, 1f)
            val px = ax + vx * t; val py = ay + vy * t
            val d = (px - x) * (px - x) + (py - y) * (py - y)
            if (d < bestD) {
                bestD = d
                val start = along[j]
                val segment = hypot(vx, vy)
                best = if (k == 0) start + segment * t else start + (along[k] - start) * t
            }
        }
        return (best / length).mod(1f)
    }
}

/** One car's positions over the session, in archive coordinates (1/10 m). */
class DriverTrack(private val t: LongArray, private val x: FloatArray, private val y: FloatArray, private val n: Int) {
    val startMs get() = if (n > 0) t[0] else 0L
    val endMs get() = if (n > 0) t[n - 1] else 0L

    /** Position at [utcMs], interpolated between samples. Null outside the data or across gaps. */
    fun at(utcMs: Long): Pair<Float, Float>? {
        if (n == 0 || utcMs < t[0] || utcMs > t[n - 1]) return null
        var lo = 0
        var hi = n - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (t[mid] <= utcMs) lo = mid else hi = mid
        }
        val span = t[hi] - t[lo]
        if (span > MAX_GAP_MS) return null
        val f = if (span == 0L) 0f else (utcMs - t[lo]).toFloat() / span
        return Pair(x[lo] + (x[hi] - x[lo]) * f, y[lo] + (y[hi] - y[lo]) * f)
    }

    companion object {
        private const val MAX_GAP_MS = 5_000L
    }
}

/** A driver's lap states as they changed through the session. */
class StateLog(private val t: LongArray, private val s: Array<LapState>) {
    fun at(utcMs: Long): LapState {
        var result = LapState.UNKNOWN
        for (i in t.indices) {
            if (t[i] > utcMs) break
            result = s[i]
        }
        return result
    }
}

class SessionTiming(
    val type: String,
    val drivers: Map<String, DriverInfo>,
    val tracks: Map<String, DriverTrack>,
    val states: Map<String, StateLog>,
    /** The circuit as x0,y0,x1,y1,... traced from one clean lap, starting at the line when known. */
    val outline: FloatArray,
    /** True when [outline] starts at the start/finish line. */
    val hasStartFinish: Boolean,
    val corners: List<Corner>,
    val startUtcMs: Long,
    val circuitKey: Int? = null,
    /**
     * When the session got going (UTC ms): lights out for a race, the first green otherwise.
     * Null when the archive doesn't say.
     */
    val startedUtc: Long? = null,
    /** The (last) chequered flag, UTC ms. */
    val finishedUtc: Long? = null,
    /** Each driver's sectors and lap times through the session, for the onboard timing card. */
    val laps: Map<String, DriverLaps> = emptyMap(),
    /** Team radio clips, oldest first. */
    val radio: List<RadioClip> = emptyList(),
    /** Each driver's tyres, for the timing tower. */
    val tyres: Map<String, TyreLog> = emptyMap(),
    /** Where the session's files live; a clip's address is this plus its path. Public, no login. */
    val radioBase: String = "",
) {
    val geometry = TrackGeometry(outline)

    /**
     * Where the race starts, as the fraction of a lap after the finish line (the outline's first
     * point). Found from a race's grid; for other sessions it comes from a race seen earlier at
     * the same circuit, or stays null.
     */
    var startLine: Float? = null

    val isRace get() = type.contains("Race", ignoreCase = true)

    /** Flying-lap highlights only mean something in qualifying. */
    val showsPushLaps get() = type.contains("Qualifying", ignoreCase = true)
}

/** Lap states per driver, plus every moment a car crossed the line (to locate it). */
class StateParse(val states: Map<String, StateLog>, val crossings: List<Pair<String, Long>>)

/**
 * Reads a past session from F1's live timing archive (livetiming.formula1.com/static). The archive
 * now needs the F1 TV subscription token as a Bearer header, the way FastF1 sends it.
 * File formats follow FastF1's parser: jsonStream lines are "HH:MM:SS.mmm" + JSON, and the ".z"
 * streams hold base64 raw-deflate JSON.
 */
/** Ways [LiveTimingArchive] tries: token + browser name, browser name only, F1's app's name only. */
private const val WAYS = 3
/** The name F1's own app gives F1's timing servers. */
private const val F1_APP_AGENT = "BestHTTP"

class LiveTimingArchive(private val token: String, private val userAgent: String) {

    /** [watchedUtc]: when the video on screen was broadcast, if known; finds the session by time. */
    suspend fun load(ref: SessionRef, watchedUtc: Long? = null): SessionTiming = withContext(Dispatchers.IO) {
        val (path, type) = findSession(ref, watchedUtc)
        val drivers = parseDrivers(JSONObject(fetch(path + "DriverList.json")))
        val tracks = parsePositions(fetch(path + "Position.z.jsonStream"))
        if (tracks.isEmpty()) throw IOException("The archive has no car positions for this session")

        val streamStart = parseStreamStart(fetch(path + "Heartbeat.jsonStream"))
        val timingStream = runCatching { fetch(path + "TimingData.jsonStream") }.getOrNull()
        val parsed = timingStream?.let { runCatching { parseStates(it, streamStart) }.getOrNull() }
            ?: StateParse(emptyMap(), emptyList())
        val laps = timingStream?.let { runCatching { DriverTimingParser.parse(it, streamStart) }.getOrNull() }.orEmpty()

        val built = tracks.mapValues { it.value.build() }
        val traced = traceOutline(tracks.values.toList())
        val line = findStartFinish(traced, parsed.crossings, built)
        val outline = if (line != null) startAt(traced, line) else traced
        // Turn numbers are a nice extra from a third party; the map works without them.
        val corners = ref.circuitKey?.let { runCatching { fetchCorners(it, ref.year) }.getOrNull() }.orEmpty()

        val started = runCatching { parseSessionStarted(fetch(path + "SessionStatus.jsonStream"), streamStart) }.getOrNull()
        val chequered = runCatching { parseChequered(fetch(path + "RaceControlMessages.jsonStream"), streamStart) }.getOrNull()
        val tyres = runCatching { TyreParser.parse(fetch(path + "TimingAppData.jsonStream"), streamStart) }.getOrDefault(emptyMap())
        val radio = runCatching { parseRadio(fetch(path + "TeamRadio.jsonStream")) }
            .onSuccess { android.util.Log.i("ApexviewRadio", "archive radio for $path: ${it.size} clips") }
            .onFailure { android.util.Log.w("ApexviewRadio", "archive radio for $path: ${it.message}") }
            .getOrDefault(emptyList())
        val isRace = type.contains("Race", ignoreCase = true)
        val grid = if (isRace && line != null) findGrid(TrackGeometry(outline), built, chequered) else null

        SessionTiming(
            type = type,
            drivers = drivers,
            tracks = built,
            states = parsed.states,
            outline = outline,
            hasStartFinish = line != null,
            corners = corners,
            startUtcMs = streamStart,
            circuitKey = ref.circuitKey,
            startedUtc = grid?.lightsOutUtc ?: started,
            finishedUtc = chequered,
            laps = laps,
            radio = radio,
            radioBase = BASE + path,
            tyres = tyres,
        ).also { it.startLine = grid?.startLine }
    }

    /** TeamRadio.jsonStream: each line adds Captures, as a list or as numbered entries. */
    internal fun parseRadio(stream: String): List<RadioClip> {
        val clips = mutableListOf<RadioClip>()
        stream.lineSequence().forEach { line ->
            if (line.length <= TS_LENGTH) return@forEach
            runCatching { JSONObject(line.substring(TS_LENGTH)) }.getOrNull()?.let { addRadio(it, clips) }
        }
        return clips.sortedBy { it.utc }
    }

    internal fun addRadio(json: JSONObject, into: MutableList<RadioClip>) {
        val captures = json.opt("Captures")
        val list = when (captures) {
            is org.json.JSONArray -> (0 until captures.length()).mapNotNull { captures.optJSONObject(it) }
            is JSONObject -> captures.keys().asSequence().mapNotNull { captures.optJSONObject(it) }.toList()
            else -> emptyList()
        }
        list.forEach { c ->
            val utc = c.optString("Utc").let { if (it.isNotEmpty() && !it.endsWith("Z")) it + "Z" else it }
            val time = runCatching { Instant.parse(utc).toEpochMilli() }.getOrNull() ?: return@forEach
            val path = c.optString("Path").ifEmpty { return@forEach }
            if (into.none { it.path == path }) into += RadioClip(time, c.optString("RacingNumber"), path)
        }
    }

    /** The first time the session status went to Started (for a race, the formation lap). */
    internal fun parseSessionStarted(stream: String, streamStart: Long): Long? {
        stream.lineSequence().forEach { line ->
            if (line.length <= TS_LENGTH) return@forEach
            val status = runCatching { JSONObject(line.substring(TS_LENGTH)).optString("Status") }.getOrNull()
            if (status == "Started") return streamStart + offsetMs(line)
        }
        return null
    }

    /** The last chequered flag in race control's messages. */
    internal fun parseChequered(stream: String, streamStart: Long): Long? {
        var last: Long? = null
        stream.lineSequence().forEach { line ->
            if (line.length <= TS_LENGTH) return@forEach
            val messages = runCatching { JSONObject(line.substring(TS_LENGTH)).opt("Messages") }.getOrNull() ?: return@forEach
            val list = when (messages) {
                is org.json.JSONArray -> (0 until messages.length()).map { messages.getJSONObject(it) }
                is JSONObject -> messages.keys().asSequence().mapNotNull { messages.optJSONObject(it) }.toList()
                else -> emptyList()
            }
            list.filter { it.optString("Flag") == "CHEQUERED" }.forEach { m ->
                // Utc comes with or without a zone; without one it's UTC anyway.
                val utc = m.optString("Utc").let { if (it.isNotEmpty() && !it.endsWith("Z")) it + "Z" else it }
                last = runCatching { Instant.parse(utc).toEpochMilli() }.getOrNull() ?: (streamStart + offsetMs(line))
            }
        }
        return last
    }

    internal class Grid(val startLine: Float, val lightsOutUtc: Long)

    /**
     * Finds a race's grid from the cars themselves. Before the start the field sits still near the
     * line twice: on the grid before the formation lap, then again after it, just before lights
     * out. The second stop gives both the start (its front car is on pole, the line a few metres
     * ahead) and the moment of lights out (when it ends). Stops after the chequered flag
     * ([before]) don't count: that's parc fermé.
     */
    internal fun findGrid(geometry: TrackGeometry, tracks: Map<String, DriverTrack>, before: Long?): Grid? {
        if (geometry.length == 0f || tracks.size < GRID_CARS) return null
        val from = tracks.values.minOf { it.startMs } + STILL_MS
        val to = minOf(before ?: Long.MAX_VALUE, tracks.values.maxOf { it.endMs })
        // Each stop: first moment, last moment, and its front car's lap fraction at the last moment.
        val stops = mutableListOf<Triple<Long, Long, Float>>()
        var t = from
        while (t <= to) {
            var count = 0
            var front = -Float.MAX_VALUE
            for (track in tracks.values) {
                val now = track.at(t) ?: continue
                val earlier = track.at(t - STILL_MS) ?: continue
                if (hypot(now.first - earlier.first, now.second - earlier.second) > STILL_UNITS) continue
                var s = geometry.progress(now.first, now.second)
                if (s > 0.5f) s -= 1f
                if (s < -GRID_ZONE || s > GRID_ZONE / 2) continue
                count++
                if (s > front) front = s
            }
            if (count >= GRID_CARS) {
                val last = stops.lastOrNull()
                if (last == null || t - last.second > MOVING_MS) stops += Triple(t, t, front)
                else stops[stops.lastIndex] = Triple(last.first, t, front)
            }
            // Two stops and the field long gone: the race is under way.
            if (stops.size >= 2 && t - stops[1].second > MOVING_MS * 2) break
            t += STEP_MS
        }
        val stop = stops.getOrNull(1) ?: stops.firstOrNull() ?: return null
        return Grid(
            startLine = (stop.third + POLE_TO_LINE_UNITS / geometry.length).mod(1f),
            lightsOutUtc = stop.second + STEP_MS / 2,
        )
    }

    /**
     * Where cars were when their lap count ticked over, pinned to the outline. The busiest spot
     * wins, so pit-lane crossings and timing lag don't drag it off the line.
     */
    internal fun findStartFinish(
        outline: FloatArray,
        crossings: List<Pair<String, Long>>,
        tracks: Map<String, DriverTrack>,
    ): Int? {
        if (outline.size < 8) return null
        val geometry = TrackGeometry(outline)
        val n = outline.size / 2
        val counts = IntArray(n)
        var found = 0
        crossings.forEach { (number, time) ->
            val (x, y) = tracks[number]?.at(time) ?: return@forEach
            counts[geometry.nearestIndex(x, y)]++
            found++
        }
        if (found < 5) return null
        // Busiest stretch of 7 points, then its busiest point (else ties pick the stretch's start).
        var best = 0
        var bestScore = -1L
        for (i in 0 until n) {
            var c = 0
            for (k in -3..3) c += counts[(i + k + n) % n]
            val score = c * 100_000L + counts[i]
            if (score > bestScore) { bestScore = score; best = i }
        }
        return best
    }

    /** The same loop, starting at point [index]. */
    internal fun startAt(outline: FloatArray, index: Int): FloatArray {
        val n = outline.size / 2
        return FloatArray(outline.size) { i -> outline[((i / 2 + index) % n) * 2 + i % 2] }
    }

    /**
     * Corner numbers from MultiViewer's public circuit data, which uses the same coordinates as
     * F1's position feed. Sent without the F1 token: it's a third party.
     */
    internal fun fetchCorners(circuitKey: Int, year: Int): List<Corner> = fetchCircuit(circuitKey, year)?.second.orEmpty()

    /**
     * The circuit's outline (x0,y0,x1,y1,...) and its numbered corners, from the same source.
     * The outline doesn't start at the line; a live session needs it before any car has done a
     * lap to trace one from.
     */
    internal fun fetchCircuit(circuitKey: Int, year: Int): Pair<FloatArray, List<Corner>>? {
        val conn = URL("https://api.multiviewer.app/api/v1/circuits/$circuitKey/$year").openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", userAgent)
            if (conn.responseCode >= 400) return null
            val json = JSONObject(conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
            val xs = json.optJSONArray("x")
            val ys = json.optJSONArray("y")
            val outline = if (xs != null && ys != null && xs.length() == ys.length()) {
                FloatArray(xs.length() * 2) { i -> (if (i % 2 == 0) xs else ys).optDouble(i / 2).toFloat() }
            } else FloatArray(0)
            val list = json.optJSONArray("corners")
            val corners = if (list == null) emptyList() else (0 until list.length()).mapNotNull { i ->
                val c = list.getJSONObject(i)
                val p = c.optJSONObject("trackPosition") ?: return@mapNotNull null
                Corner(c.optInt("number"), p.optDouble("x").toFloat(), p.optDouble("y").toFloat())
            }
            return outline to corners
        } finally {
            conn.disconnect()
        }
    }

    /** Finds the session's archive path by F1's session key, else by meeting and session name. */
    private fun findSession(ref: SessionRef, watchedUtc: Long?): Pair<String, String> {
        val meetings = JSONObject(fetch("${ref.year}/Index.json")).getJSONArray("Meetings")
        var byName: Pair<String, String>? = null
        var byNameLength = 0
        // The meeting's sessions with their start times, for when the name doesn't say which;
        // and its folder and time zone, to look for a session the index doesn't list yet.
        val meetingSessions = mutableListOf<Pair<Long, Pair<String, String>>>()
        var meetingFolder: String? = null
        var meetingOffset: String? = null
        for (i in 0 until meetings.length()) {
            val meeting = meetings.getJSONObject(i)
            val sameMeeting = meeting.optInt("Key") == ref.meetingKey ||
                meeting.optString("Name").equals(ref.meetingName, ignoreCase = true)
            val sessions = meeting.getJSONArray("Sessions")
            for (j in 0 until sessions.length()) {
                val session = sessions.getJSONObject(j)
                val path = session.optString("Path")
                if (path.isEmpty()) continue
                val found = path to session.optString("Type")
                if (ref.sessionKey != null && session.optInt("Key") == ref.sessionKey) return found
                if (sameMeeting) {
                    sessionStartUtc(session)?.let { meetingSessions += it to found }
                    meetingFolder = path.split('/').take(2).joinToString("/", postfix = "/")
                    meetingOffset = session.optString("GmtOffset").ifEmpty { null }
                }
                val name = session.optString("Name")
                // "Sprint Qualifying" also contains "Qualifying"; keep the longest matching name.
                if (sameMeeting && name.length > byNameLength && ref.title.contains(name, ignoreCase = true)) {
                    byName = found
                    byNameLength = name.length
                }
            }
        }
        byName?.let { return it }
        // F1 TV's live broadcasts (and their replays straight after) are titled with just the
        // event, with no session key: take the weekend's session that had started by the time
        // the video was broadcast, the latest such.
        if (watchedUtc != null) {
            fun recent(start: Long) = start <= watchedUtc + SESSION_SLACK_MS && watchedUtc - start < SESSION_SPAN_MS
            meetingSessions.filter { recent(it.first) }.maxByOrNull { it.first }?.let { return it.second }
            // Just finished: the index can lag well behind. Sessions live in predictable
            // folders, so look for that day's under the weekend's folder.
            val folder = meetingFolder
            if (folder != null) {
                val zone = parseOffset(meetingOffset ?: "00:00:00")
                val day = java.time.Instant.ofEpochMilli(watchedUtc).atOffset(zone).toLocalDate()
                SESSION_FOLDERS.mapNotNull { name ->
                    val path = "$folder${day}_$name/"
                    runCatching { JSONObject(fetch(path + "SessionInfo.json")) }.getOrNull()?.let { info ->
                        sessionStartUtc(info)?.let { it to (path to info.optString("Type")) }
                    }
                }.filter { recent(it.first) }.maxByOrNull { it.first }?.let { return it.second }
            }
        }
        throw IOException("Couldn't find \"${ref.title}\" in F1's ${ref.year} timing archive")
    }

    /** A session's start in UTC: the archive gives local time plus its offset from GMT. */
    private fun sessionStartUtc(session: JSONObject): Long? = runCatching {
        val local = java.time.LocalDateTime.parse(session.getString("StartDate"))
        local.toInstant(parseOffset(session.optString("GmtOffset", "00:00:00"))).toEpochMilli()
    }.getOrNull()

    /** "-05:00:00" to a zone offset. */
    private fun parseOffset(text: String): java.time.ZoneOffset {
        val negative = text.startsWith("-")
        val (h, m) = text.trimStart('-', '+').split(":").let { it[0].toInt() to it.getOrElse(1) { "0" }.toInt() }
        return java.time.ZoneOffset.ofHoursMinutes(if (negative) -h else h, if (negative) -m else m)
    }

    internal fun parseDrivers(json: JSONObject): Map<String, DriverInfo> {
        val result = mutableMapOf<String, DriverInfo>()
        json.keys().forEach { number ->
            val d = json.optJSONObject(number) ?: return@forEach
            val color = runCatching { Color.parseColor("#" + d.optString("TeamColour")) }.getOrDefault(Color.WHITE)
            result[number] = DriverInfo(
                number, d.optString("Tla", number), color,
                firstName = d.optString("FirstName"),
                lastName = d.optString("LastName"),
                team = d.optString("TeamName"),
            )
        }
        return result
    }

    internal fun parsePositions(stream: String): Map<String, TrackBuilder> {
        val tracks = mutableMapOf<String, TrackBuilder>()
        stream.lineSequence().forEach { line ->
            if (line.length <= TS_LENGTH) return@forEach
            val json = runCatching { JSONObject(inflate(line.substring(TS_LENGTH))) }.getOrNull() ?: return@forEach
            addPositions(json, tracks)
        }
        return tracks
    }

    /** One decoded Position message (archive line or live update) onto the cars' tracks. */
    internal fun addPositions(json: JSONObject, tracks: MutableMap<String, TrackBuilder>) {
        val samples = json.optJSONArray("Position") ?: return
        for (i in 0 until samples.length()) {
            val sample = samples.getJSONObject(i)
            val time = runCatching { Instant.parse(sample.getString("Timestamp")).toEpochMilli() }.getOrNull() ?: continue
            val entries = sample.optJSONObject("Entries") ?: continue
            entries.keys().forEach { number ->
                val e = entries.getJSONObject(number)
                if (e.optString("Status") == "OffTrack") return@forEach
                tracks.getOrPut(number) { TrackBuilder() }
                    .add(time, e.optDouble("X").toFloat(), e.optDouble("Y").toFloat())
            }
        }
    }

    /** jsonStream lines carry an offset from the stream start; the heartbeat ties it to UTC. */
    internal fun parseStreamStart(stream: String): Long {
        stream.lineSequence().forEach { line ->
            if (line.length <= TS_LENGTH) return@forEach
            val utc = runCatching { JSONObject(line.substring(TS_LENGTH)).getString("Utc") }.getOrNull() ?: return@forEach
            return Instant.parse(utc).toEpochMilli() - offsetMs(line)
        }
        throw IOException("The archive's heartbeat has no clock")
    }

    internal fun parseStates(stream: String, streamStart: Long): StateParse {
        val crossings = mutableListOf<Pair<String, Long>>()
        val times = mutableMapOf<String, MutableList<Long>>()
        val values = mutableMapOf<String, MutableList<LapState>>()
        val current = mutableMapOf<String, LapState>()
        val laps = mutableMapOf<String, Int>()

        stream.lineSequence().forEach { line ->
            if (line.length <= TS_LENGTH) return@forEach
            val lines = runCatching { JSONObject(line.substring(TS_LENGTH)).optJSONObject("Lines") }.getOrNull()
                ?: return@forEach
            val time = streamStart + offsetMs(line)
            lines.keys().forEach { number ->
                val d = lines.optJSONObject(number) ?: return@forEach
                var state = current[number] ?: LapState.UNKNOWN
                if (d.optBoolean("InPit")) state = LapState.PIT
                // Leaving the pits: F1 sends PitOut, but before a race the last run to the grid
                // can end with just InPit going false. Either way the car is out; without the
                // second case, cars that hadn't stopped yet stayed "in the pits" (dimmed) all race.
                if (d.optBoolean("PitOut") || (d.has("InPit") && !d.optBoolean("InPit") && state == LapState.PIT)) {
                    state = LapState.OUT_LAP
                }
                if (d.has("NumberOfLaps")) {
                    val n = d.optInt("NumberOfLaps")
                    val before = laps[number]
                    laps[number] = n
                    // Crossing the line: an out lap becomes a push lap, push and cool-down alternate.
                    if (before != null && n > before) {
                        crossings += number to time
                        state = when (state) {
                            LapState.OUT_LAP, LapState.COOL_DOWN -> LapState.PUSH
                            LapState.PUSH -> LapState.COOL_DOWN
                            else -> state
                        }
                    }
                }
                if (d.optBoolean("KnockedOut")) state = LapState.KNOCKED_OUT
                if (state != current[number]) {
                    current[number] = state
                    times.getOrPut(number) { mutableListOf() }.add(time)
                    values.getOrPut(number) { mutableListOf() }.add(state)
                }
            }
        }
        return StateParse(
            states = times.mapValues { (number, t) -> StateLog(t.toLongArray(), values.getValue(number).toTypedArray()) },
            crossings = crossings,
        )
    }

    /**
     * Traces the circuit from one lap: from a point on track, follow a car until it comes back
     * to the same spot. The shortest such loop across a few cars is a clean racing line, not a
     * trip through the pit lane.
     */
    internal fun traceOutline(tracks: List<TrackBuilder>): FloatArray {
        var best: FloatArray? = null
        var bestTime = Long.MAX_VALUE
        for (track in tracks.sortedByDescending { it.size }.take(4)) {
            for (k in 1..9) {
                val start = track.size * k / 10
                val loop = track.loopFrom(start) ?: continue
                if (loop.second < bestTime) {
                    bestTime = loop.second
                    best = loop.first
                }
            }
        }
        return best ?: FloatArray(0)
    }

    /**
     * How the archive answers: with the token and this device's browser name first, as FastF1
     * does. F1's servers turn some devices and networks away with a 403 (a Chromecast, for one,
     * and some cloud networks), though most of the archive doesn't need a login, so on a 403 it
     * tries again without the token, then as F1's own app. Whichever works is kept for the rest.
     */
    private var way: Int = 0

    private fun fetch(path: String): String {
        var last: IOException? = null
        for (attempt in way until WAYS) {
            try {
                return fetchAs(path, attempt).also { way = attempt }
            } catch (e: Refused) {
                last = e
            }
        }
        throw last ?: IOException("F1's timing archive refused the request")
    }

    private class Refused(code: Int) : IOException("F1's timing archive turned this device away (HTTP $code)")

    private fun fetchAs(path: String, attempt: Int): String {
        val conn = URL(BASE + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.setRequestProperty("User-Agent", if (attempt >= 2) F1_APP_AGENT else userAgent)
            if (attempt == 0) conn.setRequestProperty("Authorization", "Bearer $token")
            val code = conn.responseCode
            if (code == 401 || code == 403) throw Refused(code)
            if (code >= 400) throw IOException("HTTP $code for ${path.substringAfterLast('/')}")
            return conn.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8).removePrefix("\uFEFF")
        } finally {
            conn.disconnect()
        }
    }

    internal fun inflate(quoted: String): String {
        val bytes = Base64.getDecoder().decode(quoted.trim().trim('"'))
        val inflater = Inflater(true)
        try {
            inflater.setInput(bytes)
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            return out.toString("UTF-8").removePrefix("﻿")
        } finally {
            inflater.end()
        }
    }

    /** "HH:MM:SS.mmm" at the start of a jsonStream line, in ms. */
    private fun offsetMs(line: String): Long {
        val h = line.substring(0, 2).toLong()
        val m = line.substring(3, 5).toLong()
        val s = line.substring(6, 8).toLong()
        val ms = line.substring(9, 12).toLong()
        return ((h * 60 + m) * 60 + s) * 1000 + ms
    }

    companion object {
        internal const val BASE = "https://livetiming.formula1.com/static/"
        private const val TS_LENGTH = 12
        /** A video may start a little before its session officially does (build-up). */
        private const val SESSION_SLACK_MS = 30 * 60_000L
        /** A session plus its build-up and aftermath: a video this long after a start still belongs to it. */
        private const val SESSION_SPAN_MS = 4 * 3_600_000L
        /** How F1 names session folders, for finding one the index doesn't list yet. */
        private val SESSION_FOLDERS = listOf(
            "Race", "Sprint", "Qualifying", "Sprint_Qualifying", "Sprint_Shootout",
            "Practice_1", "Practice_2", "Practice_3",
        )
        // Grid search: sample every second; a car is still if it moved under 3 m in 4 s; the
        // grid sits within 12% of a lap before the line; at least 12 cars make a grid; stops
        // closer than a minute apart are one stop; pole sits about 5 m behind the line.
        private const val STEP_MS = 1_000L
        private const val STILL_MS = 4_000L
        private const val STILL_UNITS = 30f
        private const val GRID_ZONE = 0.12f
        private const val GRID_CARS = 12
        private const val MOVING_MS = 60_000L
        private const val POLE_TO_LINE_UNITS = 50f
    }
}

/** Grows one car's samples, then freezes them into a [DriverTrack]. */
class TrackBuilder {
    private var t = LongArray(4096)
    private var x = FloatArray(4096)
    private var y = FloatArray(4096)
    var size = 0
        private set

    fun add(time: Long, px: Float, py: Float) {
        // Samples can repeat or arrive slightly out of order; keep time increasing.
        if (size > 0 && time <= t[size - 1]) return
        if (size == t.size) {
            t = t.copyOf(size * 2)
            x = x.copyOf(size * 2)
            y = y.copyOf(size * 2)
        }
        t[size] = time
        x[size] = px
        y[size] = py
        size++
    }

    fun build() = DriverTrack(t, x, y, size)

    /** The points of one lap starting at [start] and its duration, or null if it doesn't close. */
    fun loopFrom(start: Int): Pair<FloatArray, Long>? {
        var travelled = 0f
        for (i in start + 1 until size) {
            if (t[i] - t[i - 1] > 2_000) return null
            travelled += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
            if (travelled > 120_000) return null
            if (travelled > 20_000 && hypot(x[i] - x[start], y[i] - y[start]) < 300f) {
                val points = FloatArray((i - start + 1) * 2)
                for (k in start..i) {
                    points[(k - start) * 2] = x[k]
                    points[(k - start) * 2 + 1] = y[k]
                }
                return points to (t[i] - t[start])
            }
        }
        return null
    }
}

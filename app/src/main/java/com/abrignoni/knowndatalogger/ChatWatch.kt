package com.abrignoni.knowndatalogger

/**
 * One line of text on the screen, with its box in screen pixels. [inList] is false for text outside the
 * screen's message list (the title bar's "online" or "typing", the message box): it is reported with
 * the same flag, so it can be logged but is never labelled a received message.
 */
data class ShownText(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int,
                     val inList: Boolean = true, val leaf: Boolean = true)

/** A line that came onto an open screen, which side of the screen it sits on, and whether it is in the message list. */
data class Appeared(val text: String, val side: String, val inList: Boolean = true) {
    /** Where the line sat and whether its node had no children (for author rules); not part of equality. */
    var top: Int = 0
    var leaf: Boolean = true
}

/**
 * Finds text that arrives on a screen that is already open: an incoming message in a chat the
 * examiner is looking at. Apps such as Messenger post no notification then (Galaxy A16 5G and moto
 * g play, Android 16, 15 November 2026: 1 of 15 received messages per phone was logged), so the
 * notification path alone misses them.
 *
 * The first look at a screen is the baseline and reports nothing: opening a chat is not receiving
 * its history. After that, a line is new when the screen holds more copies of its text than it has
 * held so far (so a second "ok" counts), and it is not above all of the content already seen: lines
 * revealed by scrolling up come in above everything that was there, while a new message comes in below
 * some of it. ("Below everything" was too strict: on a Galaxy A16 5G in Messenger something already
 * seen, likely the read-receipt avatar, sat below each new message, and 0 of 5 were found.) Lines still
 * exactly where they were at the baseline are the screen's frame (the title bar, the message box and
 * its buttons) and do not count as content. Pure logic, testable without a phone.
 */
class ChatWatch {
    private var screen: Any? = null
    private val most = HashMap<String, Int>()
    /** Text and top of every line at the baseline. */
    private val frame = HashSet<Pair<String, Int>>()
    /** How many times each text showed on the last look. */
    private var last: Map<String, Int> = emptyMap()
    /** Whether the baseline was taken with the list at its newest end (or with no list to ask). */
    private var baselineAtNewest = true
    /** When the screen was last looked at (the [now] of the last look; null when no time was given). */
    private var lastAt: Long? = null

    /** The screen held just before this one, as it was when it was left, and when that was. */
    private class Held(val screen: Any, val most: Map<String, Int>, val frame: Set<Pair<String, Int>>,
                       val last: Map<String, Int>, val atNewest: Boolean, val leftAt: Long)
    private var before: Held? = null

    /**
     * Left edges of lines that sat clearly on the left, and right edges of lines clearly on the right, per screen
     * key, kept across baselines and screens (bubble layout belongs to the app, not to one look). Read by [sideAmong].
     */
    private val edges = LinkedHashMap<Any, Pair<HashSet<Int>, HashSet<Int>>>()

    /** True when the last look went back to the screen held just before, instead of taking a new baseline. */
    var lastLookReturned = false
        private set

    /** Forget the screen: the next look is a baseline. */
    fun reset() {
        screen = null; most.clear(); frame.clear(); last = emptyMap(); baselineAtNewest = true; lastAt = null; before = null
        edges.clear()
    }

    /** Remembers the edges of this look's lines that sit clearly on one side; returns the screen's edges. */
    private fun learnEdges(screenKey: Any, lines: List<ShownText>, width: Int): Pair<HashSet<Int>, HashSet<Int>> {
        val e = edges.getOrPut(screenKey) { HashSet<Int>() to HashSet<Int>() }
        if (edges.size > 50) edges.remove(edges.keys.first())
        for (l in lines) if (l.inList) when (sideOf(l, width)) {
            "left" -> e.first += l.left
            "right" -> e.second += l.right
        }
        return e
    }

    /**
     * [atNewest] false means the screen's message list can still scroll toward newer messages: the examiner
     * is reading history, so nothing coming into view is arriving. Such a look reports nothing and learns
     * nothing, so a message that arrived meanwhile is still new when the list is back at its end. (WhatsApp,
     * Galaxy A16 5G and moto g play, 15 November 2026: older messages scrolled back into view were labelled
     * received, with and without the 3-second look.) Null when the screen has no list to ask.
     */
    fun look(screenKey: Any, lines: List<ShownText>, width: Int, atNewest: Boolean? = null, now: Long? = null): List<Appeared> {
        val counts = lines.groupingBy { it.text }.eachCount()
        lastLookReturned = false
        val (leftEdges, rightEdges) = learnEdges(screenKey, lines, width)
        if (screenKey == screen && atNewest == false) { lastAt = now ?: lastAt; return emptyList() }
        // Back on the screen held just before, within RETURN_MS of leaving it (a chat, after the picker that sends a
        // picture from it): what came in meanwhile is compared with the screen as it was left, not taken as a new
        // baseline. (Telegram 13.2.2, Galaxy A16 5G, 15 November 2026: the picture this phone sent was already in
        // the chat on the first look after the picker closed, became part of a new baseline and was never logged;
        // on the moto g play it came in a second later and was.) The list must be at its newest end.
        val held = before
        if (screenKey != screen && held != null && held.screen == screenKey && now != null && atNewest != false &&
            held.atNewest && now - held.leftAt in 0..RETURN_MS) {
            leave(now)
            screen = held.screen; most.putAll(held.most); frame += held.frame; last = held.last
            baselineAtNewest = held.atNewest
            lastLookReturned = true
        }
        // A baseline taken away from the newest end (a chat reopened where it was left, scrolled up) holds old
        // messages that the jump to the end then brings in below it: take the baseline again there. (Discord,
        // moto g play, 15 November 2026: 4 older messages were logged as new when the list jumped to its end.)
        if (screenKey != screen || (atNewest == true && !baselineAtNewest)) {
            if (screenKey != screen) leave(now) else { most.clear(); frame.clear(); last = emptyMap() }
            baselineAtNewest = atNewest != false
            screen = screenKey
            counts.forEach { (t, n) -> most[t] = n }
            lines.forEach { frame += it.text to it.top }
            last = counts
            lastAt = now
            return emptyList()
        }
        lastAt = now ?: lastAt
        // Lines whose text was already seen as often as it shows now, outside the frame: the old content.
        val oldTopmost = lines.filter { (most[it.text] ?: 0) >= (counts[it.text] ?: 0) && (it.text to it.top) !in frame }
            .minOfOrNull { it.top }
        // Lines of the last look are gone and nothing that stayed has moved (only the frame is left): the screen
        // jumped (a fast scroll), and what came in is not arriving. An arrival either moves what was there or
        // leaves it all in place; the first message in an empty chat removes nothing. (Telegram, Galaxy A16 5G,
        // 15 November 2026: one old message was labelled after a scroll.)
        val gone = last.any { (t, n) -> (counts[t] ?: 0) < n }
        val stayedAndMoved = lines.any { (last[it.text] ?: 0) > 0 && (it.text to it.top) !in frame }
        val jumped = gone && !stayedAndMoved
        last = counts
        val out = ArrayList<Appeared>()
        val extra = HashMap<String, Int>()
        for ((t, n) in counts) {
            val had = most[t] ?: 0
            if (n > had) extra[t] = n - had
        }
        // The extra copies of a text are its lowest ones on the screen.
        for (line in lines.sortedBy { it.top }.asReversed()) {
            val left = extra[line.text] ?: 0
            if (left <= 0) continue
            if (jumped || (oldTopmost != null && line.top < oldTopmost)) {
                extra[line.text] = 0      // above all old content: revealed by a scroll, not arrived
                continue
            }
            // Nothing moved, so every line seen before counts as frame and there is no old content to be above.
            // A chat adds a message at the bottom; a line with more than two lines already seen below it came in
            // somewhere else, such as the title bar. (Messenger, moto g play, 15 November 2026: "Active now"
            // came into the title bar above five old messages that had not moved, and was labelled received.)
            if (oldTopmost == null && lines.count { (most[it.text] ?: 0) > 0 && it.top >= line.bottom } > 2) {
                extra[line.text] = 0
                continue
            }
            extra[line.text] = left - 1
            out += Appeared(line.text, sideAmong(line, width, leftEdges, rightEdges), line.inList).also { it.top = line.top; it.leaf = line.leaf }
        }
        counts.forEach { (t, n) -> if (n > (most[t] ?: 0)) most[t] = n }
        return out.asReversed()
    }

    /** Keeps the screen being left as the one held before, and clears the state for the next. */
    private fun leave(now: Long?) {
        val cur = screen
        val at = lastAt ?: now
        before = if (cur != null && at != null) Held(cur, HashMap(most), HashSet(frame), last, baselineAtNewest, at) else null
        screen = null; most.clear(); frame.clear(); last = emptyMap(); lastAt = null
    }

    companion object {
        /** How long after leaving a screen a return to it still compares with it as it was left. */
        const val RETURN_MS = 60_000L

        /** The author shown nearest above [top]: the last author line whose top is at or above it. */
        fun authorAbove(top: Int, authors: List<Pair<String, Int>>): String? =
            authors.filter { it.second <= top }.maxByOrNull { it.second }?.first

        private val timeOrDate = Regex(
            "(?i)(\\d{1,2}:\\d{2}(\\s*[ap]\\.?\\s*m\\.?)?|today|yesterday|now|just now|" +
            "\\d{1,2}\\s*(m|min|mins|h|hr|hrs)( ago)?|" +
            "(monday|tuesday|wednesday|thursday|friday|saturday|sunday)|" +
            "(january|february|march|april|may|june|july|august|september|october|november|december) \\d{1,2}(, \\d{4})?)")

        /**
         * A line that is only a clock time, a relative time or a date ("8:21 AM", "Now", "5 min", "Today",
         * "November 15"): a message's time or a day divider, never a message itself. It is still logged as text
         * that appeared, but not labelled as a received message. (Moto g play, 15 November 2026: a received bubble's
         * time was labelled in WhatsApp, and Signal's "Now" under a new message.)
         */
        private val status = Regex("(?i)(downloading|uploading|sending|loading|connecting|waiting for network|" +
            "tap to (download|load|retry)|failed to send|not delivered|saved|read|" +
            "tap and hold to [a-z ]+(\\. try it)?|" +
            "seen([\\s\u202f\u00a0]+(just now|\\d{1,2}:\\d{2}[\\s\u202f\u00a0]*([ap]m)?|\\d+[\\s\u202f\u00a0]*(m|h|d|min|mins|hr|hrs)( ago)?))?)" +
            "\\s*(\\.\\.\\.|…)?")

        /**
         * A line that is only a passing status ("Downloading", "Sending…"), shown while a message or picture loads.
         * It is logged as text that appeared, never labelled. (Messenger, moto g play, 15 November 2026:
         * "Downloading" showed on the left while a received photo loaded and was labelled received. Snapchat
         * 14.32.0.54, moto g play, 15 November 2026: "SAVED" slid in beside a received picture saved in the chat,
         * under the other side's author line, and was labelled received. LINE, Galaxy A16 5G and moto g play,
         * 16 November 2026: the read receipt "Read" beside a sent message was labelled received, 4 times in 10 sends.
         * Instagram 455.0.0.51.72, same phones, 16 November 2026: the hints "Tap and hold to react" and "Tap and hold
         * to imagine with AI. Try it" showed under a message and were labelled received, once on each phone. Threads
         * 455.0.0.55.72, same phones, 16 November 2026: the receipt "Seen 6:40 AM" under a sent message sat on the left
         * and was labelled received, 3 times in 5 sends on each phone.)
         */
        fun isStatusOnly(text: String): Boolean = status.matches(text.trim())

        fun isTimeOrDate(text: String): Boolean =
            timeOrDate.matches(text.replace('\u202f', ' ').replace('\u00a0', ' ').trim())

        /** left, right or across, from where the box sits across the screen (the same rule as AppWalk). */
        fun sideOf(l: ShownText, width: Int): String {
            if (width <= 0) return ""
            val leftGap = l.left.toDouble() / width
            val rightGap = (width - l.right).toDouble() / width
            if ((leftGap < 0.04 && rightGap < 0.04) || kotlin.math.abs(leftGap - rightGap) < 0.02) return "across"
            return if (leftGap < rightGap) "left" else "right"
        }

        /**
         * The side of a line, read against the other lines of the same look. A line whose gaps to the two edges are
         * nearly equal reads "across", but a bubble lines up with the other bubbles on its side: when its left edge
         * matches a line seen clearly on the left (or its right edge one clearly on the right), and not both, it takes
         * that side. The edges are those seen on the same screen so far, this look included, because every received
         * bubble on screen can be a wide one. (Instagram 455.0.0.51.72, Galaxy A16 5G, 16 November 2026: received
         * bubbles start 158 px in, after the avatar, and two-line ones ended at 908 to 924 of 1080, leaving almost
         * the same gap on the right; they read across and were not labelled, 1 of 5 received in two rounds.)
         */
        fun sideAmong(l: ShownText, width: Int, leftEdges: Set<Int>, rightEdges: Set<Int>): String {
            val s = sideOf(l, width)
            if (s != "across" || !l.inList) return s
            val asLeft = leftEdges.any { kotlin.math.abs(it - l.left) <= 2 }
            val asRight = rightEdges.any { kotlin.math.abs(it - l.right) <= 2 }
            return when {
                asLeft && !asRight -> "left"
                asRight && !asLeft -> "right"
                else -> s
            }
        }
    }
}

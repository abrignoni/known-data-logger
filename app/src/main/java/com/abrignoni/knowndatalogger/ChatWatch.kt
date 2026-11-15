package com.abrignoni.knowndatalogger

/**
 * One line of text on the screen, with its box in screen pixels. [inList] is false for text outside the
 * screen's message list (the title bar's "online" or "typing", the message box): it is reported with
 * the same flag, so it can be logged but is never labelled a received message.
 */
data class ShownText(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int,
                     val inList: Boolean = true)

/** A line that came onto an open screen, which side of the screen it sits on, and whether it is in the message list. */
data class Appeared(val text: String, val side: String, val inList: Boolean = true)

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

    /** Forget the screen: the next look is a baseline. */
    fun reset() { screen = null; most.clear(); frame.clear(); last = emptyMap() }

    /**
     * [atNewest] false means the screen's message list can still scroll toward newer messages: the examiner
     * is reading history, so nothing coming into view is arriving. Such a look reports nothing and learns
     * nothing, so a message that arrived meanwhile is still new when the list is back at its end. (WhatsApp,
     * Galaxy A16 5G and moto g play, 15 November 2026: older messages scrolled back into view were labelled
     * received, with and without the 3-second look.) Null when the screen has no list to ask.
     */
    fun look(screenKey: Any, lines: List<ShownText>, width: Int, atNewest: Boolean? = null): List<Appeared> {
        val counts = lines.groupingBy { it.text }.eachCount()
        if (screenKey == screen && atNewest == false) return emptyList()
        if (screenKey != screen) {
            reset()
            screen = screenKey
            counts.forEach { (t, n) -> most[t] = n }
            lines.forEach { frame += it.text to it.top }
            last = counts
            return emptyList()
        }
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
            extra[line.text] = left - 1
            out += Appeared(line.text, sideOf(line, width), line.inList)
        }
        counts.forEach { (t, n) -> if (n > (most[t] ?: 0)) most[t] = n }
        return out.asReversed()
    }

    companion object {
        private val timeOrDate = Regex(
            "(?i)(\\d{1,2}:\\d{2}(\\s*[ap]\\.?\\s*m\\.?)?|today|yesterday|" +
            "(monday|tuesday|wednesday|thursday|friday|saturday|sunday)|" +
            "(january|february|march|april|may|june|july|august|september|october|november|december) \\d{1,2}(, \\d{4})?)")

        /**
         * A line that is only a clock time or a date ("8:21 AM", "Today", "November 15"): a message's time or a
         * day divider, never a message itself. It is still logged as text that appeared, but not labelled as a
         * received message. (WhatsApp, moto g play, 15 November 2026: a received bubble's time was labelled.)
         */
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
    }
}

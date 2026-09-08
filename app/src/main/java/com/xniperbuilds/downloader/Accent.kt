package com.xniperbuilds.downloader

import android.content.Context
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color

/**
 * The accent colours a streak unlocks.
 *
 * Deliberately small: an accent is a colour, not a re-skin. It is applied to the two things that
 * are on screen the whole time — the **Download button** and the **selected quality chip** — plus
 * the streak card's own buttons. A downloader is a thirty-second app; a full theme engine would be
 * a lot of surface area for a reward nobody spends long looking at.
 *
 * ⚠️ But it has to be applied to something the user can actually SEE, or the reward is a lie.
 * The first cut of this only tinted the streak card's own buttons — and both of those disappear
 * in normal use (the Check in button after checking in, the ad-free button during the window), so
 * a user who unlocked "Ocean" would usually see no change at all. "Unlock Ocean" has to change
 * something, or it should not be offered.
 *
 * [id] doubles as the streak milestone that unlocks it, so [Streak.THEME_MILESTONES] and this list
 * never need to be kept in sync by hand. `id = 0` is the stock look and is always available — its
 * colours are the exact ones the app shipped with, so nobody who ignores this feature sees any
 * change whatsoever.
 */
data class Accent(
    val id: Int,
    val name: String,
    val primary: Color,
    val onPrimary: Color,
    /** The dark end of the Download button's gradient. */
    val gradientEnd: Color,
) {
    companion object {
        val DEFAULT = Accent(
            0, "Riplox",
            primary = Color(0xFF45587A), onPrimary = Color(0xFFE9EFFA),
            gradientEnd = Color(0xFF141C2B),
        )

        /** Ordered by the streak needed. Ids match [Streak.THEME_MILESTONES]. */
        val ALL = listOf(
            DEFAULT,
            Accent(3, "Ocean", Color(0xFF1E7A8C), Color(0xFFE6F7FA), Color(0xFF0B2A31)),
            Accent(7, "Neon", Color(0xFF7A3EF0), Color(0xFFF1EAFE), Color(0xFF1E1235)),
            Accent(14, "Ember", Color(0xFFC2482B), Color(0xFFFDECE7), Color(0xFF33150D)),
            Accent(30, "Aurora", Color(0xFF1F9E6B), Color(0xFFE8FAF2), Color(0xFF0B2E20)),
        )

        fun byId(id: Int): Accent = ALL.firstOrNull { it.id == id } ?: DEFAULT

        /**
         * The accent in use right now, as Compose STATE.
         *
         * ⚠️ It has to be state rather than a plain `Prefs` read: the picker lives inside the
         * streak card, but the Download button is somewhere else entirely. With a plain read,
         * choosing a colour would repaint the card and leave the rest of the screen on the old
         * one until the app was reopened. Reading this subscribes the caller, so everything
         * changes at once.
         */
        private val currentId = mutableStateOf(UNSET)
        private const val UNSET = -1

        fun current(context: Context): Accent {
            if (currentId.value == UNSET) currentId.value = Prefs.accent(context)
            return byId(currentId.value)
        }

        /** Pick an accent: persisted, and pushed to every composable reading [current]. */
        fun select(context: Context, id: Int) {
            Prefs.setAccent(context, id)
            currentId.value = id
        }

        /**
         * Accents the user may actually pick: the default, everything their streak has earned,
         * and anything they unlocked early with a rewarded ad.
         */
        fun available(streak: Int, bought: Set<Int>): List<Accent> {
            val earned = Streak.unlockedBy(streak).toSet() + bought
            return ALL.filter { it.id == 0 || it.id in earned }
        }

        /** The next accent still locked, or null when they are all unlocked. */
        fun nextLocked(streak: Int, bought: Set<Int>): Accent? {
            val have = Streak.unlockedBy(streak).toSet() + bought
            return ALL.firstOrNull { it.id != 0 && it.id !in have }
        }
    }
}

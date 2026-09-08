package com.xniperbuilds.downloader

import androidx.compose.ui.graphics.Color

/**
 * The accent colours a streak unlocks.
 *
 * Deliberately small: an accent is a colour, not a re-skin. It is applied to the things the
 * eye actually lands on — the Download button, the selected quality chip, the streak card —
 * and nothing else. A downloader is a thirty-second app; a full theme engine would be a lot
 * of surface area for a reward nobody spends long looking at.
 *
 * [id] doubles as the streak milestone that unlocks it, so `unlockedBy(streak)` in [Streak]
 * and this list never need to be kept in sync by hand. `id = 0` is the stock look and is
 * always available.
 */
data class Accent(
    val id: Int,
    val name: String,
    val primary: Color,
    val onPrimary: Color,
) {
    companion object {
        val DEFAULT = Accent(0, "Riplox", Color(0xFF3E5A8A), Color(0xFFE9EFFA))

        /** Ordered by the streak needed. Ids match [Streak.THEME_MILESTONES]. */
        val ALL = listOf(
            DEFAULT,
            Accent(3, "Ocean", Color(0xFF1E7A8C), Color(0xFFE6F7FA)),
            Accent(7, "Neon", Color(0xFF7A3EF0), Color(0xFFF1EAFE)),
            Accent(14, "Ember", Color(0xFFC2482B), Color(0xFFFDECE7)),
            Accent(30, "Aurora", Color(0xFF1F9E6B), Color(0xFFE8FAF2)),
        )

        fun byId(id: Int): Accent = ALL.firstOrNull { it.id == id } ?: DEFAULT

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

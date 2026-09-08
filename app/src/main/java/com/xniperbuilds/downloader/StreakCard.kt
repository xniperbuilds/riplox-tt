package com.xniperbuilds.downloader

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private fun dayString(offsetDays: Int = 0): String {
    val c = Calendar.getInstance()
    c.add(Calendar.DAY_OF_YEAR, offsetDays)
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.time)
}

private fun prettyRemaining(ms: Long): String {
    val mins = (ms / 60_000L).toInt()
    val h = mins / 60
    val m = mins % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}

/**
 * The daily check-in card: streak, the ad-free window, and accent unlocks.
 *
 * ## Two rules this UI must never break
 *
 * 1. **Nothing that was free has been fenced off.** Every quality, MP3, batch paste and the
 *    optional login work exactly as before, with or without a streak. Everything here is new.
 * 2. **Every rewarded button names what it gives.** AdMob's policy calls out the opposite by
 *    name — a button must not "mislead or incentivize users towards a particular choice (such
 *    as by indicating 'watch this ad to support our business')". So the labels below are
 *    "2 hours with no ads" and "Unlock Ocean", never "support us".
 *
 * The button is also hidden outright unless a rewarded ad is actually in hand
 * ([Ads.rewardedReady]), because a reward we cannot deliver must not be offered.
 */
@Composable
fun StreakCard(activity: Activity, onAdsChanged: () -> Unit = {}) {
    val context = LocalContext.current

    var streak by remember { mutableStateOf(Prefs.streakDays(context)) }
    var lastDay by remember { mutableStateOf(Prefs.lastCheckIn(context)) }
    var adFreeUntil by remember { mutableStateOf(Prefs.adFreeUntil(context)) }
    var bought by remember { mutableStateOf(Prefs.boughtAccents(context)) }
    var busy by remember { mutableStateOf(false) }
    // Bumped after any reward so the remaining-time line and the banner both recompose.
    var tick by remember { mutableStateOf(0) }

    val now = System.currentTimeMillis()
    val checkedIn = Streak.isCheckedIn(lastDay, dayString())
    val adFree = Streak.adFree(adFreeUntil, now)
    val accent = Accent.current(context)
    val nextLocked = Accent.nextLocked(streak, bought)
    val toNext = Streak.daysToNextMilestone(streak)

    @Suppress("UNUSED_EXPRESSION") tick // read so the compiler keeps the dependency

    fun refresh() {
        val wasAdFree = Streak.adFree(adFreeUntil, System.currentTimeMillis())
        streak = Prefs.streakDays(context)
        lastDay = Prefs.lastCheckIn(context)
        adFreeUntil = Prefs.adFreeUntil(context)
        bought = Prefs.boughtAccents(context)
        tick++
        // ⚠️ Only tell the host when the ad-free window actually FLIPPED. It rebuilds the
        // banner slot, which throws away a loaded AdView and costs a fresh request — and a
        // plain check-in does not change whether ads may show. Asking for an ad we did not
        // need is the exact defect this release exists to remove (see AdGate).
        if (Streak.adFree(adFreeUntil, System.currentTimeMillis()) != wasAdFree) onAdsChanged()
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF141B29)),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("🔥", fontSize = 18.sp)
                Spacer(Modifier.width(8.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        if (streak > 0) "$streak day streak" else "Start a streak",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFFDDE4EF)
                    )
                    Text(
                        when {
                            adFree -> "Ad-free for ${prettyRemaining(Streak.adFreeRemaining(adFreeUntil, now))}"
                            nextLocked != null && toNext != null ->
                                "$toNext more ${if (toNext == 1) "day" else "days"} unlocks ${nextLocked.name}"
                            else -> "Every accent unlocked"
                        },
                        fontSize = 12.sp,
                        color = Color(0xFF9AA6B8)
                    )
                }
                if (!checkedIn) {
                    Button(
                        onClick = {
                            val prev = Prefs.streakDays(context)
                            val next = Streak.nextStreak(
                                lastCheckIn = Prefs.lastCheckIn(context),
                                today = dayString(),
                                yesterday = dayString(-1),
                                current = prev
                            )
                            Prefs.setCheckIn(
                                context, dayString(), next,
                                maxOf(Prefs.bestStreak(context), next)
                            )
                            // The 7-day milestone pays a full ad-free day, with no ad to watch.
                            if (Streak.grantsFreeDay(next, prev)) {
                                Prefs.setAdFreeUntil(
                                    context,
                                    Streak.extendAdFree(
                                        Prefs.adFreeUntil(context),
                                        System.currentTimeMillis(),
                                        Streak.AD_FREE_CAP_MS
                                    )
                                )
                            }
                            refresh()
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accent.primary, contentColor = accent.onPrimary
                        )
                    ) { Text("Check in", fontSize = 13.sp) }
                } else {
                    Text("✓ today", fontSize = 12.sp, color = Color(0xFF6FBF9B))
                }
            }

            // ---- Rewarded: more ad-free time ----
            if (Ads.rewardedConfigured && Ads.rewardedReady() && !adFree) {
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        Ads.showRewarded(
                            activity,
                            onReward = {
                                Prefs.setAdFreeUntil(
                                    context,
                                    Streak.extendAdFree(
                                        Prefs.adFreeUntil(context),
                                        System.currentTimeMillis(),
                                        Streak.AD_FREE_MS
                                    )
                                )
                            },
                            onFinished = { busy = false; refresh() }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = accent.primary, contentColor = accent.onPrimary
                    )
                ) { Text("Watch a short ad → 2 hours with no ads", fontSize = 13.sp) }
            }

            // ---- Rewarded: unlock the next accent early ----
            if (Ads.rewardedConfigured && Ads.rewardedReady() && nextLocked != null) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (busy) return@Button
                        busy = true
                        Ads.showRewarded(
                            activity,
                            onReward = { Prefs.addBoughtAccent(context, nextLocked.id) },
                            onFinished = { busy = false; refresh() }
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1E2637), contentColor = Color(0xFFDDE4EF)
                    )
                ) { Text("Watch a short ad → unlock ${nextLocked.name}", fontSize = 13.sp) }
            }

            // ---- Accent picker: only what has actually been unlocked ----
            val available = Accent.available(streak, bought)
            if (available.size > 1) {
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    available.forEach { a ->
                        Box(
                            modifier = Modifier
                                .size(if (a.id == accent.id) 28.dp else 24.dp)
                                .clip(CircleShape)
                                .background(a.primary)
                                .clickable { Accent.select(context, a.id) },
                            contentAlignment = Alignment.Center
                        ) {
                            if (a.id == accent.id) {
                                Text("✓", fontSize = 12.sp, color = a.onPrimary)
                            }
                        }
                    }
                }
            }
        }
    }
}

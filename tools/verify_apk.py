"""Pre-release gate for the Riplox TT GitHub APK.

Same idea as verify_aab.py, but for the sideload APK that goes on the GitHub release.
An AAB passing does NOT prove the APK is right: the APK is a separate packaging step,
it is signed with OUR keystore (not Play's), and it is the only build a GitHub user
ever installs.

An APK's manifest is binary AXML, so manifest facts come from `aapt2 dump badging`
(passed in as argv[2] on a file) rather than a byte scan. The dex is scanned raw,
exactly like the AAB gate.

Run:
    set PYTHONIOENCODING=utf-8
    aapt2 dump badging app-release.apk > badging.txt
    python tools\\verify_apk.py app-release.apk badging.txt

Exit code 0 = safe to publish, 1 = do NOT publish.
Bump VERSION / PREV_VERSION on every release. ASCII-only output (cp1252-safe console).
"""
import sys
import zipfile

APK = sys.argv[1] if len(sys.argv) > 1 else ""
BADGING = sys.argv[2] if len(sys.argv) > 2 else ""

# --- per-release settings: BUMP THESE ---------------------------------------
VERSION = "1.2.0"       # the versionName that MUST be in this APK
PREV_VERSION = "1.1.5"  # the previous one, which must NOT be (stale-build guard)
VERSION_CODE = "8"

# --- AdMob (record: .xniper-secrets\admob-riploxtt.txt) ---------------------
REAL_BANNER = b"ca-app-pub-8029174313177489/7721461931"
REAL_INTER = b"ca-app-pub-8029174313177489/8209115310"
TEST_MARK = b"3940256099942544"  # Google's test-ad account - forbidden in a release build

# Strings that must appear in the badging dump (manifest facts)
WANT_BADGING = [
    ("package: name='com.xniperbuilds.riploxtt'", "applicationId"),
    ("versionCode='%s'" % VERSION_CODE, "versionCode"),
    ("versionName='%s'" % VERSION, "versionName"),
    ("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "battery permission"),
    ("android.permission.FOREGROUND_SERVICE_DATA_SYNC", "FGS dataSync permission"),
]

WANT_DEX = [
    (REAL_BANNER, "real banner id"),
    (REAL_INTER, "real interstitial id"),
    (b"TikTokExtractor", "TikTok extractor"),
    # The Worker URL and its header come from local.properties. A build made on a machine
    # without them compiles and installs fine, then quietly falls back to the on-device
    # routes - which return TikTok's WATERMARKED rendition. Caught here, not by a user.
    (b"riplox-tt-extract", "extractor Worker URL"),
    (b"X-Riplox-App", "Worker app-token header"),
    (b"CookieLoginActivity", "Connect TikTok activity"),
    (b"Engine", "engine module (v1.1.5)"),
]


def main():
    if not APK or not BADGING:
        print("usage: verify_apk.py <path-to.apk> <path-to-badging.txt>")
        return 2

    z = zipfile.ZipFile(APK)
    names = z.namelist()
    dex = b"".join(z.read(n) for n in names if n.endswith(".dex"))
    with open(BADGING, "r", encoding="utf-8", errors="replace") as fh:
        badging = fh.read()

    print("entries=%d  dex=%d bytes  badging=%d chars" % (len(names), len(dex), len(badging)))
    print()
    ok = True

    print("-- manifest (aapt2 badging) --")
    for needle, label in WANT_BADGING:
        found = needle in badging
        ok = ok and found
        print("  %-40s %s" % (label, "OK" if found else "*** MISSING ***"))

    print("-- dex --")
    for needle, label in WANT_DEX:
        found = needle in dex
        ok = ok and found
        print("  %-40s %s" % (label, "OK" if found else "*** MISSING ***"))

    print("-- test ids (must be ZERO) --")
    td = dex.count(TEST_MARK)
    ok = ok and td == 0
    print("  dex=%d  %s" % (td, "OK" if td == 0 else "*** TEST IDS PRESENT ***"))

    print("-- stale-build check --")
    stale = ("versionName='%s'" % PREV_VERSION) in badging
    ok = ok and not stale
    print("  old %s in badging: %s"
          % (PREV_VERSION, "*** YES (stale build!) ***" if stale else "no - OK"))

    print("-- signature blocks present --")
    has_v1 = any(n.startswith("META-INF/") and n.endswith((".RSA", ".DSA", ".EC")) for n in names)
    print("  v1 JAR signature: %s" % ("yes" if has_v1 else "no (v2/v3 only - fine on API 24+)"))

    print()
    print("RESULT: %s" % ("ALL CHECKS PASSED" if ok else "FAILED - do not publish"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

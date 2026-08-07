"""Pre-upload gate for the Riplox TT release AAB.

Reads the bundle BYTE-LEVEL from inside the .aab (a zip) instead of trusting that the
build did the right thing. Catches the mistakes that have actually happened on this
app family before: a stale bundle, Google TEST ad ids left in a release build, a
permission silently dropped in a fork, or a policy-risky URL still compiled in.

Run (after `gradlew bundleRelease`):
    set PYTHONIOENCODING=utf-8
    python tools\\verify_aab.py "Play Launch Pack\\2. RiploxTT_v1.1.4_play.aab"

Exit code 0 = safe to upload, 1 = do NOT upload.
Bump VERSION / PREV_VERSION on every release. ASCII-only output (cp1252-safe console).
"""
import sys
import zipfile

AAB = sys.argv[1] if len(sys.argv) > 1 else ""

# --- per-release settings: BUMP THESE ---------------------------------------
VERSION = b"1.1.4"       # the versionName that MUST be in this bundle
PREV_VERSION = b"1.1.3"  # the previous one, which must NOT be (stale-build guard)

# --- AdMob (record: .xniper-secrets\admob-riploxtt.txt) ---------------------
REAL_APP = b"ca-app-pub-8029174313177489~6045986402"
REAL_BANNER = b"ca-app-pub-8029174313177489/7721461931"
REAL_INTER = b"ca-app-pub-8029174313177489/8209115310"
TEST_MARK = b"3940256099942544"  # Google's test-ad account - forbidden in a release build

WANT_MANIFEST = [
    (b"com.xniperbuilds.riploxtt", "applicationId"),
    (VERSION, "versionName"),
    (REAL_APP, "REAL AdMob App ID"),
    (b"CookieLoginActivity", "Connect TikTok activity"),
    # This permission went missing during the fork strip in v1.1.1, which made the battery
    # dialog a silent no-op (caught on a TECNO CLA5). Hence: checked on every build.
    (b"REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", "battery permission"),
    (b"FOREGROUND_SERVICE_DATA_SYNC", "FGS dataSync permission"),
]

WANT_DEX = [
    (REAL_BANNER, "real banner id"),
    (REAL_INTER, "real interstitial id"),
    (b"TikTokExtractor", "TikTok extractor (the v1.1.3 download fix)"),
    (b"appupdate", "Play In-App Updates library"),
]

# These strings must NOT be present in the bundle. Play's Device & Network Abuse policy:
# an app installed from Play may not update itself or pull an APK from outside.
MUST_BE_ABSENT = [
    (b"api.github.com/repos/xniperbuilds/riplox-tt", "GitHub update-check API URL"),
    (b"github.com/xniperbuilds/riplox-tt/releases", "GitHub releases link"),
]


def main():
    if not AAB:
        print("usage: verify_aab.py <path-to.aab>")
        return 2
    z = zipfile.ZipFile(AAB)
    names = z.namelist()
    man = z.read("base/manifest/AndroidManifest.xml")
    dex = b"".join(z.read(n) for n in names if n.endswith(".dex"))

    print("entries=%d  manifest=%d bytes  dex=%d bytes" % (len(names), len(man), len(dex)))
    print()
    ok = True

    print("-- manifest --")
    for needle, label in WANT_MANIFEST:
        found = needle in man
        ok = ok and found
        print("  %-40s %s" % (label, "OK" if found else "*** MISSING ***"))

    print("-- dex --")
    for needle, label in WANT_DEX:
        found = needle in dex
        ok = ok and found
        print("  %-40s %s" % (label, "OK" if found else "*** MISSING ***"))

    print("-- must be ABSENT --")
    both = man + dex
    for needle, label in MUST_BE_ABSENT:
        gone = needle not in both
        ok = ok and gone
        print("  %-40s %s" % (label, "absent - OK" if gone else "*** STILL PRESENT ***"))

    print("-- test ids (must be ZERO) --")
    tm = man.count(TEST_MARK)
    td = dex.count(TEST_MARK)
    clean = (tm == 0 and td == 0)
    ok = ok and clean
    print("  manifest=%d  dex=%d  %s" % (tm, td, "OK" if clean else "*** TEST IDS PRESENT ***"))

    print("-- stale-build check --")
    stale = PREV_VERSION in man
    ok = ok and not stale
    print("  old %s string in manifest: %s"
          % (PREV_VERSION.decode(), "*** YES (stale build!) ***" if stale else "no - OK"))

    print()
    print("RESULT: %s" % ("ALL CHECKS PASSED" if ok else "FAILED - do not upload"))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())

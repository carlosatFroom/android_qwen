# Big-cores + clean-output deployment

Deploys two native fixes (branch `sdk29`, `lib/src/main/cpp/ai_chat.cpp`):

1. **Big-core pinning** — worker threads (incl. OpenMP) are pinned to the big CPU cluster via
   `sched_setaffinity`, re-applied every generation. Stops the screen-off governor from
   throttling inference 5–20× by parking work on the little cores.
2. **Clean output** — model replies are parsed through llama.cpp's chat parser, so
   harmony/`<|channel|>`/`<think>` markers no longer leak into `content`. Reasoning is dropped
   when thinking is off, inline when on.

---

## 0. One-time: build the APK (do this first)

```bash
cd /Users/carlos/code/android_qwen
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # JBR 21
git rev-parse --abbrev-ref HEAD          # expect: sdk29
./gradlew assembleDebug
```

APK lands at: `app/build/outputs/apk/debug/app-debug.apk`
Package: `com.example.qwenchat`  ·  Launcher: `.MainActivity`  ·  Server port: `11434`

---

## 1. Per-device install

Plug in / confirm the device is on the LAN, then:

```bash
adb devices                              # confirm the serial shows up

APK=app/build/outputs/apk/debug/app-debug.apk
SERIAL=<paste-serial>                     # see table below

adb -s "$SERIAL" install -r "$APK"        # -r = keep app data/settings
```

> `-r` reinstalls over the top and keeps DataStore settings (model path, port, system
> prompt), so you do **not** have to reconfigure the model after each push.

Repeat for every device in the table.

### Device / IP reference

| Serial             | Device              | API | LAN IP (last known) |
|--------------------|---------------------|-----|---------------------|
| `RFCX61APE9B`      | Samsung S24 Ultra   | 36  | —                   |
| `3544533842353098` | Samsung S9          | 29  | 10.9.1.x            |
| `2628c97d15037ece` | Samsung Note 10     | 29  | 10.9.1.x            |
| `RFCRB0KTFJE`      | Samsung S20 FE      | 29  | —                   |

(Friday's unreachable servers were `10.9.1.7`, `10.9.1.8`, `10.9.1.17`. Map each to a serial
as you go and fill in the table.)

---

## 2. On each phone after install

1. Open **Qwen Chat (sdk29)** once. Load the model if it isn't already (the server reuses the
   app's loaded model).
2. Toggle the **server ON** in the app → accept the battery-optimization prompt.
3. **Samsung-specific (important — this is what killed them Friday):**
   - Settings → Battery → **Background usage limits**
     - **Never sleeping apps** → add **Qwen Chat**
     - Make sure it is **not** in *Sleeping apps* / *Deep sleeping apps*
   - Turn **off** "Put unused apps to sleep" (or at least exclude this app).
4. Confirm the persistent notification shows **"Serving on http://10.9.1.x:11434"**.

> The big-core pinning fixes *speed*; these Samsung settings fix *reachability*. You need both.

---

## 3. Verify both fixes (do this with the SCREEN OFF)

From the Mac, against each phone's IP:

```bash
IP=10.9.1.8                               # the phone under test

# Lock the phone screen first, wait ~10s, THEN run this:
time curl -s -X POST http://$IP:11434/api/chat \
  -H "Content-Type: application/json" \
  -d '{"model":"x","messages":[{"role":"user","content":"In one sentence, what is a transistor?"}],"stream":false}'
```

**Pass criteria:**
- ✅ **Clean output** — `content` has NO `<|channel|>` / `thought` / `<think>` markers, just the
  answer.
- ✅ **Speed** — finishes fast with the screen off (roughly the same as screen-on; not the
  Friday crawl). Compare `eval_count` ÷ elapsed seconds ≈ screen-on tokens/sec.

### Confirm the affinity fix is actually active (optional, via logcat)

While a request is running, with the device connected by USB:

```bash
adb -s "$SERIAL" logcat -d | grep -iE "big core|big-core|pinned .* threads"
```

Expect lines like:
- `init_context: Using 6 threads (big-core pinning: on)`
- `pin_threads_to_big_cores: pinned N threads to M big cores`

If it says `big-core pinning: off`, this SoC reported a uniform/unreadable CPU topology and
fell back to default scheduling (no harm, but no pinning either — note which device).

---

## 4. Quick troubleshooting

| Symptom                                   | Likely cause / action |
|-------------------------------------------|-----------------------|
| `curl` times out, but `ping $IP` works    | App frozen by Samsung sleep → redo step 2.3 |
| `ping $IP` also fails                      | Wi-Fi dropped or IP changed → check phone Wi-Fi; consider a DHCP reservation |
| Notification gone                          | Foreground service was killed → reopen app, server ON, redo step 2.3 |
| Output still has `<|channel|>`/`<think>`   | Capture `adb logcat | grep -i "chat parse"`; note the model name and ping me |
| `big-core pinning: off` in logcat          | Expected on uniform SoCs; otherwise note device + `adb shell cat /sys/devices/system/cpu/cpu0/cpufreq/cpuinfo_max_freq` |

### Handy one-liners

```bash
# Is the server answering at all?
curl -s http://$IP:11434/health

# Which big cores are online right now (screen off shows the parking)?
adb -s "$SERIAL" shell cat /sys/devices/system/cpu/online
```
</content>
</invoke>

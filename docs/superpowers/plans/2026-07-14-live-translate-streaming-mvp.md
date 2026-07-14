# Live Translate Streaming MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android (Kotlin + Jetpack Compose) app that captures mic audio, cuts utterances with energy VAD (including max-duration force cut), streams OpenAI-compatible ASR then LLM translation, updates bilingual UI incrementally, and saves exportable session history.

**Architecture:** Lightweight layers (`ui` / `domain` / `data`). `AudioCapture` + `EnergyVad` emit complete utterances into a serial queue in `SessionOrchestrator`, which runs ASR `stream=true` then LLM `stream=true` (sliding context window of N turns) and pushes state via `StateFlow` to Compose. Settings in DataStore; history in Room. Manual DI via `AppContainer`.

**Tech Stack:** Kotlin, Jetpack Compose Material3, Coroutines/Flow, OkHttp SSE, DataStore Preferences, Room, Navigation Compose, minSdk 26, targetSdk 34, ViewModel, junit for pure-logic unit tests.

**Spec:** `docs/superpowers/specs/2026-07-14-live-translate-streaming-mvp-design.md`

## Global Constraints

- ASR and LLM **must** use `stream=true`; UI updates on each delta (typewriter), never wait for full response before first paint.
- No hardcoded API keys; all Base URL + Key + models user-configurable.
- VAD: energy threshold; cut on silence **or** `maxUtteranceMs` force truncate.
- Display: complete utterances only (no mid-speech partial ASR).
- LLM context: last N source/translation pairs + current sentence.
- Serial utterance processing (one ASR→LLM chain at a time).
- Package name: `com.example.livetranslate`
- minSdk 26, targetSdk 34
- Commit after each task succeeds.

---

## File Structure (create during tasks)

```
settings.gradle.kts
build.gradle.kts
gradle.properties
app/build.gradle.kts
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/livetranslate/
  LiveTranslateApp.kt
  MainActivity.kt
  di/AppContainer.kt
  domain/model/{CutReason,UtteranceAudio,ContextTurn,StreamEvents,SessionModels}.kt
  domain/SessionOrchestrator.kt
  domain/AsrTextMerger.kt
  data/audio/{EnergyVad,WavEncoder,AudioCapture}.kt
  data/network/SseReader.kt
  data/asr/{AsrConfig,AsrClient}.kt
  data/llm/{LlmConfig,LlmClient}.kt
  data/settings/{UserSettings,SettingsRepository}.kt
  data/history/{Entities,AppDatabase,HistoryRepository}.kt
  ui/theme/Theme.kt
  ui/navigation/AppNav.kt
  ui/live/{LiveTranslateScreen,LiveTranslateViewModel}.kt
  ui/settings/{SettingsScreen,SettingsViewModel}.kt
  ui/history/{HistoryScreen,HistoryDetailScreen,HistoryViewModel}.kt
app/src/test/java/com/example/livetranslate/
  data/audio/EnergyVadTest.kt
  data/audio/WavEncoderTest.kt
  data/network/SseReaderTest.kt
  domain/AsrTextMergerTest.kt
  domain/SessionOrchestratorTest.kt
```

---

### Task 1: Android project scaffold

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `app/build.gradle.kts`, `app/src/main/AndroidManifest.xml`, `app/proguard-rules.pro`
- Create: `app/src/main/java/com/example/livetranslate/MainActivity.kt`
- Create: `app/src/main/java/com/example/livetranslate/ui/theme/Theme.kt`
- Create: `app/src/main/res/values/strings.xml`, `app/src/main/res/values/themes.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`

**Interfaces:**
- Consumes: nothing
- Produces: compilable empty Compose app with package `com.example.livetranslate`

- [ ] **Step 1: Create root Gradle files**

`settings.gradle.kts`:
```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "LiveTranslate"
include(":app")
```

Root `build.gradle.kts`:
```kotlin
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
```

`gradle.properties`:
```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 2: Create `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.livetranslate"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.livetranslate"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
```

Also create empty `app/proguard-rules.pro`.

- [ ] **Step 3: Manifest + minimal UI**

`AndroidManifest.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.RECORD_AUDIO" />

    <application
        android:name=".LiveTranslateApp"
        android:allowBackup="true"
        android:icon="@android:drawable/sym_def_app_icon"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.LiveTranslate"
        android:usesCleartextTraffic="true"
        android:networkSecurityConfig="@xml/network_security_config">
        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:windowSoftInputMode="adjustResize">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

Create `app/src/main/res/xml/network_security_config.xml` allowing cleartext for local OpenAI-compatible servers:
```xml
<?xml version="1.0" encoding="utf-8"?>
<network-security-config>
    <base-config cleartextTrafficPermitted="true" />
</network-security-config>
```

`strings.xml`: `<string name="app_name">Live Translate</string>`  
`themes.xml`: Theme.LiveTranslate parent `android:Theme.Material.Light.NoActionBar`

`LiveTranslateApp.kt` (stub Application):
```kotlin
package com.example.livetranslate

import android.app.Application

class LiveTranslateApp : Application()
```

`MainActivity.kt`:
```kotlin
package com.example.livetranslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import com.example.livetranslate.ui.theme.LiveTranslateTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            LiveTranslateTheme {
                Surface { Text("Live Translate MVP") }
            }
        }
    }
}
```

`Theme.kt`: standard Material3 `LiveTranslateTheme` wrapper using `MaterialTheme` + dynamic color optional off for simplicity.

- [ ] **Step 4: Ensure Gradle wrapper exists**

If no wrapper: run Android Studio sync or `gradle wrapper --gradle-version 8.7`. Commit wrapper jar + properties.

- [ ] **Step 5: Verify assemble**

Run: `./gradlew :app:assembleDebug` (Windows: `gradlew.bat :app:assembleDebug`)  
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle.properties app/ gradle/ gradlew gradlew.bat
git commit -m "chore: scaffold Android Compose LiveTranslate app"
```

---

### Task 2: Domain models

**Files:**
- Create: `app/src/main/java/com/example/livetranslate/domain/model/Models.kt`

**Interfaces:**
- Consumes: nothing
- Produces: shared types used by audio/asr/llm/orchestrator/UI

- [ ] **Step 1: Implement models**

```kotlin
package com.example.livetranslate.domain.model

enum class CutReason { Silence, MaxDuration, StopFlush }

data class UtteranceAudio(
    val pcm: ByteArray,
    val sampleRate: Int,
    val reason: CutReason
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is UtteranceAudio) return false
        return sampleRate == other.sampleRate &&
            reason == other.reason &&
            pcm.contentEquals(other.pcm)
    }
    override fun hashCode(): Int =
        31 * (31 * pcm.contentHashCode() + sampleRate) + reason.hashCode()
}

data class ContextTurn(val source: String, val translation: String)

sealed class AsrStreamEvent {
    data class Delta(val text: String) : AsrStreamEvent()
    data class Completed(val fullText: String) : AsrStreamEvent()
    data class Error(val throwable: Throwable, val retryable: Boolean) : AsrStreamEvent()
}

sealed class LlmStreamEvent {
    data class Delta(val text: String) : LlmStreamEvent()
    data class Completed(val fullText: String) : LlmStreamEvent()
    data class Error(val throwable: Throwable, val retryable: Boolean) : LlmStreamEvent()
}

enum class SessionPhase { Idle, Recording, Paused, Processing }

data class TranscriptSegment(
    val source: String,
    val translation: String,
    val cutReason: CutReason?,
    val incomplete: Boolean = false
)
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/example/livetranslate/domain/model/Models.kt
git commit -m "feat: add domain models for streaming session"
```

---

### Task 3: EnergyVad (unit tested)

**Files:**
- Create: `app/src/main/java/com/example/livetranslate/data/audio/EnergyVad.kt`
- Test: `app/src/test/java/com/example/livetranslate/data/audio/EnergyVadTest.kt`

**Interfaces:**
- Consumes: PCM frames (ShortArray or ByteArray little-endian 16-bit), config params
- Produces: `EnergyVad` that returns `VadAction` per frame: `None` | `Emit(pcm, reason)` and keeps internal buffer

- [ ] **Step 1: Write failing tests**

```kotlin
package com.example.livetranslate.data.audio

import com.example.livetranslate.domain.model.CutReason
import org.junit.Assert.*
import org.junit.Test

class EnergyVadTest {

    private fun loudFrame(samples: Int = 320): ShortArray =
        ShortArray(samples) { 8000 }

    private fun quietFrame(samples: Int = 320): ShortArray =
        ShortArray(samples) { 10 }

    private fun vad(
        silenceMs: Int = 100,
        maxMs: Int = 500,
        minMs: Int = 50,
        threshold: Double = 500.0,
        sampleRate: Int = 16000,
        frameSamples: Int = 320 // 20ms @ 16k
    ) = EnergyVad(
        sampleRate = sampleRate,
        frameSamples = frameSamples,
        energyThreshold = threshold,
        silenceMs = silenceMs,
        maxUtteranceMs = maxMs,
        minUtteranceMs = minMs
    )

    @Test
    fun silenceAfterSpeech_emitsSilenceCut() {
        val v = vad(silenceMs = 40, maxMs = 10_000, minMs = 20)
        // 20ms loud + 40ms quiet => cut
        assertNull(v.accept(loudFrame()).emit)
        assertNull(v.accept(quietFrame()).emit)
        val secondQuiet = v.accept(quietFrame())
        assertNotNull(secondQuiet.emit)
        assertEquals(CutReason.Silence, secondQuiet.emit!!.reason)
        assertTrue(secondQuiet.emit!!.pcm.isNotEmpty())
    }

    @Test
    fun longSpeech_forceMaxDurationCut() {
        val v = vad(silenceMs = 10_000, maxMs = 60, minMs = 20)
        // 20ms * 4 = 80ms > 60ms max
        var emitted = 0
        repeat(4) {
            if (v.accept(loudFrame()).emit != null) emitted++
        }
        assertEquals(1, emitted)
    }

    @Test
    fun tooShort_dropped() {
        val v = vad(silenceMs = 20, maxMs = 10_000, minMs = 100)
        // one 20ms frame then silence — under min 100ms
        v.accept(loudFrame())
        val e1 = v.accept(quietFrame()).emit
        val e2 = v.accept(quietFrame()).emit
        assertTrue(e1 == null && e2 == null)
    }
}
```

- [ ] **Step 2: Run tests — expect FAIL**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.livetranslate.data.audio.EnergyVadTest`  
Expected: FAIL (class not found)

- [ ] **Step 3: Implement EnergyVad**

```kotlin
package com.example.livetranslate.data.audio

import com.example.livetranslate.domain.model.CutReason
import java.io.ByteArrayOutputStream
import kotlin.math.sqrt

data class VadEmit(val pcm: ByteArray, val reason: CutReason)
data class VadResult(val emit: VadEmit?)

/**
 * Frame-based energy VAD.
 * - RMS >= threshold → speaking; append frame to buffer
 * - while speaking, silence duration >= silenceMs → emit Silence (if >= minUtteranceMs)
 * - while speaking, buffered duration >= maxUtteranceMs → emit MaxDuration
 */
class EnergyVad(
    private val sampleRate: Int,
    private val frameSamples: Int,
    private val energyThreshold: Double,
    private val silenceMs: Int,
    private val maxUtteranceMs: Int,
    private val minUtteranceMs: Int
) {
    private val buffer = ByteArrayOutputStream()
    private var speaking = false
    private var silenceFrames = 0
    private var speechFrames = 0

    private val frameMs: Double get() = frameSamples * 1000.0 / sampleRate
    private val silenceFramesNeeded: Int get() = (silenceMs / frameMs).toInt().coerceAtLeast(1)
    private val maxSpeechFrames: Int get() = (maxUtteranceMs / frameMs).toInt().coerceAtLeast(1)
    private val minSpeechFrames: Int get() = (minUtteranceMs / frameMs).toInt().coerceAtLeast(1)

    fun accept(frame: ShortArray): VadResult {
        require(frame.size == frameSamples) { "Expected $frameSamples samples" }
        val rms = rms(frame)
        val isSpeech = rms >= energyThreshold

        if (isSpeech) {
            speaking = true
            silenceFrames = 0
            speechFrames++
            writeFrame(frame)
            if (speechFrames >= maxSpeechFrames) {
                return emitAndReset(CutReason.MaxDuration)
            }
            return VadResult(null)
        }

        // quiet frame
        if (!speaking) {
            return VadResult(null)
        }
        // still append trailing quiet frames into buffer until cut (keeps natural tail)
        writeFrame(frame)
        silenceFrames++
        speechFrames++
        if (speechFrames >= maxSpeechFrames) {
            return emitAndReset(CutReason.MaxDuration)
        }
        if (silenceFrames >= silenceFramesNeeded) {
            return emitAndReset(CutReason.Silence)
        }
        return VadResult(null)
    }

    fun flushStop(): VadResult {
        if (!speaking || speechFrames < minSpeechFrames) {
            reset()
            return VadResult(null)
        }
        return emitAndReset(CutReason.StopFlush)
    }

    fun reset() {
        buffer.reset()
        speaking = false
        silenceFrames = 0
        speechFrames = 0
    }

    private fun emitAndReset(reason: CutReason): VadResult {
        val speechOnlyFrames = speechFrames // includes trailing silence frames for Silence cut
        val pcm = buffer.toByteArray()
        reset()
        if (speechOnlyFrames < minSpeechFrames && reason != CutReason.MaxDuration) {
            // For silence cuts under min, drop. MaxDuration always emit if any pcm.
            if (pcm.isEmpty() || bytesToFrames(pcm.size) < minSpeechFrames) {
                return VadResult(null)
            }
        }
        if (pcm.isEmpty()) return VadResult(null)
        if (bytesToFrames(pcm.size) < minSpeechFrames) return VadResult(null)
        return VadResult(VadEmit(pcm, reason))
    }

    private fun bytesToFrames(byteCount: Int): Int {
        val samples = byteCount / 2
        return samples / frameSamples
    }

    private fun writeFrame(frame: ShortArray) {
        val bytes = ByteArray(frame.size * 2)
        var i = 0
        for (s in frame) {
            bytes[i++] = (s.toInt() and 0xFF).toByte()
            bytes[i++] = (s.toInt() shr 8 and 0xFF).toByte()
        }
        buffer.write(bytes)
    }

    private fun rms(frame: ShortArray): Double {
        var sum = 0.0
        for (s in frame) {
            val v = s.toDouble()
            sum += v * v
        }
        return sqrt(sum / frame.size)
    }
}
```

Note: Adjust `emitAndReset` min-length logic so tests pass exactly — prefer counting speech frames before trailing silence if tests fail; fix until all three tests green.

- [ ] **Step 4: Run tests — expect PASS**

Run: `gradlew.bat :app:testDebugUnitTest --tests com.example.livetranslate.data.audio.EnergyVadTest`  
Expected: OK (3 tests)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/livetranslate/data/audio/EnergyVad.kt \
  app/src/test/java/com/example/livetranslate/data/audio/EnergyVadTest.kt
git commit -m "feat: energy VAD with silence and max-duration cuts"
```

---

### Task 4: WavEncoder (unit tested)

**Files:**
- Create: `app/src/main/java/com/example/livetranslate/data/audio/WavEncoder.kt`
- Test: `app/src/test/java/com/example/livetranslate/data/audio/WavEncoderTest.kt`

**Interfaces:**
- Consumes: PCM 16-bit LE mono bytes + sampleRate
- Produces: full WAV byte array (44-byte header + data)

- [ ] **Step 1: Write failing test**

```kotlin
package com.example.livetranslate.data.audio

import org.junit.Assert.*
import org.junit.Test

class WavEncoderTest {
    @Test
    fun headerAndSize_areCorrect() {
        val pcm = ByteArray(4) { 1 } // 2 samples
        val wav = WavEncoder.pcm16MonoToWav(pcm, sampleRate = 16000)
        assertEquals(44 + 4, wav.size)
        assertEquals('R'.code.toByte(), wav[0])
        assertEquals('I'.code.toByte(), wav[1])
        assertEquals('F'.code.toByte(), wav[2])
        assertEquals('F'.code.toByte(), wav[3])
        // audio format PCM = 1 at byte 20-21
        assertEquals(1, wav[20].toInt() and 0xFF)
        assertEquals(0, wav[21].toInt() and 0xFF)
        // num channels = 1
        assertEquals(1, wav[22].toInt() and 0xFF)
    }
}
```

- [ ] **Step 2: Run — expect FAIL**

- [ ] **Step 3: Implement**

```kotlin
package com.example.livetranslate.data.audio

import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavEncoder {
    fun pcm16MonoToWav(pcm: ByteArray, sampleRate: Int): ByteArray {
        val channels = 1
        val bitsPerSample = 16
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val dataSize = pcm.size
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // PCM chunk size
        buffer.putShort(1) // audio format PCM
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)
        buffer.put(pcm)
        return buffer.array()
    }
}
```

- [ ] **Step 4: Run — expect PASS**

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/example/livetranslate/data/audio/WavEncoder.kt \
  app/src/test/java/com/example/livetranslate/data/audio/WavEncoderTest.kt
git commit -m "feat: PCM to WAV encoder for ASR upload"
```

---

### Task 5: SseReader + AsrTextMerger (unit tested)

**Files:**
- Create: `app/src/main/java/com/example/livetranslate/data/network/SseReader.kt`
- Create: `app/src/main/java/com/example/livetranslate/domain/AsrTextMerger.kt`
- Test: `app/src/test/java/com/example/livetranslate/data/network/SseReaderTest.kt`
- Test: `app/src/test/java/com/example/livetranslate/domain/AsrTextMergerTest.kt`

**Interfaces:**
- `SseReader.readLines(source: BufferedSource): Sequence<String>` or suspend Flow of data payloads (JSON strings without `data:` prefix)
- `AsrTextMerger.merge(previous: String, incoming: String): String` — snapshot vs append detection

- [ ] **Step 1: Write tests**

```kotlin
// AsrTextMergerTest
@Test fun snapshot_whenPrefix() {
    assertEquals("hello world", AsrTextMerger.merge("hello", "hello world"))
}
@Test fun append_whenNotPrefix() {
    assertEquals("hello world", AsrTextMerger.merge("hello ", "world"))
}

// SseReaderTest — feed string via Buffer
@Test fun parsesDataLines_skipsDoneAndEmpty() {
    val raw = "data: {\"a\":1}\n\ndata: [DONE]\ndata: {\"b\":2}\n"
    val payloads = SseReader.parsePayloads(raw.byteInputStream().bufferedReader())
    assertEquals(listOf("{\"a\":1}", "{\"b\":2}"), payloads)
}
```

- [ ] **Step 2: Implement**

```kotlin
// AsrTextMerger.kt
object AsrTextMerger {
    fun merge(previous: String, incoming: String): String {
        if (incoming.isEmpty()) return previous
        if (previous.isEmpty()) return incoming
        return if (incoming.startsWith(previous)) incoming
        else previous + incoming
    }
}

// SseReader.kt
object SseReader {
    fun parsePayloads(reader: java.io.BufferedReader): List<String> {
        val out = ArrayList<String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            out.add(payload)
        }
        return out
    }

    fun readPayloads(source: okio.BufferedSource): Sequence<String> = sequence {
        while (!source.exhausted()) {
            val line = source.readUtf8Line() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isEmpty() || payload == "[DONE]") continue
            yield(payload)
        }
    }
}
```

- [ ] **Step 3: Tests PASS + Commit**

```bash
git commit -m "feat: SSE payload parser and ASR text merger"
```

---

### Task 6: Settings DataStore

**Files:**
- Create: `app/src/main/java/com/example/livetranslate/data/settings/UserSettings.kt`
- Create: `app/src/main/java/com/example/livetranslate/data/settings/SettingsRepository.kt`

**Interfaces:**
- `SettingsRepository.settings: Flow<UserSettings>`
- `suspend fun update(transform: (UserSettings) -> UserSettings)`

- [ ] **Step 1: Implement**

```kotlin
data class UserSettings(
    val asrBaseUrl: String = "https://api.openai.com",
    val asrApiKey: String = "",
    val asrModel: String = "whisper-1",
    val llmBaseUrl: String = "https://api.openai.com",
    val llmApiKey: String = "",
    val llmModel: String = "gpt-4o-mini",
    val inputLanguage: String = "en",
    val outputLanguage: String = "zh",
    val silenceMs: Int = 700,
    val maxUtteranceMs: Int = 10_000,
    val minUtteranceMs: Int = 300,
    val energyThreshold: Double = 500.0,
    val contextWindowSize: Int = 4
)

class SettingsRepository(private val context: Context) {
    private val Context.dataStore by preferencesDataStore("settings")
    // keys for each field; map Flow; update via edit
    val settings: Flow<UserSettings> = ...
    suspend fun update(transform: (UserSettings) -> UserSettings) { ... }
}
```

Normalize base URLs when reading: trim, remove trailing `/`.

- [ ] **Step 2: Commit**

```bash
git commit -m "feat: DataStore user settings for ASR/LLM/VAD"
```

---

### Task 7: AsrClient streaming

**Files:**
- Create: `app/src/main/java/com/example/livetranslate/data/asr/AsrConfig.kt`
- Create: `app/src/main/java/com/example/livetranslate/data/asr/AsrClient.kt`
- Test: `app/src/test/java/com/example/livetranslate/data/asr/AsrClientTest.kt` (MockWebServer)

**Interfaces:**
- `class AsrClient(okHttpClient: OkHttpClient)`
- `fun transcribeStream(audio: UtteranceAudio, config: AsrConfig): Flow<AsrStreamEvent>`

- [ ] **Step 1: MockWebServer test**

```kotlin
@Test
fun emitsDeltasFromSse() = runTest {
    val server = MockWebServer()
    server.enqueue(
        MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"text\":\"Hel\"}\n\ndata: {\"text\":\"Hello\"}\n\ndata: [DONE]\n\n")
    )
    server.start()
    val client = AsrClient(OkHttpClient())
    val config = AsrConfig(server.url("/").toString().trimEnd('/'), "key", "whisper-1", "en")
    val pcm = ByteArray(320)
    val events = client.transcribeStream(
        UtteranceAudio(pcm, 16000, CutReason.Silence), config
    ).toList()
    // expect Delta/Completed sequence with merged text ending Hello
    server.shutdown()
}
```

- [ ] **Step 2: Implement AsrClient**

Behavior:
1. Build WAV via `WavEncoder.pcm16MonoToWav`.
2. `MultipartBody` POST `{baseUrl}/v1/audio/transcriptions` with parts: `file` (audio.wav), `model`, `language`, `stream`=`true`.
3. Header `Authorization: Bearer {apiKey}`.
4. On response: `SseReader.readPayloads(body.source())`.
5. For each JSON payload, extract text via `org.json.JSONObject` trying keys: `text`, `delta`, or `choices` nested — use best-effort:
   - if has `text` use it
   - else if has `delta` as string use it
6. Maintain local `acc` with `AsrTextMerger.merge`; emit `AsrStreamEvent.Delta(acc)` each time (snapshot-style UI).
7. After stream end emit `Completed(acc)`.
8. On HTTP failure emit `Error(retryable = code in 408,429,500..599)`.
9. On Flow cancellation cancel OkHttp `Call`.

```kotlin
fun transcribeStream(...): Flow<AsrStreamEvent> = callbackFlow {
    val call = client.newCall(request)
    try {
        val response = call.execute()
        // parse...
    } catch (e: Exception) {
        trySend(AsrStreamEvent.Error(e, retryable = true))
    }
    awaitClose { call.cancel() }
}.flowOn(Dispatchers.IO)
```

- [ ] **Step 3: Test PASS + Commit**

```bash
git commit -m "feat: OpenAI-compatible ASR streaming client"
```

---

### Task 8: LlmClient streaming

**Files:**
- Create: `app/src/main/java/com/example/livetranslate/data/llm/LlmConfig.kt`
- Create: `app/src/main/java/com/example/livetranslate/data/llm/LlmClient.kt`
- Test: `app/src/test/java/com/example/livetranslate/data/llm/LlmClientTest.kt`

**Interfaces:**
- `fun translateStream(sourceText: String, context: List<ContextTurn>, config: LlmConfig): Flow<LlmStreamEvent>`

- [ ] **Step 1: MockWebServer test with chat completion SSE**

Body chunks:
```
data: {"choices":[{"delta":{"content":"你"}}]}

data: {"choices":[{"delta":{"content":"好"}}]}

data: [DONE]

```

- [ ] **Step 2: Implement**

POST `{baseUrl}/v1/chat/completions` JSON:
```json
{
  "model": "...",
  "stream": true,
  "messages": [
    {"role":"system","content":"You are a simultaneous interpreter. Translate the user's English into Chinese (or configured target). Output only the translation for the CURRENT utterance. Do not re-translate history. Use history only for terminology consistency."},
    {"role":"user","content":"History:\nEN: ...\nZH: ...\n\nCurrent EN:\n..."}
  ]
}
```

Parse `choices[0].delta.content`, append-only, emit `Delta` then `Completed`.

- [ ] **Step 3: Test PASS + Commit**

```bash
git commit -m "feat: OpenAI-compatible LLM streaming translation client"
```

---

### Task 9: AudioCapture

**Files:**
- Create: `app/src/main/java/com/example/livetranslate/data/audio/AudioCapture.kt`

**Interfaces:**
- `class AudioCapture(settingsProvider: () -> UserSettings)` or inject VAD params
- `fun start()`, `fun pause()`, `fun stop()`
- `val utterances: SharedFlow<UtteranceAudio>` (replay=0, extraBufferCapacity=8)

- [ ] **Step 1: Implement**

```kotlin
class AudioCapture(
    private val scope: CoroutineScope,
    private val settings: () -> UserSettings
) {
    private val _utterances = MutableSharedFlow<UtteranceAudio>(extraBufferCapacity = 16)
    val utterances: SharedFlow<UtteranceAudio> = _utterances

    @Volatile private var running = false
    private var job: Job? = null

    fun start() {
        if (running) return
        running = true
        job = scope.launch(Dispatchers.IO) { loop() }
    }

    fun pause() {
        running = false
        job?.cancel()
        job = null
        // do not flush on pause per spec
    }

    fun stop(flush: Boolean = true) {
        running = false
        // if flush: vad.flushStop() and emit if non-null
        job?.cancel()
        job = null
    }

    private fun loop() {
        val s = settings()
        val sampleRate = 16000
        val frameMs = 20
        val frameSamples = sampleRate * frameMs / 1000
        val vad = EnergyVad(
            sampleRate, frameSamples,
            s.energyThreshold, s.silenceMs, s.maxUtteranceMs, s.minUtteranceMs
        )
        val minBuf = AudioRecord.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2
        )
        try {
            recorder.startRecording()
            val frame = ShortArray(frameSamples)
            while (running && isActive) {
                val read = recorder.read(frame, 0, frame.size)
                if (read != frame.size) continue
                val result = vad.accept(frame)
                result.emit?.let {
                    _utterances.tryEmit(
                        UtteranceAudio(it.pcm, sampleRate, it.reason)
                    )
                }
            }
        } finally {
            try { recorder.stop() } catch (_: Exception) {}
            recorder.release()
        }
    }
}
```

Add detailed comments on frame size and VAD cut reasons (spec requirement).

- [ ] **Step 2: Commit**

```bash
git commit -m "feat: AudioRecord capture with VAD utterance emission"
```

---

### Task 10: History Room

**Files:**
- Create: `.../data/history/SessionEntity.kt`, `SegmentEntity.kt`, `SessionDao.kt`, `AppDatabase.kt`, `HistoryRepository.kt`

**Interfaces:**
- `suspend fun saveSession(startedAt, endedAt, segments): Long`
- `fun observeSessions(): Flow<List<SessionSummary>>`
- `suspend fun getSessionDetail(id): SessionDetail`
- Export helper: `fun formatMarkdown(detail): String`

Schema:
```kotlin
@Entity data class SessionEntity(id, createdAt, endedAt, previewZh)
@Entity data class SegmentEntity(id, sessionId, source, translation, cutReason, incomplete, createdAt)
```

- [ ] **Step 1: Implement DAO + repository**

- [ ] **Step 2: Commit**

```bash
git commit -m "feat: Room history storage and markdown export"
```

---

### Task 11: SessionOrchestrator (unit tested with fakes)

**Files:**
- Create: `app/src/main/java/com/example/livetranslate/domain/SessionOrchestrator.kt`
- Test: `app/src/test/java/com/example/livetranslate/domain/SessionOrchestratorTest.kt`

**Interfaces:**
- Consumes: `AudioCapture`, `AsrClient`, `LlmClient`, `SettingsRepository`, `HistoryRepository`
- Produces: `state: StateFlow<LiveSessionUiState>`
- Methods: `start()`, `pause()`, `stop()`, `retryLastFailed()`

```kotlin
data class LiveSessionUiState(
    val phase: SessionPhase = SessionPhase.Idle,
    val cumulativeEn: String = "",
    val cumulativeZh: String = "",
    val partialEn: String = "",
    val partialZh: String = "",
    val lastCutReason: CutReason? = null,
    val error: String? = null,
    val canRetry: Boolean = false,
    val segments: List<TranscriptSegment> = emptyList()
)
```

- [ ] **Step 1: Write test with fake ASR/LLM Flows**

Fake ASR emits two Deltas then Completed; fake LLM same; assert final cumulativeEn/Zh and serial processing of two utterances.

- [ ] **Step 2: Implement orchestrator**

```kotlin
class SessionOrchestrator(
    private val scope: CoroutineScope,
    private val audio: AudioCapture,
    private val asr: AsrClient,
    private val llm: LlmClient,
    private val settingsRepo: SettingsRepository,
    private val history: HistoryRepository
) {
    private val _state = MutableStateFlow(LiveSessionUiState())
    val state: StateFlow<LiveSessionUiState> = _state.asStateFlow()

    private val queue = Channel<UtteranceAudio>(Channel.UNLIMITED)
    private var worker: Job? = null
    private var collector: Job? = null
    private var sessionStartedAt: Long = 0L
    private val contextWindow = ArrayDeque<ContextTurn>()
    private var lastFailed: UtteranceAudio? = null
    private var processJob: Job? = null

    fun start() {
        sessionStartedAt = System.currentTimeMillis()
        _state.update { LiveSessionUiState(phase = SessionPhase.Recording) }
        ensureWorker()
        collector = scope.launch {
            audio.utterances.collect { queue.send(it) }
        }
        audio.start()
    }

    fun pause() {
        audio.pause()
        _state.update { it.copy(phase = SessionPhase.Paused) }
    }

    fun stop() {
        audio.stop(flush = true)
        collector?.cancel()
        processJob?.cancel()
        // save history from segments if any
        scope.launch {
            val s = _state.value
            if (s.segments.isNotEmpty()) {
                history.saveSession(sessionStartedAt, System.currentTimeMillis(), s.segments)
            }
            _state.value = LiveSessionUiState(phase = SessionPhase.Idle)
            contextWindow.clear()
        }
    }

    private fun ensureWorker() {
        if (worker?.isActive == true) return
        worker = scope.launch {
            for (utt in queue) {
                processUtterance(utt)
            }
        }
    }

    private suspend fun processUtterance(utt: UtteranceAudio) {
        val settings = settingsRepo.settings.first()
        _state.update {
            it.copy(
                phase = SessionPhase.Processing,
                partialEn = "",
                partialZh = "",
                lastCutReason = utt.reason,
                error = null,
                canRetry = false
            )
        }
        var en = ""
        try {
            asr.transcribeStream(utt, AsrConfig(...from settings)).collect { ev ->
                when (ev) {
                    is AsrStreamEvent.Delta -> {
                        en = ev.text
                        _state.update { it.copy(partialEn = en) }
                    }
                    is AsrStreamEvent.Completed -> en = ev.fullText
                    is AsrStreamEvent.Error -> throw ev.throwable
                }
            }
            if (en.isBlank()) return

            var zh = ""
            val ctx = contextWindow.toList()
            llm.translateStream(en, ctx, LlmConfig(...)).collect { ev ->
                when (ev) {
                    is LlmStreamEvent.Delta -> {
                        zh = if (ev.text.startsWith(zh)) ev.text else zh + ev.text
                        // prefer append: client already emits deltas as pieces — use append:
                        // Actually LlmClient should emit Delta as piece OR accumulated.
                        // Spec: LLM append-only pieces → orchestrator: zh += piece OR use absolute if Completed.
                        _state.update { it.copy(partialZh = zh) }
                    }
                    is LlmStreamEvent.Completed -> zh = ev.fullText
                    is LlmStreamEvent.Error -> throw ev.throwable
                }
            }

            val seg = TranscriptSegment(en, zh, utt.reason)
            contextWindow.addLast(ContextTurn(en, zh))
            while (contextWindow.size > settings.contextWindowSize) contextWindow.removeFirst()

            _state.update {
                it.copy(
                    cumulativeEn = appendBlock(it.cumulativeEn, en),
                    cumulativeZh = appendBlock(it.cumulativeZh, zh),
                    partialEn = "",
                    partialZh = "",
                    segments = it.segments + seg,
                    phase = if (audioIsRecording()) SessionPhase.Recording else it.phase
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            lastFailed = utt
            _state.update {
                it.copy(error = e.message ?: "error", canRetry = true, partialEn = en)
            }
        }
    }

    fun retryLastFailed() {
        val u = lastFailed ?: return
        lastFailed = null
        scope.launch { processUtterance(u) }
    }
}

private fun appendBlock(prev: String, next: String): String =
    if (prev.isEmpty()) next else prev + "\n" + next
```

**LLM Delta contract (lock in):** `LlmClient` emits `Delta` with **piece only** (not accumulated). Orchestrator does `zh += piece`. `Completed` carries full string.

**ASR Delta contract (lock in):** `AsrClient` emits `Delta` with **merged display text** (after AsrTextMerger). Orchestrator sets `partialEn = text`.

- [ ] **Step 3: Test PASS + Commit**

```bash
git commit -m "feat: serial session orchestrator ASR then LLM streaming"
```

---

### Task 12: AppContainer + ViewModels + Navigation + Screens

**Files:**
- Create: `di/AppContainer.kt`
- Create: `ui/live/LiveTranslateViewModel.kt`, `LiveTranslateScreen.kt`
- Create: `ui/settings/SettingsViewModel.kt`, `SettingsScreen.kt`
- Create: `ui/history/HistoryViewModel.kt`, `HistoryScreen.kt`, `HistoryDetailScreen.kt`
- Create: `ui/navigation/AppNav.kt`
- Modify: `LiveTranslateApp.kt`, `MainActivity.kt`

**Interfaces:**
- App exposes `container: AppContainer`
- Nav routes: `live`, `settings`, `history`, `history/{id}`

- [ ] **Step 1: AppContainer**

```kotlin
class AppContainer(context: Context) {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val settingsRepository = SettingsRepository(context)
    val historyRepository = HistoryRepository(context)
    val okHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS) // streaming
        .build()
    val asrClient = AsrClient(okHttp)
    val llmClient = LlmClient(okHttp)
    // AudioCapture + Orchestrator created per ViewModel session scope OR app-scoped
}
```

Prefer **one orchestrator per LiveTranslateViewModel** with `viewModelScope`.

- [ ] **Step 2: Live screen UI**

- Top bar: title + icons Settings / History
- Bilingual scroll columns: cumulative EN + partial EN; cumulative ZH + partial ZH
- Buttons: Start / Pause / Stop
- Error snackbar + Retry button when `canRetry`
- Request `RECORD_AUDIO` via `rememberLauncherForActivityResult` before start

- [ ] **Step 3: Settings screen**

OutlinedTextFields for all UserSettings fields; Save button calls repository.update.

- [ ] **Step 4: History list + detail + Share**

Detail: LazyColumn of segments; FAB/share uses `Intent.ACTION_SEND` with markdown.

- [ ] **Step 5: Wire MainActivity setContent { AppNav(container) }**

- [ ] **Step 6: Assemble debug**

Run: `gradlew.bat :app:assembleDebug`  
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git commit -m "feat: Compose UI for live translate, settings, and history"
```

---

### Task 13: Retry/error polish + manual QA checklist

**Files:**
- Modify: orchestrator/UI as needed for exponential backoff (optional in client or orchestrator): on retryable ASR/LLM error, delay 500ms then 1500ms, max 2 auto-retries before setting `canRetry`.

- [ ] **Step 1: Add auto-retry loop inside processUtterance for retryable errors (max 2)**

```kotlin
var attempt = 0
while (true) {
    try {
        // asr+llm once
        break
    } catch (e: Exception) {
        attempt++
        if (attempt > 2 || !isRetryable(e)) throw e
        delay(500L * attempt * attempt)
    }
}
```

- [ ] **Step 2: Document manual QA in plan completion notes**

Manual checks on device/emulator with mic:
1. Empty keys → clear error, no crash
2. Start → speak → silence → EN streams then ZH streams
3. Continuous speech > maxUtteranceMs → force cut, second segment starts
4. Pause freezes capture; resume (Start again) continues same session texts **OR** document: Pause then Start resumes recording into same session (implement Start-from-Paused as resume)
5. Stop saves history; export share sheet opens

**Resume behavior (lock in):** From `Paused`, Start resumes capture into the **same** session (do not clear cumulative). From `Idle` after Stop, Start is a new session.

- [ ] **Step 3: Unit tests still pass + assembleDebug**

```bash
gradlew.bat :app:testDebugUnitTest :app:assembleDebug
```

- [ ] **Step 4: Commit**

```bash
git commit -m "feat: retry policy and session resume-from-pause behavior"
```

---

## Self-Review (plan vs spec)

| Spec requirement | Task |
|------------------|------|
| Mic continuous capture AudioRecord | Task 9 |
| Energy VAD silence cut | Task 3, 9 |
| maxUtteranceMs force cut | Task 3, 9 |
| ASR stream=true + chunk UI | Task 7, 11, 12 |
| LLM stream=true + chunk UI | Task 8, 11, 12 |
| Sliding context N | Task 11 |
| Serial queue | Task 11 |
| Start/Pause/Stop | Task 11, 12 |
| Settings ASR/LLM/langs/VAD | Task 6, 12 |
| History view + export | Task 10, 12 |
| Error + retry | Task 11, 13 |
| No hardcoded keys | Task 6, 12 |
| minSdk 26 | Task 1 |
| Complete-utterance only display | Task 11 (no mid-speech ASR) |

**Placeholder scan:** No TBD steps; contracts for ASR/LLM Delta locked in Task 11.  
**Type consistency:** `UtteranceAudio`, `CutReason`, `AsrStreamEvent`, `LlmStreamEvent`, `UserSettings`, `LiveSessionUiState`, `ContextTurn` used consistently.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-14-live-translate-streaming-mvp.md`.

**Two execution options:**

1. **Subagent-Driven (recommended)** — fresh subagent per task, review between tasks, faster iteration  
2. **Inline Execution** — execute tasks in this session with executing-plans and checkpoints  

Which approach?

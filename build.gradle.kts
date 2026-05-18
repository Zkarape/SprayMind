plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "2.2.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.20" apply false
    // KSP version must match Kotlin version prefix (2.2.0-X.Y.Z).
    id("com.google.devtools.ksp") version "2.2.20-2.0.4" apply false
}

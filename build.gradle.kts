buildscript {
    dependencies {
        // O AGP 9 já traz o Kotlin embutido; aqui só fixamos uma versão mais nova dele.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

// O SSD_EXT é exFAT: o macOS cria arquivos ._* ali, e o build do Android quebra
// ao encontrá-los. Por isso a saída do build vai para o disco interno.
allprojects {
    layout.buildDirectory = file("/Volumes/AndroidDev/builds/iptv-tv/${path.replace(':', '_')}")
}

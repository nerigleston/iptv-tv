#!/usr/bin/env bash
# Build do APK dentro do Docker, para quando o volume AndroidDev não estiver montado.
# SDK, cache do Gradle e saída do build ficam em volumes do Docker (só o 1º build baixa tudo).
# Uso: ./docker-build.sh [tarefa]   (padrão: assembleDebug)
# O APK é copiado para ./saida-docker/.
set -euo pipefail
cd "$(dirname "$0")"

TASK="${1:-assembleDebug}"
mkdir -p saida-docker
printf 'sdk.dir=/sdk\n' > saida-docker/local.properties

# amd64 porque o aapt2 que o AGP baixa só existe para Linux x86_64.
docker run --rm --platform linux/amd64 \
  -v "$PWD":/src \
  -v "$PWD/saida-docker/local.properties":/src/local.properties:ro \
  -v iptv-android-sdk:/sdk \
  -v iptv-gradle-home:/gradle \
  -v iptv-build:/Volumes/AndroidDev/builds \
  -v "$HOME/.android/debug.keystore":/root/.android/debug.keystore:ro \
  -e GRADLE_USER_HOME=/gradle \
  -e ANDROID_HOME=/sdk \
  -w /src \
  eclipse-temurin:21-jdk \
  bash -euc '
    if [ ! -x /sdk/cmdline-tools/latest/bin/sdkmanager ]; then
      echo ">> Instalando Android SDK (só na primeira vez)"
      apt-get update -qq && apt-get install -y -qq unzip >/dev/null
      curl -fsSL -o /tmp/tools.zip https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip
      mkdir -p /sdk/cmdline-tools && unzip -q /tmp/tools.zip -d /sdk/cmdline-tools
      mv /sdk/cmdline-tools/cmdline-tools /sdk/cmdline-tools/latest
      yes | /sdk/cmdline-tools/latest/bin/sdkmanager --licenses >/dev/null
      /sdk/cmdline-tools/latest/bin/sdkmanager "platform-tools" "platforms;android-37.0" "build-tools;37.0.0" >/dev/null
    fi
    ./gradlew --no-daemon --console=plain \
      --project-cache-dir /gradle/project-cache/iptv-tv \
      -Pkotlin.project.persistent.dir=/gradle/kotlin/iptv-tv \
      ":app:'"$TASK"'"
    find /Volumes/AndroidDev/builds/iptv-tv -name "*.apk" -path "*outputs*" -exec cp {} /src/saida-docker/ \;
  '

ls -lh saida-docker/*.apk 2>/dev/null || true

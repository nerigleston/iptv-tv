# IPTV para Google TV

Player de listas M3U para a TV TCL (Google TV), feito com Compose for TV e Media3.

## Build

O SSD_EXT é exFAT e o macOS cria arquivos `._*` nele, o que quebra o build do Android.
Por isso a saída do build e os caches ficam na imagem APFS `/Volumes/AndroidDev` (veja `build.gradle.kts` e `gradle.properties`).
(veja `build.gradle.kts` e `gradle.properties`).

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
# APK: /Volumes/AndroidDev/builds/iptv-tv/_app/outputs/apk/debug/app-debug.apk
```

## Instalar na TV

1. Na TV, abra Configurações → Sistema → Sobre e clique 7× em "Build do sistema operacional".
2. Em Configurações → Sistema → Opções do desenvolvedor, ative a "Depuração USB".
3. No Mac, com a TV na mesma rede:

```bash
adb connect <IP-da-TV>:5555        # aceite o aviso que aparece na TV
adb install -r /Volumes/AndroidDev/builds/iptv-tv/_app/outputs/apk/debug/app-debug.apk
```

## Mandar a lista sem digitar no controle

```bash
adb shell "am start -n dev.neri.iptv/.MainActivity --es playlist_url 'http://servidor/lista.m3u'"
```

As aspas simples em volta da URL são necessárias quando ela tem `&`.

## Tipos de lista

- **Xtream Codes** (`get.php?username=…&password=…`): o app usa a API `player_api.php` em vez do M3U.
  Ao vivo, filmes e séries vêm separados por categoria, e os episódios só são buscados ao abrir a série.
  O guia vem do `get_short_epg` de cada canal. Use `output=ts` na URL para canais em MPEG-TS.
- **M3U comum**: separado pela URL (`/movie/`, `/series/`, extensão `.mp4`/`.mkv`) e pelo padrão `S01E02` no nome.
  O guia vem do `url-tvg` da lista ou da URL de EPG (XMLTV) configurada.

## Controles

- **Lista de canais:** OK toca na prévia, OK de novo abre a tela cheia, segurar OK favorita.
- **Tela cheia ao vivo:** ↑/↓ ou CH+/CH− trocam de canal, OK mostra as informações e Voltar sai da tela cheia.
- **Filmes e séries:** OK pausa ou continua, ←/→ voltam ou avançam 10 s e CH+/CH− passam para o próximo episódio ou voltam ao anterior.
- **Busca:** OK no campo abre o teclado; ↑/↓ saem do campo.

## Publicar o APK (instalação pelo app Downloader na TV)

```bash
./gradlew assembleRelease
aws s3 cp /Volumes/AndroidDev/builds/iptv-tv/_app/outputs/apk/release/app-release.apk s3://neri-tv/iptv.apk \
  --profile default --content-type application/vnd.android.package-archive --cache-control no-cache
```

Na TV, no Downloader: `neri-tv.s3.amazonaws.com/iptv.apk`. O bucket só libera leitura desse arquivo.

O release é assinado com a chave de debug (`~/.android/debug.keystore`). Se ela mudar,
a TV recusa a atualização e é preciso desinstalar o app antes (perdendo listas e recentes).

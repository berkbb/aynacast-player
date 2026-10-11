# AynaCast TV – Canlı ADB Test Planı

Bu belge, AynaCast TV'nin gerçek bir Android TV ve gerçek Apple cihazlarla (iPhone/iPad/Mac) uçtan uca
doğrulanması içindir. Her testte ne yapılacağı, ekranda ne görülmesi gerektiği ve hangi log satırlarının
çıkması gerektiği yazılıdır. Sonuçları en alttaki tabloya işleyip `tv-logs/` klasörüyle birlikte geri gönderin.

> Otomatik testler (126 yerel ağ testi, 41 birim testi) protokolü doğruluyor; bu plan ise yalnızca gerçek
> cihazda görülebilecek şeyleri (görüntü, ses, gecikme, Apple'ın gerçek davranışı, TV donanımı) hedefliyor.

---

## 0. Hazırlık

### Gerekenler
- Android TV (Android 9+), geliştirici seçenekleri açık.
- Aynı Wi-Fi/LAN ağında: en az bir iPhone/iPad, mümkünse bir Mac ve **ikinci bir Apple cihaz** (devralma testi için).
- Bilgisayarda Android platform-tools (`adb`). macOS'ta `dns-sd`, `nc`, `curl` hazır gelir.
- APK: depoyu derleyin (`./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/`) veya
  `./package_tv.sh release` ile `dist/` altında üretilen paketi kullanın.

### TV'de ağ üzerinden ADB
1. Ayarlar → Cihaz Tercihleri → Hakkında → **Derleme numarası**'na 7 kez basın.
2. Geliştirici seçenekleri → **USB hata ayıklama** ve (varsa) **Ağ üzerinden hata ayıklama / ADB over network**'ü açın.
3. TV'nin IP adresini not edin (Ayarlar → Ağ).
4. Android 11+ "Kablosuz hata ayıklama" kullanıyorsa önce eşleştirin: `adb pair IP:EŞLEŞTİRME_PORTU`.

### Ortam değişkenleri ve yardımcı fonksiyonlar (her terminalde bir kez)
```bash
export TV=192.168.1.50:5555          # TV'nin IP:port'u
export TV_IP=${TV%:*}
export PKG=com.babadogan.aynacast.tv
mkdir -p tv-logs

# Bir testin logunu temizleyip kaydeder; test bitince Ctrl+C
tvlog() {
  adb -s "$TV" logcat -c
  adb -s "$TV" logcat -v time \
    AirPlayRTSP:V AirPlayCrypto:V AirPlayNTP:V AirPlayVideo:V AirPlayJNI:V H264Parser:V \
    AirPlayDaemonService:V AirPlayNativeBridge:V VideoDecoderEngine:V AudioPlaybackEngine:V \
    PinPairingManager:V VideoCastActivity:V BootReceiver:V DisplayHelper:V \
    AndroidRuntime:E libc:F DEBUG:F *:S | tee "tv-logs/$1.log"
}
```

### Bağlan, kur, izinleri ver
```bash
adb connect "$TV"
adb -s "$TV" install -r app/build/outputs/apk/debug/app-debug.apk
# Arka planda ekran açabilmesi için (yoksa bildirim yedeği devreye girer – bkz. Test 10)
adb -s "$TV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
# Android 13+ bildirim izni
adb -s "$TV" shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS
adb -s "$TV" shell getprop ro.build.version.release   # Android sürümü (sonuç tablosuna yazın)
adb -s "$TV" shell getprop ro.product.model            # TV modeli
```

---

## 1. Kurulum ve servis başlangıcı

**Adımlar**
```bash
tvlog 01-baslangic     # ayrı bir terminalde açık kalsın
adb -s "$TV" shell am start -n "$PKG/.ui.AirPlaySettingsActivity"
adb -s "$TV" shell dumpsys activity services "$PKG" | grep -E "ServiceRecord|isForeground"
```

**Beklenen ekran:** Ayarlar açılır, "Servis Durumu: Aktif" (yeşil nokta).

**Beklenen log**
- `AirPlay Daemon Service creating.`
- `Persistent identity loaded. Public key: <hex>` → bu anahtarı not edin (Test 11'de karşılaştırılacak).
- `AirPlay RTSP Server started on port 7000, Video TCP on port 7100, Audio UDP on 7011`
- `_airplay._tcp registered successfully: ...` ve `_raop._tcp registered successfully: ...`
- `Broadcasting AirPlay device name: '...', Device ID: '...', PK: '...'`
- `dumpsys` çıktısında servis `isForeground=true`.

**Başarısız sayılır:** `AirPlay server failed to start`, `could not be started`, `Native AirPlay core unavailable`.

---

## 2. Ağda görünürlük (Mac'ten)

```bash
dns-sd -B _airplay._tcp local.        # TV'nin adı listede görünmeli (Ctrl+C)
dns-sd -B _raop._tcp local.           # "<MAC>@<TV adı>" görünmeli
dns-sd -L "<TV adı>" _airplay._tcp local.   # TXT kaydı
nc -vz "$TV_IP" 7000 && nc -vz "$TV_IP" 7100
curl -s "http://$TV_IP:7000/server-info"
```

**Beklenen**
- TXT içinde `features=0x5A7FFFF7,0x1E`, `model=AppleTV...`, `pk=<Test 1'deki anahtar>`; PIN kapalıyken `pw` yok/`false`.
- Portlar açık; `/server-info` bir XML plist döner (`features` 639, `model` AppleTV3,2).
- iPhone'da Denetim Merkezi → Ekran Yansıtma listesinde TV görünür.

---

## 3. iPhone ekran yansıtma (görüntü + ses)

**Adımlar:** `tvlog 03-yansitma-iphone` → iPhone Denetim Merkezi → Ekran Yansıtma → TV.
Ardından sırayla: ses çalan bir video açın, iPhone'u yatay/dikey çevirin, bir listeyi hızlıca kaydırıp
**bırakın**, ekranı birkaç saniye sabit tutun, iPhone'u kilitleyip açın.

**Beklenen ekran**
- 1–2 saniye içinde tam ekran oynatıcı açılır; görüntü akıcı, bozulma yok.
- Dikey iPhone kenarlarda siyah bantla ortalanır, yatayda ekranı doldurur.
- **Kaydırma bitince son konum hemen görünür** (bir sonraki ekran değişikliğini beklemez).
- Ses TV'den gelir, cızırtı/kesik yok; iPhone'dan ses açma-kısma TV sesini değiştirir.

**Beklenen log**
- `Accepted incoming AirPlay connection #N from <iPhone IP>`
- `Pair-verify Step 2 completed successfully. Channel authenticated.`
- `NTP timing started: ...` ve kısa süre sonra `NTP synchronized with sender (round trip ... ms)`
- `Responded to Video SETUP with streams: dataPort=7100, type=110`
- `AirPlay session started (RECORD).` / `Mirrored video started. Launching AirPlayPlayerActivity on-demand.`
- `MediaCodec started on worker thread: <codec adı> (1920x1080...)` – codec adı **OMX/c2 donanım** olmalı, `software=false`
- Her saniye `Rendering video successfully at NN fps, X.XX Mbps`
- `Audio configured: codec=8 44100 Hz` (yansıtma sesi AAC-ELD)
- Kilitleyince `Sender paused the video stream (display asleep)`, açınca `Sender resumed the video stream`

**Başarısız sayılır:** `Attempting software decoder fallback`, `Decoder is 120 frames behind`, sürekli
`Dropping frame`, `AAC decoder keeps failing`, `Incorrect pair-verify signature`.

**Ölçüm (isteğe bağlı):** iPhone'da kronometre açıp TV ile birlikte fotoğraflayın → gecikme (ms) yazın.

---

## 4. Mac ekran yansıtma

**Adımlar:** `tvlog 04-yansitma-mac` → Mac Denetim Merkezi → Ekran Yansıtma → TV. "Yerleşik ekranı
yansıt" ve "Ayrı ekran olarak kullan" modlarının ikisini de deneyin; Mac'te ses çalın.

**Beklenen:** Test 3 ile aynı; Mac adı (ör. "Berk’s MacBook Pro") ayarlar ekranında **doğru karakterlerle**
görünür (`?` olmamalı). Ayrı ekran modunda da görüntü gelir.

---

## 5. Oturum dayanıklılığı

| # | Adım | Beklenen ekran | Beklenen log |
|---|------|----------------|--------------|
| 5a | Yansıtmayı iPhone'dan durdur | Oynatıcı kapanır, önceki TV uygulamasına dönülür | `Ending AirPlay session: TEARDOWN` |
| 5b | Yansıtırken iPhone'da Wi-Fi'yi kapat | ≤ ~15 sn içinde oynatıcı kapanır | `No request from the session owner for 15 s` veya `closed without TEARDOWN` |
| 5c | 5b'den sonra Wi-Fi'yi aç, hemen yeniden yansıt | Bekletmeden yeniden bağlanır | gerekirse `takes over the session from` |
| 5d | Yansıtırken TV kumandasında **Home**, sonra uygulamayı açıp **"Yansıtmayı Göster"** | Görüntü geri gelir, hızlı-ileri sarma/eski kare gösterisi yok | `Replaying N buffered frame(s) from the last keyframe` |
| 5e | iPhone A yansıtırken **iPhone B** TV'yi seçer | A'nın yansıtması biter, B'nin görüntüsü gelir | `Connection #M takes over the session from #N` |
| 5f | Yansıtma sürerken **başka bir bilgisayardan** `nc -vz $TV_IP 7100` | Yansıtma kesilmez | `Ignoring a video connection from <o bilgisayarın IP'si>` |

> 5f'yi yansıtma yapan cihazdan yapmayın: aynı IP'den gelen bağlantı meşru bir yeniden bağlanma sayılır.

---

## 6. Yalnızca ses (Müzik) ve Now Playing ekranı

**Adımlar:** `tvlog 06-muzik` → iPhone Müzik/Spotify'da AirPlay simgesi → TV. Şarkıyı duraklat/devam
ettir, sonraki şarkıya geç, iPhone'dan ses seviyesini değiştir, TV'de **Geri** tuşuna bas, 5 dk dinle.

**Beklenen ekran:** Kapak resmi, şarkı adı, sanatçı, albüm ve ilerleyen çubuk görünür (Türkçe karakterler
düzgün). Şarkı değişince bilgiler güncellenir. Geri ile ekran kapanır, **müzik çalmaya devam eder**.
Yansıtma oynatıcısı (siyah ekran) açılmaz.

**Beklenen log**
- `Audio stream configured: ct=2 ...` (ALAC) veya `ct=4` (AAC-LC); Kotlin'de `Audio configured: codec=0` / `codec=4`
- `Now playing: '<şarkı>' by '<sanatçı>' (<albüm>)`, `Cover art received (... bytes, image/jpeg)`
- `Sender volume changed: -NN.NN dB`

**Başarısız sayılır:** 5 dakikada duyulabilir kesik/tıkırtı, `Unsupported audio compression type`, ekranda `?` karakterleri.

---

## 7. Video oynatma (yansıtmadan, uygulamanın AirPlay düğmesiyle)

### 7a. Safari / doğrudan URL
iPhone Safari'de bir video açın (ör. Apple örnek akışı
`https://devstreaming-cdn.apple.com/videos/streaming/examples/img_bipbop_adv_example_ts/master.m3u8`;
adres değişmişse herhangi bir web videosu), oynatıcıdaki AirPlay simgesi → TV. `tvlog 07a-safari`.

| Kontrol | Beklenen |
|---------|----------|
| Başlangıç | TV'de tam ekran oynatıcı açılır, video oynar; iPhone "TV'de oynatılıyor" gösterir |
| iPhone'dan duraklat / oynat / ileri sar | TV aynısını yapar, iPhone'daki süre çubuğu TV ile uyumlu |
| TV kumandası: OK = duraklat/oynat, Sol/Sağ = ∓10 sn | Çalışır |
| **Kumandayla duraklat → iPhone'dan oynat** | TV oynatmaya devam eder |
| iPhone'dan durdur | TV oynatıcısı kapanır |
| TV'de **Geri** | Oynatıcı kapanır, iPhone oynatmanın bittiğini gösterir |
| TV'de **Home** | Video durur (arkada görünmeden çalmaya devam etmez) |
| Video sonuna kadar oynasın | Oynatıcı kapanır, iPhone bitişi gösterir |

**Beklenen log:** `Video casting requested: https://... (start X s)`, `Casting https://... from Xs`,
sarmada `Sender seeks to NN.NN s`, TV tarafında bitişte `Video casting ended on the receiver; closing the casting session`.

### 7b. YouTube uygulaması
YouTube'da bir video → AirPlay simgesi → TV. `tvlog 07b-youtube`. Oynarken ileri sarın, duraklatın;
sonra **hemen başka bir videoya** geçin; en sonunda iPhone'dan bağlantıyı kesin.

**Beklenen log (sırayla)**
- `Video casting requested: mlhls://localhost/master.m3u8`
- `FCUP request #N for mlhls://localhost/master.m3u8`
- `Master playlist received: K media playlists to fetch`
- `FCUP request #...` (her medya listesi için)
- `All playlists fetched; starting playback of http://localhost:7000/master.m3u8`
- `Casting http://localhost:7000/master.m3u8 from ...`
- Video değiştirince yeni bir `Video casting requested`; varsa `Ignoring a playlist reply that belongs to no current request` (normal)

**Başarısız sayılır:** `The sender could not provide the master playlist; stopping`, oynatıcı açılıp siyah
kalması, `Video casting playback failed`. Bu durumda ilgili logu mutlaka gönderin (bkz. Sorun Giderme).

### 7c. DRM'li uygulama (beklenen: desteklenmez)
Netflix / Apple TV+ / Disney+'ta AirPlay deneyin. **Beklenen:** uygulama çökmez; iPhone hata gösterir veya
yansıtmaya düşer. Log: `HTTP Request: POST /fp-setup2`. (FairPlay korumalı içerik bilinçli olarak desteklenmiyor.)

---

## 8. PIN ile eşleşme

**Hazırlık:** Ayarlar → **Bağlantı Kodu (PIN)** → Açık. Mac'te `dns-sd -L "<TV adı>" _airplay._tcp local.`
ile `pw=true` görüldüğünü kontrol edin. `tvlog 08-pin`.

| # | Adım | Beklenen |
|---|------|----------|
| 8a | iPhone ilk kez bağlanır | TV'de 4 haneli kod, iPhone kod ister; doğru kodla yansıtma başlar. Log: `Sender requested on-screen PIN pairing`, `PIN pairing started`, `Device paired with PIN`, `PIN pairing completed; device registered` |
| 8b | Aynı iPhone yeniden bağlanır | Kod sorulmaz |
| 8c | Yeni cihazda bir kez **yanlış** kod | iPhone yeniden sorar; TV'de ekrandaki kod (iPhone baştan başlatırsa yeni bir kod) girilince eşleşir. Log: `Wrong PIN entered on the sender` |
| 8d | Kod ekranındayken 2 dk bekle | Kod ekranı kendiliğinden kapanır. Log: `PIN code expired` |
| 8e | Servisi yeniden başlat (Ayarlar → "Servisi Yeniden Başlat") ve TV'yi yeniden başlat (`adb -s $TV reboot`) | 8a'daki iPhone **kod sorulmadan** bağlanır (kalıcı kimlik) |
| 8f | Ayarlar → "Eşleşmiş Cihazları Unut (N)" | Sonraki bağlantıda kod tekrar sorulur |
| 8g | Bir cihazla art arda 9 kez yanlış kod | 5 dk yeni kod gösterilmez. Log: `Too many wrong codes; PIN pairing locked for 300 s` |
| 8h | Video oynatma (Test 7a) sürerken **ikinci bir** cihaz TV'yi seçer | Kod ekranı açılır, devam eden video **kapanmaz** |

Testten sonra PIN'i tekrar **Kapalı** yapın (varsayılan).

---

## 9. Ses-görüntü senkronu (isteğe bağlı ayar)

**Adımlar:** YouTube'da "audio video sync test" / "lip sync test" videosunu **yansıtma** modunda
(Test 3 gibi) oynatın. Önce ayar **Kapalı**, sonra Ayarlar → **Ses-Görüntü Senkronu (Zaman Damgası)** → Açık.
Ayarı yansıtma sürerken değiştirin. `tvlog 09-senkron`.

**Beklenen**
- Kapalıyken: en düşük gecikme; dudak senkronu TV'ye göre biraz kayık olabilir (not alın: ses önde mi geride mi, yaklaşık kaç ms).
- Açıkken: bip ile flaş çakışır; ses kesilmez, görüntü takılmaz. Ayar değişikliği **hemen** etkili olur.
- Log: `Timestamp audio/video sync enabled`.

**Başarısız sayılır:** Açıkken sesin tamamen kesilmesi, düzenli tıkırtı, sürekli `Dropping frame`, fps'in
belirgin düşmesi (`Rendering video successfully at NN fps` değerlerini karşılaştırın).

---

## 10. Arka plandan açılma yedeği (izin yokken)

```bash
adb -s "$TV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW deny
```
TV'de başka bir uygulama (ör. YouTube) açıkken: yansıtma başlatın; PIN açıkken yeni cihaz bağlayın; video oynatın.

**Beklenen:** Ekranlar kendiliğinden açılamazsa bildirim gelir; bildirime tıklayınca ilgili ekran açılır.
PIN bildiriminde **kodun kendisi** yazar. Ekran görününce bildirim kaybolur.
Log: `Could not open ...` olabilir; uygulama çökmemeli. Test sonunda:
```bash
adb -s "$TV" shell appops set "$PKG" SYSTEM_ALERT_WINDOW allow
```

---

## 11. Açılışta otomatik başlama ve kalıcı kimlik

```bash
adb -s "$TV" reboot
# TV açılınca (uygulamayı açmadan):
adb connect "$TV"; tvlog 11-acilis
```
**Beklenen:** Uygulama açılmadan TV, iPhone'un Ekran Yansıtma listesinde görünür.
Log: `BootReceiver` satırları ve `Persistent identity loaded. Public key: <Test 1 ile AYNI hex>`.

---

## 12. Uzun süre ve kaynak kullanımı

30 dakika kesintisiz yansıtma (video + ses), sonra 30 dakika müzik. Bu sırada iki kez:
```bash
adb -s "$TV" shell top -b -n 1 | grep -i aynacast       # CPU %
adb -s "$TV" shell dumpsys meminfo "$PKG" | grep -E "TOTAL|Native Heap"
adb -s "$TV" logcat -b crash -d                          # çökme kaydı (boş olmalı)
```
**Beklenen:** Çökme/ANR yok; bellek sürekli artmıyor (ilk ve son ölçüm yakın); sabit ekranda CPU makul
(yansıtma sürerken ölçülen değerleri tabloya yazın). Görüntü/ses sonradan kaymıyor.

---

## Sorun giderme

- **TV listede görünmüyor:** Test 2'deki `dns-sd` çıktısını ve `tv-logs/01-baslangic.log`'u gönderin. Router'da
  "AP/istemci yalıtımı" ve multicast/mDNS engeli olmadığını kontrol edin.
- **Siyah ekran:** `MediaCodec started` ve `Rendering video successfully` satırları var mı bakın; codec adını yazın.
- **YouTube oynatmıyor:** `07b-youtube.log` dosyasını gönderin. Bilinen tek şüphe: AirPlay özellik
  bitlerinin üst kelimesi (`0x1E`) UxPlay'den (`0x0`) farklı; gerekirse `AirPlayFeatures.kt` içinde denenecek.
- **Ekran görüntüsü:** `adb -s "$TV" exec-out screencap -p > tv-logs/ekran.png`
  (bazı TV'lerde video katmanı ekran görüntüsünde siyah çıkar; bu bir hata değildir).
- **Çökme:** `adb -s "$TV" logcat -b crash -d > tv-logs/crash.log` ve mümkünse `adb -s "$TV" bugreport tv-logs/bugreport.zip`.

---

## Sonuç tablosu (doldurup gönderin)

TV modeli / Android sürümü: ……………… · iPhone modeli / iOS: ……………… · Mac / macOS: ………………

| Test | Sonuç (✅/❌/–) | Not (gecikme, fps, gözlem) |
|------|-----------------|----------------------------|
| 1 Kurulum ve servis | | |
| 2 Ağda görünürlük | | |
| 3 iPhone yansıtma | | |
| 4 Mac yansıtma | | |
| 5a–5f Oturum dayanıklılığı | | |
| 6 Müzik + Now Playing | | |
| 7a Safari / URL video | | |
| 7b YouTube | | |
| 7c DRM (beklenen: desteklenmez) | | |
| 8a–8h PIN eşleşme | | |
| 9 Ses-görüntü senkronu | | |
| 10 Bildirim yedeği | | |
| 11 Açılış + kalıcı kimlik | | |
| 12 Uzun süre / kaynak | | |

Gönderilecekler: bu tablo + `tv-logs/` klasörünün tamamı.

package com.babadogan.aynacast.tv.ui.localization

import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.model.AppThemeMode

object AirPlayStrings {

    fun appName(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AynaCast TV - AirPlay Yansıtıcı"
        AppLanguage.EN -> "AynaCast TV - AirPlay Receiver"
    }

    fun appSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Apple cihazlarınızdan (iPhone, iPad, Mac) bu ekrana anında görüntü ve ses yansıtın (AirPlay Mirroring)."
        AppLanguage.EN -> "Stream screen and audio directly from your Apple devices (iPhone, iPad, Mac) to this TV via AirPlay."
    }

    fun tabSettings(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "⚙️ Ayarlar"
        AppLanguage.EN -> "⚙️ Settings"
    }

    fun tabAbout(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "ℹ️ Hakkında & Bilgi"
        AppLanguage.EN -> "ℹ️ About & Info"
    }

    fun deviceInfoTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Cihaz ve Ağ Bilgileri"
        AppLanguage.EN -> "Device & Network Information"
    }

    fun deviceInfoPort(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Protokol & Port: RTSP 7000 • Video TCP 7100 • Ses UDP 7011"
        AppLanguage.EN -> "Protocol & Port: RTSP 7000 • Video TCP 7100 • Audio UDP 7011"
    }

    fun deviceInfoResolution(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Çözünürlük: 1080p 60 FPS • MediaCodec Donanım AVC"
        AppLanguage.EN -> "Resolution: 1080p 60 FPS • MediaCodec Hardware AVC"
    }

    fun deviceInfoMac(mac: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Cihaz Kimliği (MAC): $mac"
        AppLanguage.EN -> "Device ID (MAC): $mac"
    }

    fun serviceStatusActive(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Servis Durumu: Aktif"
        AppLanguage.EN -> "Service Status: Active"
    }

    fun serviceStatusStopped(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Servis Durumu: Durduruldu"
        AppLanguage.EN -> "Service Status: Stopped"
    }

    fun statusIdle(deviceName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bağlantı bekleniyor. TV ağ üzerinde '$deviceName' olarak yayın yapıyor."
        AppLanguage.EN -> "Waiting for connection. TV is broadcasting as '$deviceName' on the local network."
    }

    fun statusConnecting(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Cihaz bağlandı: $clientName. Akış hazırlanıyor..."
        AppLanguage.EN -> "Device connected: $clientName. Preparing stream..."
    }

    fun statusPlayingAudio(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Müzik çalıyor: $clientName"
        AppLanguage.EN -> "Playing audio from $clientName"
    }

    fun statusConnectedIdle(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bağlı, yayın yok: $clientName"
        AppLanguage.EN -> "Connected, nothing playing: $clientName"
    }

    fun receiverOffStatus(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay Kapalı"
        AppLanguage.EN -> "AirPlay Off"
    }

    fun receiverOffDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "TV Apple cihazlarında görünmüyor ve bağlantı kabul etmiyor."
        AppLanguage.EN -> "The TV is hidden from Apple devices and accepts no connections."
    }

    fun stopStreaming(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yayını Durdur"
        AppLanguage.EN -> "Stop Streaming"
    }

    fun receiverStop(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay'i Durdur"
        AppLanguage.EN -> "Stop AirPlay"
    }

    fun receiverStart(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay'i Başlat"
        AppLanguage.EN -> "Start AirPlay"
    }

    fun statusStreaming(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Ekran yansıtılıyor: $clientName"
        AppLanguage.EN -> "Screen mirroring active: $clientName"
    }

    fun statusDisconnected(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Son oturum tamamlandı. Servis arka planda dinlemeye devam ediyor."
        AppLanguage.EN -> "Session ended. Background service continues listening for incoming connections."
    }

    fun howToConnectTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Nasıl Bağlanılır?"
        AppLanguage.EN -> "How to Connect?"
    }

    fun howToConnectInstructions(deviceName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "1. iPhone veya Mac'inizin bu TV ile aynı Wi-Fi ağına bağlı olduğundan emin olun.\n" +
                          "2. Denetim Merkezi'ni (Control Center) açın.\n" +
                          "3. 'Ekran Yansıtma' (Screen Mirroring) simgesine dokunun.\n" +
                          "4. Listeden '$deviceName' seçeneğine tıklayın."
        AppLanguage.EN -> "1. Ensure your iPhone or Mac is connected to the same Wi-Fi network as this TV.\n" +
                          "2. Open the Control Center on your Apple device.\n" +
                          "3. Tap the 'Screen Mirroring' icon.\n" +
                          "4. Select '$deviceName' from the list."
    }

    fun restartService(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Servisi Yeniden Başlat"
        AppLanguage.EN -> "Restart Service"
    }

    fun settingsSectionAppearance(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Görünüm ve Tema"
        AppLanguage.EN -> "Appearance & Theme"
    }

    fun settingsSectionLanguage(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Uygulama Dili"
        AppLanguage.EN -> "App Language"
    }

    fun settingKeepAliveTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "7/24 Arka Planda Çalışma"
        AppLanguage.EN -> "24/7 Background Service"
    }

    fun settingKeepAliveDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Açıkken TV açıldığında servis otomatik başlar ve uygulama kapalıyken de yayın almaya devam eder. Kapalıyken yalnızca uygulama açıkken çalışır."
        AppLanguage.EN -> "When enabled, the service starts on TV boot and listens in the background. When disabled, it runs only while the app is active."
    }

    fun keepAliveEnabled(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "7/24 Arka Plan Açık (Önerilen)"
        AppLanguage.EN -> "24/7 Always Active (Recommended)"
    }

    fun keepAliveDisabled(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yalnızca Uygulama Açıkken"
        AppLanguage.EN -> "Only While App is Open"
    }

    fun themeName(theme: AppThemeMode, lang: AppLanguage): String = when (theme) {
        AppThemeMode.SYSTEM -> if (lang == AppLanguage.TR) "Sistem" else "System"
        AppThemeMode.DARK -> if (lang == AppLanguage.TR) "Koyu" else "Dark"
        AppThemeMode.LIGHT -> if (lang == AppLanguage.TR) "Açık" else "Light"
    }

    fun languageName(language: AppLanguage, lang: AppLanguage): String = when (language) {
        AppLanguage.TR -> if (lang == AppLanguage.TR) "Türkçe (Varsayılan)" else "Turkish (Default)"
        AppLanguage.EN -> if (lang == AppLanguage.TR) "İngilizce" else "English"
    }

    fun airplayActiveBadge(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay Aktif"
        AppLanguage.EN -> "AirPlay Active"
    }

    fun aboutVersion(version: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Sürüm: v$version"
        AppLanguage.EN -> "Version: v$version"
    }

    fun aboutArchitectureTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Mimari Tasarım"
        AppLanguage.EN -> "Architecture Design"
    }

    fun aboutArchitectureDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Arka Plan Daemon Servisi (Foreground Daemon) + İhtiyaç Anında Açılan Compose TV Arayüzü (On-Demand UI)."
        AppLanguage.EN -> "Foreground Daemon Service + On-Demand Jetpack Compose for TV Interface."
    }

    fun aboutProtocolsTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Desteklenen Protokoller ve Motorlar"
        AppLanguage.EN -> "Supported Protocols & Engines"
    }

    fun aboutProtocolsDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Apple AirPlay Mirroring (RTSP 7000), mDNS/DNS-SD (_airplay._tcp, _raop._tcp), MediaCodec AVC H.264 Donanım Çözücü, AAC-ELD / ALAC Ses, AudioTrack."
        AppLanguage.EN -> "Apple AirPlay Mirroring (RTSP 7000), mDNS/DNS-SD (_airplay._tcp, _raop._tcp), MediaCodec AVC H.264 Hardware Decoder, AAC-ELD / ALAC Audio, AudioTrack."
    }

    fun aboutDeveloperTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Geliştirici ve Telif"
        AppLanguage.EN -> "Developer & Copyright"
    }

    fun aboutDeveloperDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Babadogan Tech & Edu — Ankara Üniversitesi Teknokent"
        AppLanguage.EN -> "Babadogan Tech & Edu — Ankara University Technopolis"
    }

    fun aboutLicenseTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Lisans"
        AppLanguage.EN -> "License"
    }

    fun aboutLicenseDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "MIT Açık Kaynak Lisansı ile lisanslanmıştır."
        AppLanguage.EN -> "Licensed under the MIT Open Source License."
    }

    fun aboutDisclaimerTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yasal Uyarı ve Marka Bildirimi"
        AppLanguage.EN -> "Legal Disclaimer & Trademark Notice"
    }

    fun aboutDisclaimerDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay, Apple TV, iPhone, iPad ve Mac; Apple Inc.'in ABD ve diğer ülkelerde tescilli ticari markalarıdır. Bu bağımsız açık kaynak projesi Apple Inc. ile ilişkili değildir, Apple tarafından onaylanmamış veya desteklenmemiştir. Uygulama yalnızca yerel ağda birlikte çalışabilirlik amacıyla geliştirilmiş olup hiçbir telifli DRM/FairPlay içeriği kırmaz veya şifresini çözmez."
        AppLanguage.EN -> "AirPlay, Apple TV, iPhone, iPad, and Mac are registered trademarks of Apple Inc., registered in the U.S. and other countries. This independent open-source project is not affiliated with, sponsored, or endorsed by Apple Inc. It is developed solely for local network device interoperability and research, and does not decrypt or circumvent DRM-protected streams."
    }

    fun showMirroring(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yansıtmayı Göster"
        AppLanguage.EN -> "Show Mirroring"
    }

    fun serviceStatusStarting(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Servis başlatılıyor veya AirPlay portu (7000) kullanılamıyor. Gerekirse servisi yeniden başlatın."
        AppLanguage.EN -> "The service is starting or the AirPlay port (7000) is unavailable. Restart the service if needed."
    }

    fun overlayPermissionTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Otomatik Açılma İzni"
        AppLanguage.EN -> "Auto-Open Permission"
    }

    fun overlayPermissionDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Android 10 ve üzeri sürümler, başka bir uygulama açıkken yansıtma ekranının kendiliğinden açılması için \"Diğer uygulamaların üzerinde göster\" iznini ister. İzin verilmezse ekranda kısa bir uyarı çıkar ve yansıtmayı görmek için AynaCast TV'yi açmanız gerekir."
        AppLanguage.EN -> "Android 10 and later require the \"Display over other apps\" permission so the mirroring screen can open by itself while another app is in use. Without it, a short message appears on screen and you have to open AynaCast TV to see the mirroring."
    }

    fun overlayPermissionLater(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Daha Sonra"
        AppLanguage.EN -> "Later"
    }

    /** Toast over another app when a screen could not open by itself (no overlay permission). */
    fun toastOpenApp(message: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "$message \"Diğer uygulamaların üzerinde göster\" izni verilmediği için ekran öne getirilemedi; görmek için AynaCast TV'yi açın."
        AppLanguage.EN -> "$message The screen could not come to the front because \"Display over other apps\" is not allowed; open AynaCast TV to see it."
    }

    fun toastPin(pin: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay kodu: $pin — iPhone, iPad veya Mac'inizde girin. (İzin verilmediği için kod ekranı öne getirilemedi.)"
        AppLanguage.EN -> "AirPlay code: $pin — enter it on your iPhone, iPad or Mac. (The code screen could not come to the front: permission not granted.)"
    }

    fun overlayPermissionButton(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "İzni Ver"
        AppLanguage.EN -> "Grant Permission"
    }

    fun overlayPermissionUnavailable(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bu TV izin ekranını sunmuyor. Bilgisayardan şu komutla verebilirsiniz: adb shell appops set com.babadogan.aynacast.tv SYSTEM_ALERT_WINDOW allow"
        AppLanguage.EN -> "This TV does not offer the permission screen. Grant it from a computer with: adb shell appops set com.babadogan.aynacast.tv SYSTEM_ALERT_WINDOW allow"
    }

    fun notificationServiceChannel(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay Alıcı Servisi"
        AppLanguage.EN -> "AirPlay Receiver Service"
    }

    fun notificationServiceChannelDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Gelen AirPlay bağlantılarını arka planda dinler."
        AppLanguage.EN -> "Listens for incoming AirPlay connections in the background."
    }

    fun notificationServiceTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay Alıcısı Aktif"
        AppLanguage.EN -> "AirPlay Receiver Active"
    }

    fun notificationServiceText(deviceName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "'$deviceName' ağda yayınlanıyor (Apple cihazlarından yansıtabilirsiniz)"
        AppLanguage.EN -> "Broadcasting as '$deviceName' (mirror from your Apple devices)"
    }

    fun notificationStreamChannel(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Ekran Yansıtma"
        AppLanguage.EN -> "Screen Mirroring"
    }

    fun notificationStreamTitle(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "$clientName ekranını yansıtıyor"
        AppLanguage.EN -> "$clientName is mirroring"
    }

    fun notificationStreamText(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yansıtma ekranını açmak için seçin."
        AppLanguage.EN -> "Select to open the mirroring screen."
    }

    fun avSyncTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Ses-Görüntü Senkronu (Zaman Damgası)"
        AppLanguage.EN -> "Audio/Video Sync (Timestamps)"
    }

    fun avSyncDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Açıkken ses ve görüntü göndericinin zaman damgalarına göre eşlenir (UxPlay -vsync); gecikme artar ve geç kalan kareler atlanır. Kapalıyken her şey geldiği anda oynatılır: Mac yansıtma ve donanım çözücülü TV'ler için önerilen en düşük gecikmeli mod."
        AppLanguage.EN -> "When on, audio and video are aligned to the sender's timestamps (UxPlay -vsync); latency increases and late frames are skipped. When off, everything plays as soon as it arrives: the lowest-latency mode recommended for Mac mirroring and hardware-decoding TVs."
    }

    fun avSyncOff(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kapalı – En Düşük Gecikme (Önerilen)"
        AppLanguage.EN -> "Off – Lowest Latency (Recommended)"
    }

    fun avSyncOn(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Açık – Zaman Damgası Senkronu"
        AppLanguage.EN -> "On – Timestamp Sync"
    }

    fun mirrorStartingTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Ekran yansıtma başlıyor"
        AppLanguage.EN -> "Starting screen mirroring"
    }

    fun mirrorStartingSubtitle(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "$clientName ekranı bu TV'ye geliyor"
        AppLanguage.EN -> "$clientName is coming up on this TV"
    }

    fun mirrorEndedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Ekran yansıtma sona erdi"
        AppLanguage.EN -> "Screen mirroring ended"
    }

    fun pinPairedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Eşleşme tamamlandı"
        AppLanguage.EN -> "Paired"
    }

    fun pinPairedSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bu cihaz bundan sonra kod sorulmadan bağlanacak"
        AppLanguage.EN -> "This device will connect without a code from now on"
    }

    fun pinExpiredTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kodun süresi doldu"
        AppLanguage.EN -> "The code expired"
    }

    fun pinWrongCodeTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kod eşleşmedi"
        AppLanguage.EN -> "The code did not match"
    }

    fun pinRetrySubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yeni bir kod için Apple cihazınızdan tekrar bağlanın"
        AppLanguage.EN -> "Connect again from your Apple device for a new code"
    }

    fun pinLockedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Çok fazla hatalı deneme"
        AppLanguage.EN -> "Too many wrong codes"
    }

    fun pinLockedSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "5 dakika sonra tekrar deneyin"
        AppLanguage.EN -> "Try again in 5 minutes"
    }

    fun pinCancelledTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Eşleşme iptal edildi"
        AppLanguage.EN -> "Pairing cancelled"
    }

    fun castPreparingTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Video hazırlanıyor"
        AppLanguage.EN -> "Preparing video"
    }

    fun castPreparingSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay ile bu TV'de oynatılacak"
        AppLanguage.EN -> "It will play on this TV via AirPlay"
    }

    fun castDrmTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bu video korumalı"
        AppLanguage.EN -> "This video is protected"
    }

    fun castDrmSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Netflix, Apple TV+ gibi uygulamaların kopya korumalı (DRM) videoları bu TV'de oynatılamaz"
        AppLanguage.EN -> "Copy-protected (DRM) videos from apps like Netflix or Apple TV+ cannot play on this TV"
    }

    fun castFailedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Video oynatılamadı"
        AppLanguage.EN -> "The video could not be played"
    }

    fun castFailedSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bağlantıyı kontrol edip Apple cihazınızdan tekrar deneyin"
        AppLanguage.EN -> "Check the connection and try again from your Apple device"
    }

    fun castEndedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Oynatma sona erdi"
        AppLanguage.EN -> "Playback ended"
    }

    fun castEndedSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay bağlantısı kapatılıyor"
        AppLanguage.EN -> "Closing the AirPlay connection"
    }

    fun nowPlayingInApp(title: String, artist: String, lang: AppLanguage): String {
        val track = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" – ")
        return when (lang) {
            AppLanguage.TR -> "♪ Çalıyor: $track"
            AppLanguage.EN -> "♪ Playing: $track"
        }
    }

    fun showNowPlaying(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Şimdi Çalınanı Göster"
        AppLanguage.EN -> "Show Now Playing"
    }

    fun nowPlayingFrom(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "$clientName cihazından AirPlay ile çalıyor"
        AppLanguage.EN -> "Playing from $clientName via AirPlay"
    }

    fun nowPlayingUnknownTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bilinmeyen Şarkı"
        AppLanguage.EN -> "Unknown Track"
    }

    fun nowPlayingPaused(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Duraklatıldı"
        AppLanguage.EN -> "Paused"
    }

    fun pinTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay Kodu"
        AppLanguage.EN -> "AirPlay Code"
    }

    fun pinInstructions(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bu kodu iPhone, iPad veya Mac'inizde girin. Kod yalnızca bu cihazdan ilk bağlanışta sorulur."
        AppLanguage.EN -> "Enter this code on your iPhone, iPad or Mac. It is only asked the first time this device connects."
    }

    fun notificationPinText(pin: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "iPhone, iPad veya Mac'inizde şu kodu girin: $pin"
        AppLanguage.EN -> "Enter this code on your iPhone, iPad or Mac: $pin"
    }

    fun notificationVideoCastTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay videosu hazır"
        AppLanguage.EN -> "AirPlay video ready"
    }

    fun notificationVideoCastText(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Oynatmak için seçin."
        AppLanguage.EN -> "Select to play."
    }

    fun pinSettingTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bağlantı Kodu (PIN)"
        AppLanguage.EN -> "Connection Code (PIN)"
    }

    fun pinSettingDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Açıkken yeni bir Apple cihazı ilk kez bağlanırken TV'de 4 haneli bir kod gösterilir ve cihazda girilmesi gerekir; eşleşen cihazlar hatırlanır."
        AppLanguage.EN -> "When on, a new Apple device connecting for the first time must enter the 4-digit code shown on the TV; paired devices are remembered."
    }

    fun pinOff(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kapalı – Kod Sorma"
        AppLanguage.EN -> "Off – No Code"
    }

    fun pinOn(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Açık – İlk Bağlantıda Kod"
        AppLanguage.EN -> "On – Code on First Connection"
    }

    fun pinPairedDevices(count: Int, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Eşleşmiş Cihazlar ($count)"
        AppLanguage.EN -> "Paired Devices ($count)"
    }

    fun pairedDevicesDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kaldırılan cihaz bir sonraki bağlantısında yeniden kod girmelidir."
        AppLanguage.EN -> "A removed device has to enter a code again the next time it connects."
    }

    fun pairedDeviceUnknown(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bilinmeyen cihaz"
        AppLanguage.EN -> "Unknown device"
    }

    fun pairedDeviceSince(date: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Eşleşme: $date"
        AppLanguage.EN -> "Paired: $date"
    }

    fun pairedDeviceRemove(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kaldır"
        AppLanguage.EN -> "Remove"
    }

    fun pairedDevicesForgetAll(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Tümünü Unut"
        AppLanguage.EN -> "Forget All"
    }

    fun close(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kapat"
        AppLanguage.EN -> "Close"
    }
}

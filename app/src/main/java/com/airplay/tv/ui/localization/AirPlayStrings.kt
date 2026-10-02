package com.airplay.tv.ui.localization

import com.airplay.tv.model.AppLanguage
import com.airplay.tv.model.AppThemeMode

object AirPlayStrings {

    fun appName(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay TV Alıcısı"
        AppLanguage.EN -> "AirPlay TV Receiver"
    }

    fun appSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Apple cihazlarınızdan (iPhone, iPad, Mac) bu ekrana anında görüntü ve ses yansıtın."
        AppLanguage.EN -> "Stream screen and audio directly from your Apple devices (iPhone, iPad, Mac) to this TV."
    }

    fun tabSettings(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Ayarlar"
        AppLanguage.EN -> "Settings"
    }

    fun tabAbout(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Hakkında & Bilgi (Info)"
        AppLanguage.EN -> "About & Info"
    }

    fun deviceInfoTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Cihaz ve Ağ Bilgileri"
        AppLanguage.EN -> "Device & Network Information"
    }

    fun deviceInfoPort(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Protokol & Port: RTSP 7000 • Video UDP 7100"
        AppLanguage.EN -> "Protocol & Port: RTSP 7000 • Video UDP 7100"
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
        AppLanguage.TR -> "Apple AirPlay Mirroring (RTSP 7000), mDNS/DNS-SD (_airplay._tcp, _raop._tcp), MediaCodec AVC H.264 Donanım Çözücü, Low-Latency PCM AudioTrack."
        AppLanguage.EN -> "Apple AirPlay Mirroring (RTSP 7000), mDNS/DNS-SD (_airplay._tcp, _raop._tcp), MediaCodec AVC H.264 Hardware Decoder, Low-Latency PCM AudioTrack."
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
        AppLanguage.TR -> "Apache 2.0 Açık Kaynak Lisansı ile korunmaktadır."
        AppLanguage.EN -> "Protected under the Apache 2.0 Open Source License."
    }

    fun aboutDisclaimerTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yasal Uyarı ve Marka Bildirimi"
        AppLanguage.EN -> "Legal Disclaimer & Trademark Notice"
    }

    fun aboutDisclaimerDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay, Apple TV, iPhone, iPad ve Mac; Apple Inc.'in ABD ve diğer ülkelerde tescilli ticari markalarıdır. Bu bağımsız açık kaynak projesi Apple Inc. ile ilişkili değildir, Apple tarafından onaylanmamış veya desteklenmemiştir. Uygulama yalnızca yerel ağda birlikte çalışabilirlik amacıyla geliştirilmiş olup hiçbir telifli DRM/FairPlay içeriği kırmaz veya şifresini çözmez."
        AppLanguage.EN -> "AirPlay, Apple TV, iPhone, iPad, and Mac are registered trademarks of Apple Inc., registered in the U.S. and other countries. This independent open-source project is not affiliated with, sponsored, or endorsed by Apple Inc. It is developed solely for local network device interoperability and research, and does not decrypt or circumvent DRM-protected streams."
    }
}

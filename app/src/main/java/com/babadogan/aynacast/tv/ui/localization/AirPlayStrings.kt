package com.babadogan.aynacast.tv.ui.localization

import com.babadogan.aynacast.tv.model.AppLanguage
import com.babadogan.aynacast.tv.model.AppThemeMode

object AirPlayStrings {

    fun appName(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AynaCast TV - AirPlay Yansıtıcı"
        AppLanguage.EN -> "AynaCast TV - AirPlay Receiver"
        AppLanguage.RO -> "AynaCast TV - Receptor AirPlay"
    }

    fun appSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Apple cihazlarınızdan (iPhone, iPad, Mac) bu ekrana anında görüntü ve ses yansıtın (AirPlay Mirroring)."
        AppLanguage.EN -> "Stream screen and audio directly from your Apple devices (iPhone, iPad, Mac) to this TV via AirPlay."
        AppLanguage.RO -> "Transmiteți imaginea și sunetul de pe dispozitivele Apple (iPhone, iPad, Mac) direct pe acest televizor prin AirPlay."
    }

    fun tabSettings(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "⚙️ Ayarlar"
        AppLanguage.EN -> "⚙️ Settings"
        AppLanguage.RO -> "⚙️ Setări"
    }

    fun tabAbout(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "ℹ️ Hakkında & Bilgi"
        AppLanguage.EN -> "ℹ️ About & Info"
        AppLanguage.RO -> "ℹ️ Despre & Informații"
    }

    fun deviceInfoTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Cihaz ve Ağ Bilgileri"
        AppLanguage.EN -> "Device & Network Information"
        AppLanguage.RO -> "Informații despre dispozitiv și rețea"
    }

    fun deviceInfoPort(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Protokol & Port: RTSP 7000 • Video TCP 7100 • Ses UDP 7011"
        AppLanguage.EN -> "Protocol & Port: RTSP 7000 • Video TCP 7100 • Audio UDP 7011"
        AppLanguage.RO -> "Protocol și port: RTSP 7000 • Video TCP 7100 • Audio UDP 7011"
    }

    fun deviceInfoResolution(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Çözünürlük: 1080p 60 FPS • MediaCodec Donanım AVC"
        AppLanguage.EN -> "Resolution: 1080p 60 FPS • MediaCodec Hardware AVC"
        AppLanguage.RO -> "Rezoluție: 1080p 60 FPS • Decodor hardware AVC MediaCodec"
    }

    fun deviceInfoMac(mac: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Cihaz Kimliği (MAC): $mac"
        AppLanguage.EN -> "Device ID (MAC): $mac"
        AppLanguage.RO -> "ID dispozitiv (MAC): $mac"
    }

    fun serviceStatusActive(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Servis Durumu: Aktif"
        AppLanguage.EN -> "Service Status: Active"
        AppLanguage.RO -> "Starea serviciului: Activ"
    }

    fun serviceStatusStopped(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Servis Durumu: Durduruldu"
        AppLanguage.EN -> "Service Status: Stopped"
        AppLanguage.RO -> "Starea serviciului: Oprit"
    }

    fun statusIdle(deviceName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bağlantı bekleniyor. TV ağ üzerinde '$deviceName' olarak yayın yapıyor."
        AppLanguage.EN -> "Waiting for connection. TV is broadcasting as '$deviceName' on the local network."
        AppLanguage.RO -> "Se așteaptă conexiunea. Televizorul este vizibil ca '$deviceName' în rețeaua locală."
    }

    fun statusConnecting(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Cihaz bağlandı: $clientName. Akış hazırlanıyor..."
        AppLanguage.EN -> "Device connected: $clientName. Preparing stream..."
        AppLanguage.RO -> "Dispozitiv conectat: $clientName. Se pregătește transmisia..."
    }

    fun statusPlayingAudio(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Müzik çalıyor: $clientName"
        AppLanguage.EN -> "Playing audio from $clientName"
        AppLanguage.RO -> "Se redă audio de la $clientName"
    }

    fun statusConnectedIdle(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bağlı, yayın yok: $clientName"
        AppLanguage.EN -> "Connected, nothing playing: $clientName"
        AppLanguage.RO -> "Conectat, nimic în redare: $clientName"
    }

    fun receiverOffStatus(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay Kapalı"
        AppLanguage.EN -> "AirPlay Off"
        AppLanguage.RO -> "AirPlay oprit"
    }

    fun receiverOffDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "TV Apple cihazlarında görünmüyor ve bağlantı kabul etmiyor."
        AppLanguage.EN -> "The TV is hidden from Apple devices and accepts no connections."
        AppLanguage.RO -> "Televizorul este ascuns de dispozitivele Apple și nu acceptă conexiuni."
    }

    fun stopStreaming(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yayını Durdur"
        AppLanguage.EN -> "Stop Streaming"
        AppLanguage.RO -> "Oprește transmisia"
    }

    fun receiverStop(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay'i Durdur"
        AppLanguage.EN -> "Stop AirPlay"
        AppLanguage.RO -> "Oprește AirPlay"
    }

    fun receiverStart(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay'i Başlat"
        AppLanguage.EN -> "Start AirPlay"
        AppLanguage.RO -> "Pornește AirPlay"
    }

    fun statusStreaming(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Ekran yansıtılıyor: $clientName"
        AppLanguage.EN -> "Screen mirroring active: $clientName"
        AppLanguage.RO -> "Oglindirea ecranului este activă: $clientName"
    }

    fun statusDisconnected(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Son oturum tamamlandı. Servis arka planda dinlemeye devam ediyor."
        AppLanguage.EN -> "Session ended. Background service continues listening for incoming connections."
        AppLanguage.RO -> "Sesiunea s-a încheiat. Serviciul din fundal continuă să aștepte conexiuni."
    }

    fun howToConnectTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Nasıl Bağlanılır?"
        AppLanguage.EN -> "How to Connect?"
        AppLanguage.RO -> "Cum vă conectați?"
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
        AppLanguage.RO -> "1. Asigurați-vă că iPhone-ul sau Mac-ul este conectat la aceeași rețea Wi-Fi ca acest televizor.\n" +
                          "2. Deschideți Centrul de control pe dispozitivul Apple.\n" +
                          "3. Atingeți pictograma 'Oglindirea ecranului' (Screen Mirroring).\n" +
                          "4. Selectați '$deviceName' din listă."
    }

    fun restartService(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Servisi Yeniden Başlat"
        AppLanguage.EN -> "Restart Service"
        AppLanguage.RO -> "Repornește serviciul"
    }

    fun settingsSectionAppearance(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Görünüm ve Tema"
        AppLanguage.EN -> "Appearance & Theme"
        AppLanguage.RO -> "Aspect și temă"
    }

    fun settingsSectionLanguage(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Uygulama Dili"
        AppLanguage.EN -> "App Language"
        AppLanguage.RO -> "Limba aplicației"
    }

    fun settingKeepAliveTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "7/24 Arka Planda Çalışma"
        AppLanguage.EN -> "24/7 Background Service"
        AppLanguage.RO -> "Serviciu în fundal 24/7"
    }

    fun settingKeepAliveDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Açıkken TV açıldığında servis otomatik başlar ve uygulama kapalıyken de yayın almaya devam eder. Kapalıyken yalnızca uygulama açıkken çalışır."
        AppLanguage.EN -> "When enabled, the service starts on TV boot and listens in the background. When disabled, it runs only while the app is active."
        AppLanguage.RO -> "Când este activat, serviciul pornește odată cu televizorul și ascultă în fundal. Când este dezactivat, funcționează doar cât timp aplicația este deschisă."
    }

    fun discoveryRefreshTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Görünürlüğü Tazele"
        AppLanguage.EN -> "Refresh Visibility"
        AppLanguage.RO -> "Reîmprospătează vizibilitatea"
    }

    fun discoveryRefreshDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bazı TV'lerin Wi-Fi'ı, iPhone ve Mac'in \"kim var?\" sorgularını almaz; TV yalnızca açılışta görünür, telefon Wi-Fi'a yeniden bağlanınca listeden kaybolur. Açıkken TV, yayın yokken her dakika kendini yeniden duyurur. Liste açıkken TV bir anlığına kaybolup geri gelebilir."
        AppLanguage.EN -> "Some TVs' Wi-Fi drops the \"who is there?\" queries of iPhones and Macs, so the TV only shows up right after it starts and vanishes once a phone reconnects to Wi-Fi. When on, the TV announces itself every minute while nothing is streaming; an open device list may briefly drop and re-add it."
        AppLanguage.RO -> "Wi-Fi-ul unor televizoare ignoră întrebările \"cine este acolo?\" ale iPhone-urilor și Mac-urilor, așa că televizorul apare doar imediat după pornire și dispare când un telefon se reconectează la Wi-Fi. Când este activat, televizorul se anunță la fiecare minut cât timp nu se transmite nimic; o listă de dispozitive deschisă îl poate elimina și readăuga pentru scurt timp."
    }

    fun discoveryRefreshOn(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Her Dakika (Önerilen)"
        AppLanguage.EN -> "Every Minute (Recommended)"
        AppLanguage.RO -> "În fiecare minut (Recomandat)"
    }

    fun discoveryRefreshOff(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kapalı"
        AppLanguage.EN -> "Off"
        AppLanguage.RO -> "Oprit"
    }

    fun keepAliveEnabled(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "7/24 Arka Plan Açık (Önerilen)"
        AppLanguage.EN -> "24/7 Always Active (Recommended)"
        AppLanguage.RO -> "Activ 24/7 (Recomandat)"
    }

    fun keepAliveDisabled(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yalnızca Uygulama Açıkken"
        AppLanguage.EN -> "Only While App is Open"
        AppLanguage.RO -> "Doar cât timp aplicația e deschisă"
    }

    fun themeName(theme: AppThemeMode, lang: AppLanguage): String = when (theme) {
        AppThemeMode.SYSTEM -> when (lang) {
            AppLanguage.TR -> "Sistem"
            AppLanguage.EN -> "System"
            AppLanguage.RO -> "Sistem"
        }
        AppThemeMode.DARK -> when (lang) {
            AppLanguage.TR -> "Koyu"
            AppLanguage.EN -> "Dark"
            AppLanguage.RO -> "Întunecat"
        }
        AppThemeMode.LIGHT -> when (lang) {
            AppLanguage.TR -> "Açık"
            AppLanguage.EN -> "Light"
            AppLanguage.RO -> "Luminos"
        }
    }

    // Short: three of them share one row in the language card.
    fun languageName(language: AppLanguage, lang: AppLanguage): String = when (language) {
        AppLanguage.TR -> when (lang) {
            AppLanguage.TR -> "Türkçe"
            AppLanguage.EN -> "Turkish"
            AppLanguage.RO -> "Turcă"
        }
        AppLanguage.EN -> when (lang) {
            AppLanguage.TR -> "İngilizce"
            AppLanguage.EN -> "English"
            AppLanguage.RO -> "Engleză"
        }
        AppLanguage.RO -> when (lang) {
            AppLanguage.TR -> "Rumence"
            AppLanguage.EN -> "Romanian"
            AppLanguage.RO -> "Română"
        }
    }

    fun airplayActiveBadge(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay Aktif"
        AppLanguage.EN -> "AirPlay Active"
        AppLanguage.RO -> "AirPlay activ"
    }

    fun aboutVersion(version: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Sürüm: v$version"
        AppLanguage.EN -> "Version: v$version"
        AppLanguage.RO -> "Versiune: v$version"
    }

    fun aboutArchitectureTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Mimari Tasarım"
        AppLanguage.EN -> "Architecture Design"
        AppLanguage.RO -> "Arhitectură"
    }

    fun aboutArchitectureDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Arka Plan Daemon Servisi (Foreground Daemon) + İhtiyaç Anında Açılan Compose TV Arayüzü (On-Demand UI)."
        AppLanguage.EN -> "Foreground Daemon Service + On-Demand Jetpack Compose for TV Interface."
        AppLanguage.RO -> "Serviciu daemon în prim-plan + interfață Jetpack Compose for TV deschisă la cerere."
    }

    fun aboutProtocolsTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Desteklenen Protokoller ve Motorlar"
        AppLanguage.EN -> "Supported Protocols & Engines"
        AppLanguage.RO -> "Protocoale și motoare acceptate"
    }

    fun aboutProtocolsDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Apple AirPlay Mirroring (RTSP 7000), mDNS/DNS-SD (_airplay._tcp, _raop._tcp), MediaCodec AVC H.264 Donanım Çözücü, AAC-ELD / ALAC Ses, AudioTrack."
        AppLanguage.EN -> "Apple AirPlay Mirroring (RTSP 7000), mDNS/DNS-SD (_airplay._tcp, _raop._tcp), MediaCodec AVC H.264 Hardware Decoder, AAC-ELD / ALAC Audio, AudioTrack."
        AppLanguage.RO -> "Apple AirPlay Mirroring (RTSP 7000), mDNS/DNS-SD (_airplay._tcp, _raop._tcp), decodor hardware MediaCodec AVC H.264, audio AAC-ELD / ALAC, AudioTrack."
    }

    fun aboutDeveloperTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Geliştirici ve Telif"
        AppLanguage.EN -> "Developer & Copyright"
        AppLanguage.RO -> "Dezvoltator și drepturi de autor"
    }

    fun aboutDeveloperDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Babadogan Tech & Edu — Ankara Üniversitesi Teknokent"
        AppLanguage.EN -> "Babadogan Tech & Edu — Ankara University Technopolis"
        AppLanguage.RO -> "Babadogan Tech & Edu — Ankara University Technopolis"
    }

    fun aboutLicenseTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Lisans"
        AppLanguage.EN -> "License"
        AppLanguage.RO -> "Licență"
    }

    fun aboutLicenseDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "MIT Açık Kaynak Lisansı ile lisanslanmıştır."
        AppLanguage.EN -> "Licensed under the MIT Open Source License."
        AppLanguage.RO -> "Licențiat sub licența open source MIT."
    }

    fun aboutDisclaimerTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yasal Uyarı ve Marka Bildirimi"
        AppLanguage.EN -> "Legal Disclaimer & Trademark Notice"
        AppLanguage.RO -> "Declinarea răspunderii și mărci comerciale"
    }

    fun aboutDisclaimerDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay, Apple TV, iPhone, iPad ve Mac; Apple Inc.'in ABD ve diğer ülkelerde tescilli ticari markalarıdır. Bu bağımsız açık kaynak projesi Apple Inc. ile ilişkili değildir, Apple tarafından onaylanmamış veya desteklenmemiştir. Uygulama yalnızca yerel ağda birlikte çalışabilirlik amacıyla geliştirilmiş olup hiçbir telifli DRM/FairPlay içeriği kırmaz veya şifresini çözmez."
        AppLanguage.EN -> "AirPlay, Apple TV, iPhone, iPad, and Mac are registered trademarks of Apple Inc., registered in the U.S. and other countries. This independent open-source project is not affiliated with, sponsored, or endorsed by Apple Inc. It is developed solely for local network device interoperability and research, and does not decrypt or circumvent DRM-protected streams."
        AppLanguage.RO -> "AirPlay, Apple TV, iPhone, iPad și Mac sunt mărci comerciale înregistrate ale Apple Inc., înregistrate în SUA și în alte țări. Acest proiect open source independent nu este afiliat, sponsorizat sau aprobat de Apple Inc. Este dezvoltat exclusiv pentru interoperabilitatea dispozitivelor din rețeaua locală și pentru cercetare și nu decriptează și nu ocolește fluxurile protejate prin DRM."
    }

    fun showMirroring(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yansıtmayı Göster"
        AppLanguage.EN -> "Show Mirroring"
        AppLanguage.RO -> "Afișează oglindirea"
    }

    fun serviceStatusStarting(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Servis başlatılıyor veya AirPlay portu (7000) kullanılamıyor. Gerekirse servisi yeniden başlatın."
        AppLanguage.EN -> "The service is starting or the AirPlay port (7000) is unavailable. Restart the service if needed."
        AppLanguage.RO -> "Serviciul pornește sau portul AirPlay (7000) nu este disponibil. Reporniți serviciul dacă este necesar."
    }

    fun overlayPermissionTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Otomatik Açılma İzni"
        AppLanguage.EN -> "Auto-Open Permission"
        AppLanguage.RO -> "Permisiune de deschidere automată"
    }

    fun overlayPermissionDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Android 10 ve üzeri sürümler, başka bir uygulama açıkken yansıtma ekranının kendiliğinden açılması için \"Diğer uygulamaların üzerinde göster\" iznini ister. İzin verilmezse ekranda kısa bir uyarı çıkar ve yansıtmayı görmek için AynaCast TV'yi açmanız gerekir."
        AppLanguage.EN -> "Android 10 and later require the \"Display over other apps\" permission so the mirroring screen can open by itself while another app is in use. Without it, a short message appears on screen and you have to open AynaCast TV to see the mirroring."
        AppLanguage.RO -> "Android 10 și versiunile ulterioare cer permisiunea \"Afișare peste alte aplicații\" pentru ca ecranul de oglindire să se poată deschide singur cât timp folosiți altă aplicație. Fără ea, pe ecran apare un scurt mesaj și trebuie să deschideți AynaCast TV pentru a vedea oglindirea."
    }

    fun overlayPermissionLater(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Daha Sonra"
        AppLanguage.EN -> "Later"
        AppLanguage.RO -> "Mai târziu"
    }

    /** Toast over another app when a screen could not open by itself (no overlay permission). */
    fun toastOpenApp(message: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "$message \"Diğer uygulamaların üzerinde göster\" izni verilmediği için ekran öne getirilemedi; görmek için AynaCast TV'yi açın."
        AppLanguage.EN -> "$message The screen could not come to the front because \"Display over other apps\" is not allowed; open AynaCast TV to see it."
        AppLanguage.RO -> "$message Ecranul nu a putut fi adus în prim-plan deoarece \"Afișare peste alte aplicații\" nu este permisă; deschideți AynaCast TV pentru a-l vedea."
    }

    fun toastPin(pin: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay kodu: $pin — iPhone, iPad veya Mac'inizde girin. (İzin verilmediği için kod ekranı öne getirilemedi.)"
        AppLanguage.EN -> "AirPlay code: $pin — enter it on your iPhone, iPad or Mac. (The code screen could not come to the front: permission not granted.)"
        AppLanguage.RO -> "Cod AirPlay: $pin — introduceți-l pe iPhone, iPad sau Mac. (Ecranul cu codul nu a putut fi adus în prim-plan: permisiunea nu este acordată.)"
    }

    fun overlayPermissionButton(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "İzni Ver"
        AppLanguage.EN -> "Grant Permission"
        AppLanguage.RO -> "Acordă permisiunea"
    }

    fun overlayPermissionUnavailable(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bu TV izin ekranını sunmuyor. Bilgisayardan şu komutla verebilirsiniz: adb shell appops set com.babadogan.aynacast.tv SYSTEM_ALERT_WINDOW allow"
        AppLanguage.EN -> "This TV does not offer the permission screen. Grant it from a computer with: adb shell appops set com.babadogan.aynacast.tv SYSTEM_ALERT_WINDOW allow"
        AppLanguage.RO -> "Acest televizor nu oferă ecranul de permisiuni. Acordați-o de pe un computer cu: adb shell appops set com.babadogan.aynacast.tv SYSTEM_ALERT_WINDOW allow"
    }

    fun notificationServiceChannel(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay Alıcı Servisi"
        AppLanguage.EN -> "AirPlay Receiver Service"
        AppLanguage.RO -> "Serviciul receptor AirPlay"
    }

    fun notificationServiceChannelDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Gelen AirPlay bağlantılarını arka planda dinler."
        AppLanguage.EN -> "Listens for incoming AirPlay connections in the background."
        AppLanguage.RO -> "Ascultă în fundal conexiunile AirPlay primite."
    }

    fun notificationServiceTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay Alıcısı Aktif"
        AppLanguage.EN -> "AirPlay Receiver Active"
        AppLanguage.RO -> "Receptor AirPlay activ"
    }

    fun notificationServiceText(deviceName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "'$deviceName' ağda yayınlanıyor (Apple cihazlarından yansıtabilirsiniz)"
        AppLanguage.EN -> "Broadcasting as '$deviceName' (mirror from your Apple devices)"
        AppLanguage.RO -> "Vizibil ca '$deviceName' (oglindiți de pe dispozitivele Apple)"
    }

    fun notificationStreamChannel(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Ekran Yansıtma"
        AppLanguage.EN -> "Screen Mirroring"
        AppLanguage.RO -> "Oglindirea ecranului"
    }

    fun notificationStreamTitle(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "$clientName ekranını yansıtıyor"
        AppLanguage.EN -> "$clientName is mirroring"
        AppLanguage.RO -> "$clientName își oglindește ecranul"
    }

    fun notificationStreamText(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yansıtma ekranını açmak için seçin."
        AppLanguage.EN -> "Select to open the mirroring screen."
        AppLanguage.RO -> "Selectați pentru a deschide ecranul de oglindire."
    }

    fun avSyncTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Ses-Görüntü Senkronu (Zaman Damgası)"
        AppLanguage.EN -> "Audio/Video Sync (Timestamps)"
        AppLanguage.RO -> "Sincronizare audio/video (marcaje temporale)"
    }

    fun avSyncDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Açıkken ses ve görüntü göndericinin zaman damgalarına göre eşlenir (UxPlay -vsync); gecikme artar ve geç kalan kareler atlanır. Kapalıyken her şey geldiği anda oynatılır: Mac yansıtma ve donanım çözücülü TV'ler için önerilen en düşük gecikmeli mod."
        AppLanguage.EN -> "When on, audio and video are aligned to the sender's timestamps (UxPlay -vsync); latency increases and late frames are skipped. When off, everything plays as soon as it arrives: the lowest-latency mode recommended for Mac mirroring and hardware-decoding TVs."
        AppLanguage.RO -> "Când este activată, sunetul și imaginea sunt aliniate după marcajele temporale ale expeditorului (UxPlay -vsync); latența crește și cadrele întârziate sunt omise. Când este dezactivată, totul se redă imediat ce sosește: modul cu cea mai mică latență, recomandat pentru oglindirea de pe Mac și pentru televizoarele cu decodare hardware."
    }

    fun avSyncOff(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kapalı – En Düşük Gecikme (Önerilen)"
        AppLanguage.EN -> "Off – Lowest Latency (Recommended)"
        AppLanguage.RO -> "Oprit – latență minimă (Recomandat)"
    }

    fun avSyncOn(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Açık – Zaman Damgası Senkronu"
        AppLanguage.EN -> "On – Timestamp Sync"
        AppLanguage.RO -> "Pornit – sincronizare după marcaje temporale"
    }

    fun mirrorStartingTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Ekran yansıtma başlıyor"
        AppLanguage.EN -> "Starting screen mirroring"
        AppLanguage.RO -> "Se pornește oglindirea ecranului"
    }

    fun mirrorStartingSubtitle(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "$clientName ekranı bu TV'ye geliyor"
        AppLanguage.EN -> "$clientName is coming up on this TV"
        AppLanguage.RO -> "$clientName apare pe acest televizor"
    }

    fun mirrorEndedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Ekran yansıtma sona erdi"
        AppLanguage.EN -> "Screen mirroring ended"
        AppLanguage.RO -> "Oglindirea ecranului s-a încheiat"
    }

    fun pinPairedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Eşleşme tamamlandı"
        AppLanguage.EN -> "Paired"
        AppLanguage.RO -> "Asociat"
    }

    fun pinPairedSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bu cihaz bundan sonra kod sorulmadan bağlanacak"
        AppLanguage.EN -> "This device will connect without a code from now on"
        AppLanguage.RO -> "De acum, acest dispozitiv se va conecta fără cod"
    }

    fun pinExpiredTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kodun süresi doldu"
        AppLanguage.EN -> "The code expired"
        AppLanguage.RO -> "Codul a expirat"
    }

    fun pinWrongCodeTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kod eşleşmedi"
        AppLanguage.EN -> "The code did not match"
        AppLanguage.RO -> "Codul nu se potrivește"
    }

    fun pinRetrySubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Yeni bir kod için Apple cihazınızdan tekrar bağlanın"
        AppLanguage.EN -> "Connect again from your Apple device for a new code"
        AppLanguage.RO -> "Conectați-vă din nou de pe dispozitivul Apple pentru un cod nou"
    }

    fun pinLockedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Çok fazla hatalı deneme"
        AppLanguage.EN -> "Too many wrong codes"
        AppLanguage.RO -> "Prea multe coduri greșite"
    }

    fun pinLockedSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "5 dakika sonra tekrar deneyin"
        AppLanguage.EN -> "Try again in 5 minutes"
        AppLanguage.RO -> "Încercați din nou peste 5 minute"
    }

    fun pinCancelledTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Eşleşme iptal edildi"
        AppLanguage.EN -> "Pairing cancelled"
        AppLanguage.RO -> "Asocierea a fost anulată"
    }

    fun castPreparingTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Video hazırlanıyor"
        AppLanguage.EN -> "Preparing video"
        AppLanguage.RO -> "Se pregătește videoclipul"
    }

    fun castPreparingSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay ile bu TV'de oynatılacak"
        AppLanguage.EN -> "It will play on this TV via AirPlay"
        AppLanguage.RO -> "Va fi redat pe acest televizor prin AirPlay"
    }

    fun castDrmTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bu video korumalı"
        AppLanguage.EN -> "This video is protected"
        AppLanguage.RO -> "Acest videoclip este protejat"
    }

    fun castDrmSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Netflix, Apple TV+ gibi uygulamaların kopya korumalı (DRM) videoları bu TV'de oynatılamaz"
        AppLanguage.EN -> "Copy-protected (DRM) videos from apps like Netflix or Apple TV+ cannot play on this TV"
        AppLanguage.RO -> "Videoclipurile protejate la copiere (DRM) din aplicații precum Netflix sau Apple TV+ nu pot fi redate pe acest televizor"
    }

    fun castFailedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Video oynatılamadı"
        AppLanguage.EN -> "The video could not be played"
        AppLanguage.RO -> "Videoclipul nu a putut fi redat"
    }

    fun castFailedSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bağlantıyı kontrol edip Apple cihazınızdan tekrar deneyin"
        AppLanguage.EN -> "Check the connection and try again from your Apple device"
        AppLanguage.RO -> "Verificați conexiunea și încercați din nou de pe dispozitivul Apple"
    }

    fun castEndedTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Oynatma sona erdi"
        AppLanguage.EN -> "Playback ended"
        AppLanguage.RO -> "Redarea s-a încheiat"
    }

    fun castEndedSubtitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay bağlantısı kapatılıyor"
        AppLanguage.EN -> "Closing the AirPlay connection"
        AppLanguage.RO -> "Se închide conexiunea AirPlay"
    }

    fun nowPlayingInApp(title: String, artist: String, lang: AppLanguage): String {
        val track = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" – ")
        return when (lang) {
            AppLanguage.TR -> "♪ Çalıyor: $track"
            AppLanguage.EN -> "♪ Playing: $track"
            AppLanguage.RO -> "♪ Se redă: $track"
        }
    }

    fun showNowPlaying(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Şimdi Çalınanı Göster"
        AppLanguage.EN -> "Show Now Playing"
        AppLanguage.RO -> "Afișează ce se redă"
    }

    fun nowPlayingFrom(clientName: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "$clientName cihazından AirPlay ile çalıyor"
        AppLanguage.EN -> "Playing from $clientName via AirPlay"
        AppLanguage.RO -> "Se redă de la $clientName prin AirPlay"
    }

    fun nowPlayingUnknownTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bilinmeyen Şarkı"
        AppLanguage.EN -> "Unknown Track"
        AppLanguage.RO -> "Piesă necunoscută"
    }

    fun nowPlayingPaused(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Duraklatıldı"
        AppLanguage.EN -> "Paused"
        AppLanguage.RO -> "În pauză"
    }

    fun pinTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay Kodu"
        AppLanguage.EN -> "AirPlay Code"
        AppLanguage.RO -> "Cod AirPlay"
    }

    fun pinInstructions(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bu kodu iPhone, iPad veya Mac'inizde girin. Kod yalnızca bu cihazdan ilk bağlanışta sorulur."
        AppLanguage.EN -> "Enter this code on your iPhone, iPad or Mac. It is only asked the first time this device connects."
        AppLanguage.RO -> "Introduceți acest cod pe iPhone, iPad sau Mac. Este cerut doar la prima conectare a acestui dispozitiv."
    }

    fun notificationPinText(pin: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "iPhone, iPad veya Mac'inizde şu kodu girin: $pin"
        AppLanguage.EN -> "Enter this code on your iPhone, iPad or Mac: $pin"
        AppLanguage.RO -> "Introduceți acest cod pe iPhone, iPad sau Mac: $pin"
    }

    fun notificationVideoCastTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "AirPlay videosu hazır"
        AppLanguage.EN -> "AirPlay video ready"
        AppLanguage.RO -> "Videoclip AirPlay pregătit"
    }

    fun notificationVideoCastText(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Oynatmak için seçin."
        AppLanguage.EN -> "Select to play."
        AppLanguage.RO -> "Selectați pentru redare."
    }

    fun pinSettingTitle(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bağlantı Kodu (PIN)"
        AppLanguage.EN -> "Connection Code (PIN)"
        AppLanguage.RO -> "Cod de conectare (PIN)"
    }

    fun pinSettingDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Açıkken yeni bir Apple cihazı ilk kez bağlanırken TV'de 4 haneli bir kod gösterilir ve cihazda girilmesi gerekir; eşleşen cihazlar hatırlanır."
        AppLanguage.EN -> "When on, a new Apple device connecting for the first time must enter the 4-digit code shown on the TV; paired devices are remembered."
        AppLanguage.RO -> "Când este activat, un dispozitiv Apple nou care se conectează pentru prima dată trebuie să introducă codul din 4 cifre afișat pe televizor; dispozitivele asociate sunt reținute."
    }

    fun pinOff(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kapalı – Kod Sorma"
        AppLanguage.EN -> "Off – No Code"
        AppLanguage.RO -> "Oprit – fără cod"
    }

    fun pinOn(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Açık – İlk Bağlantıda Kod"
        AppLanguage.EN -> "On – Code on First Connection"
        AppLanguage.RO -> "Pornit – cod la prima conectare"
    }

    fun pinPairedDevices(count: Int, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Eşleşmiş Cihazlar ($count)"
        AppLanguage.EN -> "Paired Devices ($count)"
        AppLanguage.RO -> "Dispozitive asociate ($count)"
    }

    fun pairedDevicesDesc(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kaldırılan cihaz bir sonraki bağlantısında yeniden kod girmelidir."
        AppLanguage.EN -> "A removed device has to enter a code again the next time it connects."
        AppLanguage.RO -> "Un dispozitiv eliminat va trebui să introducă din nou un cod la următoarea conectare."
    }

    fun pairedDeviceUnknown(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Bilinmeyen cihaz"
        AppLanguage.EN -> "Unknown device"
        AppLanguage.RO -> "Dispozitiv necunoscut"
    }

    fun pairedDeviceSince(date: String, lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Eşleşme: $date"
        AppLanguage.EN -> "Paired: $date"
        AppLanguage.RO -> "Asociat: $date"
    }

    fun pairedDeviceRemove(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kaldır"
        AppLanguage.EN -> "Remove"
        AppLanguage.RO -> "Elimină"
    }

    fun pairedDevicesForgetAll(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Tümünü Unut"
        AppLanguage.EN -> "Forget All"
        AppLanguage.RO -> "Uită toate"
    }

    fun close(lang: AppLanguage): String = when (lang) {
        AppLanguage.TR -> "Kapat"
        AppLanguage.EN -> "Close"
        AppLanguage.RO -> "Închide"
    }
}

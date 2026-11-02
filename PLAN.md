# Pilot TV – założenia i stan projektu

## Cel
- Natywna aplikacja Android (APK) do sterowania TV i dekoderem przez Wi-Fi. Bez reklam, bez trackerów.
- Projekt open source (MIT): https://github.com/BartoszKowalik/pilottv

## Urządzenia
| Urządzenie | Protokół | Status |
|---|---|---|
| Samsung UE55NU7093U (Tizen 4.0, 2018) | WebSocket `wss://IP:8002`, token, TOFU certyfikatu | Działa |
| Dekoder Canal+ z Android TV | Android TV Remote v2: parowanie 6467 (kod z ekranu), sterowanie 6466 (TLS + cert. klienta, protobuf) | Działa |
| LG webOS (TV kolegi) | SSAP przez WebSocket `wss://:3001` / `ws://:3000`, client-key | Eksperymentalne, nietestowane |

- Telefon właściciela nie ma IR – kanał IR odpada.
- TV Samsung ma uszkodzony port LAN, działa tylko po Wi-Fi (Wake-on-LAN przez Wi-Fi – do sprawdzenia).
- Kolega z LG ma iPhone'a: APK nie zadziała. Rekomendacja: oficjalna aplikacja LG ThinQ. Wersja iOS tylko z Apple Developer Program (99 USD/rok, TestFlight) – decyzja odłożona.

## Decyzje techniczne
- Kotlin + Jetpack Compose (Material 3), minSdk 26, OkHttp, DataStore.
- **Build tylko w GitHub Actions.** Na komputerze deweloperskim nie instalujemy Android SDK ani JDK 17. Brak Gradle Wrappera – wersja Gradle jest w workflow.
- Push na `main` → APK w Artifacts. Tag `v*` → GitHub Release z APK. Podpis stałym kluczem z sekretów repo (`KEYSTORE_BASE64`, `SIGNING_*`).
- Dystrybucja: GitHub Releases + Obtainium. Sklep Play / F-Droid – później, opcjonalnie.
- Bez zewnętrznych bibliotek do protobuf i certyfikatów: ręczny protobuf (`atv/Proto.kt`) i ręczny DER X.509 (`atv/ClientIdentity.kt`).
- TLS do urządzeń: TOFU (zapamiętanie SHA-256 certyfikatu), bez trust-all.
- Neutralne klawisze `remote/Key` mapowane na kody każdego urządzenia (`SamsungKeys`, `AtvKeys`, `LgKeys`).
- Slot „TV” = Samsung albo LG (`TvBrand`, wspólny interfejs `TvClient`). Slot „Dekoder” = Android TV.
- Głośność domyślnie zawsze do TV, także gdy wybrany jest dekoder (przełączane w ustawieniach).
- Identyfikator aplikacji `pl.pilottv` i nazwa „Pilot TV” – nie zmieniać (zmiana psuje aktualizacje / parowanie).
- Commity w repo z adresem `BartoszKowalik@users.noreply.github.com` (ustawione lokalnie dla repo).

## Zrobione (do v0.4.0)
- Samsung: wyszukiwanie, parowanie, wszystkie przyciski, Wake-on-LAN, tekst.
- Dekoder Android TV: parowanie kodem, przyciski, tekst na żywo, skróty VOD (edytowalna lista).
- LG webOS: parowanie, przyciski (gniazdo pointer), tekst (IME), MAC do Wake-on-LAN.
- UI: układ minimalny / pełny, touchpad, motyw systemowy/jasny/ciemny, siła wibracji, przyciski głośności telefonu, przełącznik TV/dekoder.
- Pole tekstowe na żywo (tekst z urządzenia widoczny w telefonie, kasowanie). Ważne głównie na Androidzie TV, na Samsungu priorytet niski.
- Mikrofon: rozpoznawanie mowy w telefonie → tekst do pola na urządzeniu.

## Do zrobienia / do sprawdzenia
1. Kafelek w szybkich ustawieniach i widget (Power, Mute).
2. Testy v0.4.0 na dekoderze: odczyt tekstu z pola, kasowanie, skróty VOD.
3. Mikrofon przez protokół v2 (prawdziwy głos jak w Google TV): sprawdzić, czy słuchanie w v0.3 pochodziło z aplikacji Google TV w telefonie. Jeśli tak – dekoder obsługuje głos z telefonu, warto to dodać (wymaga uprawnienia RECORD_AUDIO).
4. Wake-on-LAN Samsunga przez Wi-Fi – potwierdzić w praktyce.
5. LG: potwierdzić parowanie bez podpisanego manifestu; zweryfikować nazwy przycisków PRE-CH, TTX, TOOLS, Źródło.
6. Skrót Canal+ – nazwa pakietu nieznana, użytkownik może dodać z linku Google Play.
7. Opcjonalnie: wersja iOS (tylko LG / całość), publikacja w Sklepie Play.

## Uwagi do Sklepu Play (na później)
- Konto 25 USD, weryfikacja tożsamości, test zamknięty 12 testerów / 14 dni dla nowych kont prywatnych.
- Brak „Samsung”/„LG” w nazwie i ikonie. Prosta polityka prywatności.
- Do sprawdzenia: aktualny zakres weryfikacji deweloperów Google dla aplikacji spoza sklepu.

## Przygotowanie TV Samsung
- Ogólne → Menedżer urządzeń zewnętrznych → Menedżer połączeń urządzeń → Powiadomienie o dostępie: „Tylko pierwsze połączenie”.
- Ogólne → Sieć → Ustawienia eksperckie → Włącz za pomocą urządzenia mobilnego.
- Rezerwacja DHCP (stałe IP) dla TV i dekodera w routerze.

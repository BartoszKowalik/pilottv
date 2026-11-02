# Pilot TV – plan

## Cel
- Natywna aplikacja Android (APK) bez reklam i bez trackerów.
- Urządzenie główne: Samsung UE55NU7093U (Tizen 4.0, 2018).
- Urządzenie drugie: dekoder Canal+ z Android TV.
- Build tylko w GitHub Actions (na komputerze deweloperskim nie ma Android SDK ani JDK 17).
- Projekt open source na GitHubie. Dystrybucja: GitHub Releases + Obtainium. Sklep Play / F-Droid później.

## Decyzje
| Obszar | Wybór |
|---|---|
| Język / UI | Kotlin + Jetpack Compose (Material 3) |
| Samsung | OkHttp WebSocket `wss://IP:8002/api/v2/channels/samsung.remote.control`, token w DataStore |
| TLS | TOFU – zapis certyfikatu TV przy parowaniu, potem tylko ten certyfikat (bez trust-all) |
| Włączanie TV | Wake-on-LAN (UDP 9, MAC z `http://IP:8001/api/v2/`) |
| Wykrywanie | SSDP + skan podsieci (port 8001) + ręczne IP |
| Dekoder Canal+ | Android TV Remote v2 (TLS 6466/6467, protobuf), jeśli dekoder jest certyfikowanym Android TV |
| Telefon | Brak IR – kanał IR odpada |
| Wygląd | Domyślnie układ minimalny; pełny pilot jako opcja w ustawieniach |

## Etapy
### Etap 1 – MVP Samsung
1. Szkielet projektu, build APK (Gradle, CI na GitHub Actions).
2. Wyszukanie TV w sieci i ręczne IP.
3. Parowanie: okno „Zezwól” na TV, zapis tokenu i certyfikatu.
4. Ekran minimalny: Power, Source, D-pad + OK, Back, Home, Menu, VOL±, CH±, Mute, media.
5. Haptyka, auto-reconnect, status połączenia.
6. Power przy wyłączonym TV: WoL + oczekiwanie na port 8002.

### Etap 2 – wygoda
- Fizyczne przyciski głośności telefonu sterują TV.
- Touchpad (gesty) zamiast strzałek – opcja.
- Zwijane panele: cyfry, A/B/C/D, TTX, PRE-CH, Guide, Tools, Info.
- Układ „pełny pilot” jako przełącznik w ustawieniach.
- Wpisywanie tekstu i wklejanie ze schowka (`SendInputString`).
- Kafelek w szybkich ustawieniach, widget.
- Przytrzymanie = powtarzanie.

### Etap 3 – dekoder Canal+
- Weryfikacja: czy aplikacja Google TV (pilot) na telefonie steruje dekoderem. Jeśli tak – protokół v2 zadziała.
- Parowanie PIN, klawisze, tekst.
- Tryb hybrydowy: VOL/Power → Samsung, D-pad/Home/Back → dekoder (konfigurowalne).
- Sprawdzenie HDMI-CEC (Anynet+) jako prostszej alternatywy.

### Etap 4 – opcjonalnie
- Lista aplikacji na TV i skróty do uruchamiania.

## Przygotowanie TV
- Ogólne → Menedżer urządzeń zewnętrznych → Menedżer połączeń urządzeń → Powiadomienie o dostępie: „Tylko pierwsze połączenie”; wyczyścić listę blokad.
- Ogólne → Sieć → Ustawienia eksperckie → Włącz za pomocą urządzenia mobilnego.
- Rezerwacja DHCP (stałe IP) dla TV w routerze.

## Sklep Play – uwagi na później
- Konto 25 USD, weryfikacja tożsamości, test zamknięty 12 testerów / 14 dni dla nowych kont prywatnych.
- Brak „Samsung” w nazwie i ikonie. Prosta polityka prywatności.
- Do sprawdzenia: aktualny zakres weryfikacji deweloperów Google dla aplikacji spoza sklepu.

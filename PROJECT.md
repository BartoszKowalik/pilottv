# Pilot TV – stan pracy

Notatki robocze Claude. Aktualizowane po każdym kroku. Założenia projektu: [PLAN.md](PLAN.md).

Ostatnia aktualizacja: 2026-11-15

## Teraz
- Wydanie `v0.7.2` opublikowane: https://github.com/BartoszKowalik/pilottv/releases/tag/v0.7.2 (pliki `pilot-tv-v0.7.2.apk`, `pilot-tv-helper-v0.7.2.apk`). CI dla taga: `build` i `release` – sukces. Nowe zadanie `release` (7.9) działa.
- Czekamy na testy użytkownika na urządzeniach (lista niżej).
- 2026-11-15: commit `f54cc0f` na `main` (push za zgodą): poprawka tekstu telefon → dekoder, czyszczenie wklejonego tekstu (nowe linie, spacje na końcach), lista ostatnich tekstów w arkuszu klawiatury (do 6, zapis po „Zatwierdź”, nowy klucz DataStore `recent_texts`, krzyżyk usuwa). CI `main`: sukces. Tag `v0.7.3` (z adnotacją) wysłany, CI dla taga: sukces, wydanie https://github.com/BartoszKowalik/pilottv/releases/tag/v0.7.3 z 2 plikami APK.

### Tekst na dekoderze – ustalenia (test na dekoderze 2026-11-15, Downloader)
- Dekoder odrzucał każde wstawienie: logcat `AtvRemote.ImeFeature: Ignoring edit, the input field has changed from 1 to 94`.
- `IME_BATCH_EDIT` od dekodera ma stałe liczniki (1, 0). Prawidłowe: licznik IME = `IME_KEY_INJECT.app_info(1).field 1`, licznik pola = `text_field_status.counter_field(1)` z `IME_KEY_INJECT(2)` i `IME_SHOW_REQUEST(2)`.
- Dekoder wysyła `IME_SHOW_REQUEST` po każdej zmianie (też naszej), z nowym licznikiem pola i kursorem (pole 3). Wstawienie ze starym licznikiem jest odrzucane, więc po Backspace lub poprzednim wstawieniu aplikacja czeka, aż kursor dojdzie do oczekiwanej pozycji (limit 1 s od ostatniego potwierdzenia).
- Kilka szybkich Backspace = mniej potwierdzeń (łączenie), więc nie liczymy potwierdzeń, tylko porównujemy kursor. 50 Backspace trwa ok. 2 s.
- Enter („Zatwierdź”) idzie po czekającym wstawieniu (`AtvRemote.submitText`).
- Logika sprawdzona skryptem na laptopie (kopia logiki w Pythonie): pisanie, zamiana w środku, czyszczenie długiego adresu – wynik poprawny, brak „Ignoring edit”. Aplikacja (Kotlin) nie jest jeszcze sprawdzona na urządzeniu.
- ADB do dekodera: po „unauthorized” pomaga `adb kill-server` i ponowne `connect`. OK z pilota aplikacji nie działa w oknie zgody ADB.

### Głośność na dekoderze – ustalenia (ADB, 2026-11-18)
- Klawisze VOL (z ADB i z aplikacji) docierają do dekodera. Dekoder zmienia tylko własną głośność cyfrową (`volume_music_hdmi`), nie wysyła CEC do Bravii. Log: `vol adjust end, without passthrough tv!`.
- Przyczyna w firmware (`services.jar`, `AudioService.passthroughToTv`): CEC jest wyłączone na stałe, gdy istnieje `/vendor/lib/libdolbyms12.so` (istnieje). `persist.audio.passthrough.enable` nie ma znaczenia; shell i tak nie może jej zmienić.
- Bez roota brak obejścia w dekoderze. Do sprawdzenia: sterowanie Bravią przez sieć (IRCC lub DLNA Renderer) po podłączeniu jej do Wi-Fi.
- Aplikacja: przy włączonym „Głośność zawsze steruje telewizorem” i skonfigurowanym Samsungu głośność idzie do Samsunga, nie do dekodera (etykieta „Głośność: TV”). To była przyczyna zgłoszenia użytkownika (2026-11-18) – nie błąd.
- Głośność Bravii przez sieć: niski priorytet (decyzja użytkownika 2026-11-18). Nie zaczynać bez prośby.

## Zasady pracy
- Brak lokalnego buildu (brak Android SDK, JDK 17, Gradle Wrappera). Kompiluje tylko GitHub Actions.
- Każdą zmianę sprawdzić przez lekturę: importy, wyczerpujące `when`, sygnatury, miejsca wywołania.
- `git push` i tagi tylko po zgodzie użytkownika.
- Brak `gh` na komputerze. Stan CI: publiczne API `https://api.github.com/repos/BartoszKowalik/pilottv/actions/runs`.
- Commity po polsku, jedna krótka linia. Komentarze i teksty UI po polsku.
- Nie zmieniać: `applicationId`, `CLIENT_NAME`, kluczy DataStore, protokołów, numerów pól protobuf.
- Zmiany w dekoderze: dziennik poza repo (`G:\Mój dysk\!Dev\Projects\pilot\DEKODER.md`).

## Wersje
| Wersja | Data | Zawartość |
|---|---|---|
| v0.7.1 | – | Aktywna aplikacja na dekoderze, komunikat o błędzie linku. |
| v0.7.2 | 2026-11-13 | Poprawki z przeglądu kodu (POPRAWKI.md), 15 commitów `1a38e8b..9b9e1a5`. CI dla `main`: sukces (kompilacja + testy). |

### v0.7.2 – szczegóły
- Walidacja IP (`net/Ipv4.kt`), stan `SessionEnd.BAD_ADDRESS`, siatka bezpieczeństwa w `connectionLoop`.
- Ping WebSocket 10 s (Samsung, LG) – commit `1abe700`, **do cofnięcia, gdy Samsung nie odpowiada na ping**.
- `LocalPressOnRelease`: w układzie pełnym i w arkuszu „Więcej” komenda idzie po puszczeniu palca.
- Klawiatura: puste pole po „Zatwierdź”, brak podwójnego wysłania.
- Skan i WoL tylko w sieci Wi-Fi/Ethernet bez VPN.
- Testy JUnit 4 w CI (`app/src/test`): Proto, parseIpv4, normalizeMac, resolveTarget, AppShortcut, textEdit.
- Drobne: LG bez `ws://` przy przypiętym certyfikacie, anulowanie parowania bez błędu, cel TV po ręcznym IP Samsunga, osobne szybkie ponawianie TV/dekoder, `ProtoIo.read` odporny na złe dane, ścisła walidacja MAC (też `AABBCCDDEEFF`), `use {}` dla certyfikatu, typy sesji w `remote/Session.kt`, CI: `contents: write` tylko w zadaniu `release`.

## Testy na urządzeniach (v0.7.3) – do wykonania przez użytkownika
- [ ] Downloader na dekoderze: wpisanie adresu w telefonie, poprawka w środku, „Wyczyść”, „Wklej”, „Zatwierdź” (Enter po tekście).
- [ ] Lista ostatnich tekstów: pojawia się po „Zatwierdź”, stuknięcie wpisuje tekst, krzyżyk usuwa.
- [ ] Samsung: wpisywanie tekstu nadal działa (zmiana tylko w arkuszu i kodzie dekodera).

## Testy na urządzeniach (v0.7.2) – do wykonania przez użytkownika
- [ ] Samsung: „Połączono” przez min. 2 min bez dotykania. Po odłączeniu TV od prądu zmiana stanu w ok. 20 s. (ping, `1abe700`)
- [ ] Układ pełny: przewijanie zaczęte na przycisku nie wysyła klawisza. Stuknięcie i przytrzymanie VOL działają.
- [ ] Dekoder: wpisanie tekstu, szybkie „Zatwierdź”, ponowne otwarcie klawiatury – pole puste.
- [ ] Wyłączone Wi-Fi: „Szukaj…” pokazuje komunikat o braku Wi-Fi.
- [ ] Wpisanie `192.168.1.50:8002`: komunikat, adres nie jest zapisany, ustawienia zostają otwarte.
- LG (7.1): nietestowane – użytkownik nie ma TV LG.

## Otwarte sprawy
- Poprzednie tagi są lekkie (lightweight), `v0.7.2` jest z adnotacją. Bez wpływu na CI.
- `PROJECT.md` jest nieśledzony w git (decyzja użytkownika, czy go dodać).
- Lista „Do zrobienia” w [PLAN.md](PLAN.md) – bez zmian w tej wersji.

## Dziennik
- 2026-11-12: Wdrożenie POPRAWKI.md (zadania 1–7.9), 15 commitów. Push na `main`, CI sukces.
- 2026-11-15: Diagnoza tekstu w Downloaderze przez ADB i skrypt testowy. Poprawka liczników IME i kolejki wstawień w `AtvRemote.kt`.
- 2026-11-13: Tag `v0.7.2` wysłany. Utworzony PROJECT.md. Usunięty POPRAWKI.md (wszystkie zadania wykonane; plik nie był w git). CI dla taga: sukces, release v0.7.2 z 2 plikami APK.

# Stadiul optimizărilor Cămara

Lista urmărește identificatorii din auditul pentru 1.2.9. API 0.9.12 este activ prin HTTPS pe ZimaOS, iar Android 1.2.13 este instalat pe Xiaomi 14 și Huawei MatePad 11.5 S. Schema 8 folosește un volum separat, cu ambele volume anterioare păstrate.

| Zonă | Acoperit în cod | Mai trebuie verificat sau implementat |
|---|---|---|
| Securitate | SEC-1–SEC-10. ZimaOS citește cheile din fișiere private. API-ul limitează autentificarea și consumul AI, iar Caddy folosește portul de pe IP-ul privat. | Limitarea suplimentară direct în Caddy necesită un modul sau un proxy separat. Rotația cheilor de furnizor necesită emiterea de chei noi în conturile respective. |
| Sincronizare | SYNC-1–SYNC-10, cu revizii compacte pentru colecțiile confirmate, protecția operațiilor aflate deja în jurnal și inițializare de fundal. | Migrarea la Room rămâne condiționată de o măsurătoare care să justifice schimbarea. |
| Server | API-2 include diferențele, comprimarea și paginarea în Android. API-3 colectează cheile de rejucare expirate. API-4 verifică revizii în tranzacție. API-8 limitează validarea la loturile afectate. API-10 validează copia pe disc înainte de a bloca scrierile. API-11 și API-13 folosesc indexuri și cache. | Istoricul utilizatorului și identificatorii vechi fără termen de rejucare sunt păstrați pentru calcule și operații offline. |
| AI | AI-1–AI-14 acoperă alegerea, cache-ul, contextul și termenele. Groq și OpenRouter au răspuns la conversația de probă, cu date fictive. | Profile comparative pentru calitatea răspunsurilor, pe un set mai mare de rețete. |
| Interfață | Navigația și bara aplicației sunt separate, iar starea barei și mesajele sunt observate independent de ecran. Istoricul folosește pagini și are mod offline. Rețetele din catalog au conversație AI. Tranzițiile păstrează un singur ecran activ. | Coordonarea formularelor rămâne grupată în `CamaraApp`. Nu există încă o măsurătoare cu 500 de rânduri pe ambele dispozitive. |
| Build | BUILD-1–BUILD-12 includ imaginea fixată, hash-uri, uvloop, httptools, catalog Gradle, teste de contract și proba Poppler/Tesseract. Ollama păstrează oprirea rapidă după închiderea aplicației. | Probele Android pe dispozitive sunt făcute prin ADB. Un job Android cu emulator și protecția obligatorie a ramurii GitHub rămân configurări separate. |

SYNC-7 rămâne o decizie de arhitectură. Reevaluează Room după măsurarea dimensiunii și a timpului de acces la jurnal. Formatul actual păstrează aceeași scriere atomică pentru snapshot și acțiunile în așteptare.

## Verificări

Din `backend`, rulează `python benchmark_snapshot.py --rows 5000`. Benchmarkul folosește date fictive și rulează local, fără latența rețelei. La 30 de cereri pe scenariu, percentila 95 a fost 105,1 ms după modificarea datelor, 10,7 ms pentru răspunsul complet din cache și 10,3 ms pentru răspunsul fără conținut. Snapshot-ul complet avea aproximativ 730 KB, deci volumul la încărcarea inițială mai trebuie redus. Răspunsul HTTP 304 avea zero octeți în corp.

APK-ul de release are 24,20 MB față de 35,63 MB în 1.2.9. Versiunea, semnătura și amprenta fișierului instalat au fost verificate. Pe Xiaomi și Huawei, aplicația a confirmat sincronizarea jurnalului și a încărcat istoricul de bonuri și cheltuieli de pe server.

După oprirea completă a procesului, Android a raportat un timp median de lansare de 117 ms pe Xiaomi și de 218 ms pe Huawei, din trei porniri pe fiecare dispozitiv. Măsurarea din sistem acoperă lansarea activității. Sincronizarea prin rețea se verifică separat.

Au trecut 60 de teste Android și 112 teste API. Proba cu Poppler și Tesseract a trecut separat în containerul Linux de pe ZimaOS. Migrarea de la schema 7 la schema 8 a păstrat înregistrările, identificatorul bazei și confirmările de sincronizare anterioare.

Pe o copie reală a bazei de producție, migrarea de la schema 6 la schema 7 a păstrat înregistrările și identificatorul bazei. Verificarea SQLite a trecut. Cu 500 de alimente fictive și un plan pe 7 zile, mediana celor trei calcule locale a scăzut de la 3,18 s la 0,63 s. Rețetele selectate, lista de cumpărături și bugetul au coincis.

Pe o copie izolată în ZimaOS, o modificare în cămară a produs un răspuns diferențial de 241 B, față de 4.283 B pentru copia completă. Dacă revizia de plecare a ieșit din cache sau baza a fost restaurată, API-ul răspunde cu copia completă. Istoricul rămâne integral în baza de date și în copiile de siguranță.

Cu 5.000 de alimente fictive și trei ani de cheltuieli și consum zilnic, copia completă a avut 1.242.342 B JSON și 251.639 B comprimați. Răspunsul condiționat pentru date neschimbate a avut 0 B în corp și p95 de 41,4 ms în testul local.

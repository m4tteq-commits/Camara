# Release Android

Construiește cu JDK 17, Android SDK 35 și Build Tools 35.0.0. Pentru verificări locale și CI, Gradle poate genera un APK de release nesemnat. Scriptul de publicare cere o semnătură validă.

Creează și păstrează cheia de release în afara proiectului. Fă o copie de rezervă a cheii într-un loc protejat și păstrează parolele separat. Configurează local `android/keystore.properties` cu proprietățile `storeFile`, `storePassword`, `keyAlias` și `keyPassword`. Pentru căile Windows din fișier folosește slash-uri, de exemplu `C:/cale/privata/camara.p12`. Gradle citește fișierul, iar Git îl ignoră.

Din PowerShell, rulează `./scripts/build_android.ps1 -Release`. Scriptul construiește, rulează testele, verifică semnătura și copiază APK-ul semnat în `releases/`.

## Actualizare de la 1.2.9

Aplicațiile existente au fost semnate cu cheia instalării anterioare. Compară certificatul APK-ului instalat cu cel disponibil pentru semnare folosind `apksigner verify --print-certs`. Pentru o cheie nouă, pregătește lanțul de rotație semnat de cheia anterioară și testează instalarea peste versiunea existentă pe Xiaomi și Huawei. Pentru dispozitivele care implementează Android 12 și mai vechi, verifică pragul `--rotation-min-sdk-version 28` și compatibilitatea producătorului. [Documentație Android pentru rotația semnăturii](https://developer.android.com/tools/apksigner)

Actualizarea 1.2.9 → 1.2.10 cu semnătură v3 a trecut pe Xiaomi 14 (API 36) și Huawei MatePad (API 31). Setările locale au rămas disponibile și sincronizarea prin HTTPS a funcționat pe ambele. Pentru alte actualizări pe aceleași dispozitive, `scripts/sign_android_update.ps1` semnează APK-ul cu cheia nouă și include lanțul verificat. APK-ul de rotație este pentru API 28 sau mai nou. Pentru instalări noi pe API 26–27 folosește buildul semnat prin Gradle.

Păstrează împreună cheia nouă și fișierul lanțului, în afara Git. Pentru o copie portabilă, salvează parola într-un manager de parole. O copie a parolei protejată de Windows pentru contul curent nu poate fi folosită pe orice alt PC.

Înainte de test, verifică acțiunile în așteptare și salvează o copie a serverului. Dacă dispozitivul respinge certificatul, păstrează instalarea actuală și diagnostichează rotația. Dezinstalarea șterge datele locale, inclusiv jurnalul și configurarea care locuiesc în spațiul privat al aplicației.

Verifică întâi API-ul actualizat prin HTTPS, apoi sincronizarea, salvarea unei rețete, scanarea și încărcarea modelului AI pe dispozitiv cu APK-ul minificat.

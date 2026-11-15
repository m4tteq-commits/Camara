# Cămara

Cămara este o aplicație Android pentru inventarul alimentelor, organizarea cumpărăturilor, rețete și planificarea meselor. Aplicația poate lucra offline și sincronizează modificările cu API-ul Python și SQLite găzduit pe ZimaOS.

Versiunea curentă cuprinde Android **1.2.13** și API **0.9.12**, cu schema SQLite 8. Sincronizarea verifică revizia fiecărei colecții când salvează calcule offline, iar aplicația de release folosește HTTPS. Istoricul se găsește în **Mai mult → Istoric**.

1. Fă o copie SQLite, actualizează serverul și verifică sincronizarea. Vezi [ghidul ZimaOS](docs/ZIMAOS.md).
2. Configurează semnarea pentru Android și construiește APK-ul. Dacă ai deja o instalare, verifică migrarea semnăturii în [ghidul de release](docs/ANDROID-RELEASE.md).
3. În **Serverul meu**, introdu adresa HTTPS și cheia de acces. Câmpul **IP LAN** este opțional.

GroqCloud este furnizorul AI online principal, iar OpenRouter preia cererile la limită sau indisponibilitate. Poți folosi și Ollama pe server ori modelul opțional pe dispozitiv. Vezi [configurarea AI online](docs/ZIMAOS.md#furnizori-ai-online-opționali) și [gestionarea memoriei pe ZimaOS](docs/AI-MEMORY.md).

Pentru dezvoltare rulează `python -m pytest` în `backend` după instalarea `requirements-dev.txt`. Android cere JDK 17 și SDK 35. Din `android`, rulează `./gradlew testReleaseUnitTest lintRelease assembleRelease`. Istoricul este în [CHANGELOG.md](CHANGELOG.md), iar verificările și lucrările rămase sunt în [backlog](docs/OPTIMIZARI.md).

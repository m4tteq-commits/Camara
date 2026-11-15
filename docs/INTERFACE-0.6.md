# Cămara 0.6 · interfața

Versiunea Android 0.6.0, cod 8, păstrează compatibilitatea cu serverul 0.5.0. Actualizarea reorganizează ecranele native, fără schimbarea schemei de date.

- **Astăzi** are un sumar al cămării și cumpărăturilor, scurtături pentru scanare, bon și plan automat, masa următoare, porțiile pregătite și bugetul. Alimentele cu termene apropiate sau depășite sunt semnalate separat.
- **Cămară** include căutare și filtre pentru locul de depozitare și termene. Acțiunile de editare, eliminare și risipă sunt în meniul alimentului.
- **Gătește** afișează întâi timpul, porțiile și preferința principală. Motorul AI, filtrele și echipamentele sunt în secțiuni extensibile. Rețetele salvate rămân accesibile separat de editorul rețetelor personale.
- **Listă** pune în față produsele de cumpărat. Pozițiile deja bifate pot fi extinse la nevoie. Accesul la modul În magazin este separat de bifarea manuală a listei.
- **În magazin** păstrează totalul și butonul de confirmare vizibile jos. Datele magazinului și cumpărăturile anterioare au secțiuni proprii. Atingerea unui produs deschide cantitatea, prețul și opțiunile de depozitare.
- **Planul meselor** are selector de săptămână și zile, cu marcaje pentru mesele planificate. Lista de cumpărături continuă să fie calculată pentru întreaga săptămână, indiferent de ziua afișată.
- **Planul automat** grupează zilele și bugetul, preferințele și propunerea. Butonul principal rămâne vizibil jos; prețurile necunoscute continuă să blocheze salvarea până la completare și recalculare.
- **Porțiile gătite** au filtre pentru loturile cu porții rămase, cele congelate și toate loturile. Acțiunile uzuale sunt separate de raportarea porțiilor aruncate.
- **Mai mult** grupează mesele, cumpărăturile și administrarea datelor. Butonul Înapoi și revenirea Android păstrează contextul navigării, inclusiv ziua aleasă înainte de deschiderea unei rețete.

Culorile, tipografia, suprafețele și colțurile sunt comune întregii aplicații, cu palete distincte pentru modurile luminos și întunecat. Interfața folosește fonturile și pictogramele native incluse în aplicație. Nu descarcă imagini sau fonturi de pe internet.

În buildul de test există un ecran separat de verificare vizuală, protejat prin permisiunea Android DUMP. Acesta folosește date fictive în memorie pentru capturi în ambele teme, cu text mărit și în orientare orizontală. Nu se conectează la server și nu salvează datele fictive.

Verificarea 0.6.0 a fost efectuată pe Xiaomi 14: compilare, 23 de teste Android și Android Lint fără erori. Au fost inspectate ecrane goale și populate, ambele teme, text la 130% și orientarea orizontală. În aplicația reală au fost verificate păstrarea filtrului cămării după schimbarea secțiunii, revenirea din meniurile secundare și din rețetă și derularea automată la propunerea de plan. O propunere cu prețuri necunoscute a păstrat salvarea dezactivată; nu a fost confirmată în datele utilizatorului.

După verificări, copia API a datelor a fost identică cu cea de dinaintea actualizării, modul AI a rămas cel ales anterior și jurnalul de sincronizare nu avea acțiuni în așteptare. APK-ul instalat a fost verificat prin SHA-256. Rotația automată a telefonului a fost restabilită după probe. Tableta Huawei nu a fost disponibilă pentru instalarea și verificarea acestei versiuni.

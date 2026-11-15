# Cămara 0.3 · utilizare

## După ce gătești

În detaliile unei rețete sau în Plan, apasă **Am gătit**. Verifică porțiile și cantitățile, apoi **Confirmă consumul**. Poți pune zero la un aliment pe care nu l-ai folosit. Se propun mai întâi loturile cu termen apropiat; cele expirate sunt excluse. Cantitățile fără o unitate compatibilă sunt semnalate și nu sunt scăzute automat. Apa de bucătărie nu se scade.

Confirmarea salvează împreună scăderea stocului și înregistrarea din istoric. Masa din plan devine gătită. Din **Buget → Istoric → Anulează și restabilește stocul** revii asupra unei greșeli. Anularea adaugă cantitatea consumată la stocul curent, păstrând eventualele reaprovizionări ulterioare. Dacă alimentul a fost redenumit, mutat sau schimbat în altă unitate, aplicația cere corectarea manuală. Înregistrarea anulată rămâne vizibilă.

## Planificarea meselor

**Plan** arată săptămâna, cu navigare înainte/înapoi. Butonul plus al unei zile permite alegerea unei rețete salvate, datei, mesei și porțiilor. Mesele pot fi editate sau eliminate. Rețeta rămâne încorporată în plan chiar dacă nu mai apare între ultimele 30 de recomandări.

**Lista pentru săptămână** cumulează necesarul și rezervă cantitățile din inventar o singură dată, în ordinea datelor. Un aliment care expiră înainte de masă nu este considerat disponibil atunci. Mesele marcate ca gătite sunt excluse. Previzualizarea arată completările listei comune și avertizează asupra cantităților neclare. Confirmarea nu scade inventarul și nu bifează cumpărături.

Dacă un produs există deja nebifat într-o cantitate compatibilă, îl completăm numai până la necesarul calculat. Repetarea calculului cu aceleași date nu tot adună cantități. O modificare a inventarului, planului sau listei între calcul și salvare cere recalculare.

## Fără conexiune

Poți adăuga, edita sau elimina alimente și cumpărături, planifica mese, confirma consumul/risipa, înregistra cheltuieli și modifica bugetul. Schimbările apar imediat, iar bara de sus arată câte acțiuni așteaptă serverul. Jurnalul este în spațiul privat al aplicației și rezistă unei reporniri. Dezinstalarea sau ștergerea datelor Android elimină și acțiunile încă nesincronizate.

Sincronizarea se reia în aplicație și prin WorkManager când există rețea. Serverul ZimaOS trebuie să fie accesibil; o conexiune mobilă fără acces la rețeaua casei nu este suficientă. În fundal Android poate întârzia lucrările. **Importul din text și recomandările AI necesită serverul**, inclusiv atunci când modelul rulează pe dispozitiv.

Apasă bara de sus pentru **Sincronizare**. La conflict vezi valorile curente și modificarea ta. Pentru editări obișnuite poți păstra explicit modificarea locală sau renunța la ea. Pentru consum, anulare și lista calculată renunți și refaci acțiunea pe datele actualizate. Acțiunile ulterioare rămân păstrate și pot necesita la rândul lor verificare dacă depindeau de cea abandonată. Schimbarea serverului sau a cheii cere mai întâi rezolvarea jurnalului curent.

## Buget și risipă

În **Buget → Cheltuieli**, adaugă suma în lei, descrierea, data și categoria. Sumele au maximum două zecimale și sunt stocate în bani întregi. Atinge o cheltuială pentru editare sau elimin-o din pictograma coș. Bifarea unei cumpărături nu creează automat o cheltuială: introduci totalul bonului o singură dată.

Navigarea lunilor arată totalul și limita acelei luni. **Stabilește bugetul lunii** setează limita; zero o elimină. Limitele sunt comune telefonului și tabletei după sincronizare.

În Cămara, **Am aruncat** cere cantitatea, un motiv opțional și valoarea opțională a părții aruncate. Cantitatea scade din inventar, iar istoricul rămâne disponibil pentru anulare. Valoarea este cea introdusă manual; înregistrările fără valoare sunt numărate separat. Risipa nu se adaugă din nou la cheltuieli, pentru că produsele pot fi deja incluse în bonuri.

## Date și instalare

Versiunea 0.3 adaugă tabele pentru plan, cheltuieli, bugete, istoric și identificatorii acțiunilor. Migrarea păstrează inventarul, cumpărăturile și rețetele. API-ul aplică întreaga acțiune într-o tranzacție și verifică valorile de la care a pornit editarea. Reluarea aceluiași identificator nu repetă consumul sau cheltuiala.

Instalarea cu actualizare păstrează configurația, modelele și jurnalul local. Serverul se actualizează conform `ZIMAOS.md`, cu aceeași cheie și aceleași volume. Backupul bazei nu conține încă acțiunile existente exclusiv pe un dispozitiv offline.

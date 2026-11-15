# AI pe server și memoria RAM · 0.4.1

Când Cămara este vizibilă și motorul selectat este „Pe server”, Android trimite o sesiune temporară către API. Serverul preîncarcă Qwen3.5 2B fără să genereze o recomandare. Deschiderea aplicației rămâne disponibilă în timpul încărcării. Cu motorul „Pe dispozitiv”, Android nu cere încărcarea modelului de pe server.

Fiecare proces Android are propria sesiune aleatoare, reînnoită la 25 de secunde. Ieșirea din ecranul aplicației eliberează sesiunea. Modelul este descărcat din RAM după 15 secunde fără nicio sesiune activă, cu o verificare la cel mult 5 secunde. O revenire rapidă în aplicație evită reîncărcarea. Telefonul și tableta se numără separat: ieșirea de pe unul nu oprește modelul folosit de celălalt.

Dacă Android închide procesul, se pierde Wi-Fi-ul sau nu ajunge mesajul de ieșire, sesiunea expiră după 75 de secunde fără reînnoire. Modelul este apoi eliberat după intervalul de grație. Încărcarea, inferența și descărcarea sunt serializate: o recomandare deja începută este lăsată să se termine.

Ollama primește `keep_alive: "90s"` la încărcare și inferență, plus valoarea implicită `OLLAMA_KEEP_ALIVE=90s`. Cât timp există sesiuni, API-ul reînnoiește încărcarea la 30 de secunde. Dacă API-ul se oprește, timeoutul Ollama rămâne o protecție separată. La pornirea API-ului fără sesiuni, un eventual model rămas de la procesul anterior este eliberat. Modelul rămâne salvat pe disc și nu se descarcă din nou de pe internet.

API-ul, baza de date, backupurile, notificările și sincronizarea continuă să funcționeze. Verificarea `/v1/ai/status` citește doar lista modelelor și lista proceselor; nu pornește modelul. Serviciul Ollama rămâne disponibil în container cu un consum mic de bază; economia principală provine din eliberarea modelului și a procesului său de inferență. API-ul nu primește acces la socketul Docker.

Pe Android, „Gătește” afișează starea de repaus, încărcare sau disponibilitate a motorului de pe server. Prima recomandare după o pauză poate dura mai mult din cauza încărcării de pe disc. Clienții vechi pot cere în continuare o recomandare la nevoie, dar nu țin modelul pornit prin sesiuni de preîncărcare.

Teste dedicate verifică repausul la pornire, două dispozitive, revenirea în intervalul de grație, expirarea unei sesiuni pierdute, ordinea inferență/oprire, reîncercarea descărcării și autentificarea rutelor de sesiune.

Probele pe serverul ZimaOS i3-7020U cu 12 GB RAM au măsurat aproximativ 2,70 GiB pentru Ollama cu modelul încărcat și 53 MiB după eliberare. După ieșirea normală de pe ultimul dispozitiv, eliberarea a durat 15,4 secunde; după o sesiune fără reînnoire și fără mesaj de ieșire, 93,5 secunde. Sunt măsurători ale containerului Ollama, nu ale întregului server. Baza de date a rămas identică înainte și după probe.

Mecanismul folosește [preîncărcarea și descărcarea documentate de Ollama](https://docs.ollama.com/faq#how-do-i-keep-a-model-loaded-in-memory-or-make-it-unload-immediately), fără prompt la preîncărcare și cu `keep_alive: 0` la eliberare.

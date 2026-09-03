# Badge NFC

Applicazione Android in Kotlin per acquisire un badge NFC autorizzato e renderne i dati disponibili dal telefono tramite Host Card Emulation (HCE). Questa versione include l'integrazione specifica per i lettori **StrongLink SL500A/SL500F**.

## Funzioni

- Pulsante esplicito per attivare e disattivare la lettura di nuovi tag.
- Lettura NDEF completa quando il tag espone contenuti standard.
- Lettura dei blocchi dati MIFARE Classic accessibili con le chiavi standard:
  - `FFFFFFFFFFFF`
  - `A0A1A2A3A4A5`
  - `D3F7D3F7D3F7`
- Tentativo con Key A e Key B per ogni settore.
- Esclusione dei sector trailer, che contengono chiavi e bit di accesso.
- Salvataggio locale, rinomina, aggiornamento ed eliminazione dei badge.
- Selezione di un badge da rendere attivo tramite Android HCE.
- Emulazione NFC Forum Type 4 per i contenuti NDEF.
- Protocollo HCE/APDU dedicato per fornire al programma di timbratura i blocchi acquisiti.
- Lettura diretta di un blocco MIFARE salvato tramite un singolo comando APDU, pensata per lo SDK SL500.
- Adattatore C# per `MasterRD.dll` in `integrations/sl500/Sl500HceClient.cs`.

I dati restano nelle preferenze private dell'app. Il backup Android è disattivato e il servizio HCE richiede che il telefono sia sbloccato.

## Uso dell'app

1. Installa l'app su un telefono Android con NFC.
2. Premi **Attiva lettura**.
3. Avvicina il badge al retro del telefono e attendi il riepilogo.
4. Premi **Salva questo badge**.
5. Premi **Usa sul telefono** sul badge salvato.
6. Sblocca il telefono e avvicinalo al lettore.

L'attivazione della lettura disattiva il badge HCE per evitare conflitti tra reader mode e card emulation.

## Limite hardware importante

Android HCE non può comportarsi come una carta MIFARE Classic a livello radio. In particolare non può riprodurre:

- il protocollo MIFARE Classic;
- Crypto1 e l'autenticazione dei settori;
- l'UID hardware della carta;
- i comandi proprietari o il layout fisico della memoria.

Il badge MIFARE viene quindi acquisito come insieme di blocchi e reso disponibile attraverso una carta virtuale **ISO-DEP/APDU**. Il lettore esistente deve supportare ISO-DEP e il programma di timbratura deve selezionare l'AID descritto sotto. Un lettore configurato esclusivamente per MIFARE Classic non rileverà il telefono, indipendentemente dal codice dell'app.

## Compatibilità StrongLink SL500

La sigla SL500 identifica una famiglia, non un singolo lettore:

| Variante | Protocolli utili | Telefono Android HCE |
| --- | --- | --- |
| SL500L | MIFARE Classic/Ultralight | Non compatibile |
| SL500A | ISO14443A e Mifare_ProX/Type A-4 | Compatibile tramite APDU |
| SL500D | ISO15693 | Non compatibile |
| SL500F | ISO14443A/B/15693 e carte Type A-4 | Compatibile tramite APDU, se abilitato nel firmware |

Per SL500A/F il programma di timbratura deve usare:

```text
rf_init_type('A') → rf_typea_rst(0x52) → rf_cos_command(APDU)
```

Il normale percorso `rf_request`/`rf_M1_authentication`/`rf_M1_read` deve rimanere disponibile per i badge fisici. Il percorso HCE è separato perché il telefono viene rilevato come carta ISO14443A-4, non come MIFARE Classic.

Un adattatore C# pronto da integrare è disponibile in [`integrations/sl500`](integrations/sl500/README.md). Usa le funzioni ufficiali `rf_get_model`, `rf_init_type`, `rf_typea_rst`, `rf_cos_command` e `rf_cl_deselect` della `MasterRD.dll`.

## Protocollo HCE per il programma di timbratura

### AID

```text
F04E464352455001
```

### 1. Selezione dell'applicazione

Comando:

```text
00 A4 04 00 08 F0 4E 46 43 52 45 50 01
```

Risposta positiva:

```text
90 00
```

### 2. Informazioni sul payload

Comando:

```text
80 CA 00 00 00
```

Risposta prima di `90 00`:

| Byte | Contenuto |
| --- | --- |
| 0 | Versione protocollo, attualmente `02` |
| 1 | Flag: bit 0 = blocchi MIFARE, bit 1 = NDEF |
| 2–3 | Lunghezza totale del payload, big endian |
| 4–5 | Numero di blocchi MIFARE acquisiti, big endian |

### 3. UID del badge originale

Comando:

```text
80 CA 01 00 00
```

La risposta contiene l'UID acquisito dal badge fisico, seguito da `90 00`. L'UID radio generato da Android non deve essere usato per identificare il dipendente.

### 4. Lettura diretta di un blocco MIFARE salvato

Comando, con indirizzo assoluto del blocco in `P1/P2`:

```text
80 B0 BLOCK_H BLOCK_L 10
```

La risposta contiene i 16 byte originali seguiti da `90 00`. Se il blocco non è stato acquisito, viene restituito `6A 83`. Questo è il comando consigliato per adattare un programma che in precedenza chiamava `rf_M1_read`.

### 5. Lettura dell'intero payload

Usare `READ BINARY` in blocchi fino a 240 byte, così dati e `SW1/SW2` restano entro il limite a un byte dello SDK SL500:

```text
00 B0 OFFSET_H OFFSET_L LE
```

La risposta contiene i dati richiesti seguiti da `90 00`.

### Formato del payload

Tutti gli interi multibyte sono big endian.

| Campo | Dimensione | Descrizione |
| --- | ---: | --- |
| Magic | 4 byte | ASCII `NFR1` |
| Flag | 1 byte | bit 0 MIFARE, bit 1 NDEF |
| Lunghezza UID | 1 byte | Numero di byte UID salvati |
| UID | variabile | UID letto, solo informativo |
| Numero blocchi | 2 byte | Numero di blocchi acquisiti |
| Blocco | variabile | Settore (1), indice assoluto (2), lunghezza (1), dati |
| Lunghezza NDEF | 2 byte | Zero quando assente |
| NDEF | variabile | Messaggio NDEF serializzato |

Il programma di timbratura può cercare il valore dipendente nel settore/blocco già usato dal badge originale, senza affidarsi all'UID del telefono.

## Compatibilità NDEF

Quando il badge salvato contiene NDEF, il servizio registra anche l'AID NFC Forum Type 4:

```text
D2760000850101
```

I lettori NDEF standard possono quindi leggere lo stesso messaggio. Questa modalità non converte un dump MIFARE arbitrario in NDEF.

## Compilazione

Requisiti:

- Android Studio con Android SDK 34
- JDK 17
- Telefono Android 8.0 o successivo con NFC
- Supporto HCE sul telefono per la modalità **Usa sul telefono**

Aprire la cartella in Android Studio e avviare la configurazione `app`, oppure eseguire:

```bash
./gradlew assembleDebug
```

L'APK debug viene generato in `app/build/outputs/apk/debug/app-debug.apk`.

## Collaudo consigliato

1. Verificare che l'app legga tutti i settori attesi del badge.
2. Controllare nel riepilogo eventuali settori non leggibili.
3. Eseguire `rf_get_model` dal programma collegato al lettore e verificare che sia SL500A/F.
4. Attivare il telefono con `rf_typea_rst`, poi selezionare l'AID con `rf_cos_command`.
5. Leggere direttamente il blocco che contiene il codice dipendente con `80 B0`.
6. Conservare il vecchio percorso MIFARE come fallback per i badge fisici.
7. Se `rf_typea_rst` non rileva il telefono sbloccato, la variante o il firmware non supporta HCE.

Usare esclusivamente badge personali e sistemi per i quali si dispone di autorizzazione.

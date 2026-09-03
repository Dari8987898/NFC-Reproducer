# Integrazione StrongLink SL500

`Sl500HceClient.cs` è un adattatore C# per il programma di timbratura che usa il file ufficiale `MasterRD.dll`.

## Modelli compatibili

- **SL500A**: percorso previsto, tramite carta Mifare_ProX/ISO14443A-4.
- **SL500F**: percorso previsto, se il firmware espone le funzioni Type A-4.
- **SL500L**: non compatibile con Android HCE; supporta MIFARE Classic e Ultralight, ma non Mifare_ProX/ISO14443A-4.
- **SL500D**: non compatibile; è la variante ISO15693.

Il modello va letto dal programma con `rf_get_model`. La scritta frontale “RFID Reader” non identifica la variante.

## Inserimento nel programma esistente

1. Copiare `Sl500HceClient.cs` nel progetto C#.
2. Usare la `MasterRD.dll` ufficiale della stessa architettura del processo (`x86` o `x64`).
3. Riutilizzare l'ID dispositivo e la connessione già aperta dal programma.
4. Nel ciclo di rilevamento, provare prima il normale badge MIFARE e poi il percorso HCE, oppure viceversa.

Esempio, supponendo che la connessione al lettore sia già aperta e che il dato dipendente si trovi nel blocco assoluto 9:

```csharp
using (var phone = new BadgeNfc.StrongLink.Sl500HceClient(deviceId: 0))
{
    phone.ActivatePhone();
    byte[] employeeBlock = phone.ReadClassicBlock(9);
    RegistraTimbratura(employeeBlock);
}
```

Se il programma non apre già il lettore:

```csharp
Sl500HceClient.OpenPort(comPortNumber: 4, baudRate: 115200);
try
{
    using (var phone = new Sl500HceClient(deviceId: 0))
    {
        string model = phone.GetReaderModel();
        phone.ActivatePhone();
        byte[] originalUid = phone.ReadOriginalUid();
        byte[] employeeBlock = phone.ReadClassicBlock(9);
    }
}
finally
{
    Sl500HceClient.ClosePort();
}
```

Usare la porta COM e il baud rate già configurati nel programma reale: `115200` nell'esempio non è una garanzia sul dispositivo installato.

## Differenza rispetto al percorso MIFARE

Il percorso fisico esistente è tipicamente:

```text
rf_request → rf_anticoll → rf_select → rf_M1_authentication → rf_M1_read
```

Il percorso telefono deve essere:

```text
rf_init_type('A') → rf_typea_rst → rf_cos_command(SELECT AID)
→ rf_cos_command(READ BLOCK) → rf_cl_deselect
```

Non chiamare `rf_M1_authentication` o `rf_M1_read` sul telefono: Android HCE non implementa MIFARE Classic.

## Errori significativi

- `rf_typea_rst` fallisce sempre con telefono sbloccato e HCE attivo: probabile SL500L, firmware senza Type A-4 o antenna/posizionamento non adatti.
- SELECT AID restituisce `6A82`: servizio HCE non selezionabile o AID errato.
- Un blocco restituisce `6A83`: quel blocco non è stato acquisito dall'app, oppure è un sector trailer escluso intenzionalmente.
- L'applicazione genera `BadImageFormatException`: architettura di `MasterRD.dll` diversa da quella del processo.

Il polling e la registrazione della timbratura restano responsabilità del programma aziendale.

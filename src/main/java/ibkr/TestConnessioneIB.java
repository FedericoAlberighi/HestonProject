package ibkr;

import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;

public class TestConnessioneIB {

    public static void main(String[] args) throws InterruptedException {
        // 1. Inizializza chi riceve (il Wrapper) e i Segnali
        IBWrapperImpl wrapper = new IBWrapperImpl();
        EReaderSignal signal = new EJavaSignal();

        // 2. Inizializza chi invia (il ClientSocket)
        EClientSocket client = new EClientSocket(wrapper, signal);

        // 3. Connettiti!
        // Parametri: (IP, Porta, ClientID)
        // IP: 127.0.0.1 (il tuo computer locale)
        // Porta: 7497 (Porta standard per il Paper Trading. Se usi il conto Live, prova 7496)
        // ClientID: 1 (un numero a caso per identificare il tuo programma Java)
        System.out.println("Tentativo di connessione alla TWS in corso...");
        client.eConnect("127.0.0.1", 7497, 1);

        // 4. Avvia i thread di lettura in background (necessario per l'asincronia)
        final EReader reader = new EReader(client, signal);
        reader.start();

        new Thread(() -> {
            while (client.isConnected()) {
                signal.waitForSignal();
                try {
                    reader.processMsgs();
                } catch (Exception e) {
                    System.out.println("Errore nel reader: " + e.getMessage());
                }
            }
        }).start();

        // Aspettiamo 3 secondi per dare il tempo alla TWS di risponderci
        Thread.sleep(3000);

        // Disconnettiti alla fine del test
        System.out.println("Chiusura della connessione...");
        client.eDisconnect();
    }
}
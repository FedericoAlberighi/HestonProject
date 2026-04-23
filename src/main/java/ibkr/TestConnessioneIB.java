package ibkr;

import com.ib.client.EClientSocket;
import com.ib.client.EJavaSignal;
import com.ib.client.EReader;
import com.ib.client.EReaderSignal;

import java.util.Set;

public class TestConnessioneIB {

    public static void main(String[] args) throws InterruptedException {
        IBWrapperImpl wrapper = new IBWrapperImpl() {
            @Override
            public void error(int id, int errorCode, String errorMsg) {

            }

            @Override
            public void securityDefinitionOptionParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes) {

            }

            @Override
            public void securityDefinitionOptionParameterEnd(int reqId) {

            }
        };
        EReaderSignal signal = new EJavaSignal();

        EClientSocket client = new EClientSocket(wrapper, signal);


        System.out.println("Tentativo di connessione alla TWS in corso...");
        client.eConnect("127.0.0.1", 7497, 1);

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

        Thread.sleep(3000);

        System.out.println("Chiusura della connessione...");
        client.eDisconnect();
    }
}
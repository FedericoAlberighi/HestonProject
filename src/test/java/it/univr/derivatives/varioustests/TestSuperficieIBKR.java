package it.univr.derivatives.varioustests;

import it.univr.derivatives.marketdataprovider.IBKRMarketDataProvider;
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;

public class TestSuperficieIBKR {

    public static void main(String[] args) {
        try {
            System.out.println("Avvio test Superficie di Volatilità LIVE...");

            // Chiediamo la superficie di Apple (AAPL)
            // Usiamo AAPL perché con i conti Paper Trading gratuiti le opzioni americane funzionano sempre
            OptionSurfaceData surface = IBKRMarketDataProvider.getLiveVolatilitySurface("SPY", "STK", "SMART", "USD");

            System.out.println("\n====================================");
            if (surface != null) {
                System.out.println("SUCCESSO ASSOLUTO! L'oggetto OptionSurfaceData è stato creato.");
                System.out.println("Nome della superficie: " + surface.getName());
                System.out.println("Data di riferimento: " + surface.getReferenceDate());
            } else {
                System.out.println("Qualcosa è andato storto, la superficie è null.");
            }
            System.out.println("====================================");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}









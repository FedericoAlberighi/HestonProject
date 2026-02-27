package it.univr.derivatives.varioustests;

import it.univr.derivatives.marketdataprovider.IBKRMarketDataProvider;
import it.univr.derivatives.utils.TimeSeries;

public class TestSpotIBKR {

    // Il metodo main inizia qui
    public static void main(String[] args) {

        // =======================================================
        // PARTE 1: RICHIESTA PREZZO SPOT (ISP)
        // =======================================================
        System.out.println("Richiesta prezzo in tempo reale a Interactive Brokers in corso...");

        try {
            double spotDax = IBKRMarketDataProvider.getRealTimeSpotPrice("ISP", "STK", "SMART", "EUR");
            System.out.println("====================================");
            if (spotDax > 0) {
                System.out.println("IL PREZZO SPOT DI ISP È: " + spotDax);
            } else {
                System.out.println("ATTENZIONE: Nessun prezzo ricevuto (Mercato chiuso o permessi mancanti?).");
            }
            System.out.println("====================================");

        } catch (Exception e) {
            e.printStackTrace();
        }

        // =======================================================
        // PARTE 2: RICHIESTA DATI STORICI E GRAFICO (AAPL)
        // =======================================================
        System.out.println("\nAvvio scaricamento dati storici...");

        try {
            String simbolo = "AAPL";
            // Scarichiamo 1 anno ("1 Y") di dati giornalieri
            TimeSeries storico = IBKRMarketDataProvider.getHistoricalData("PYPL", "STK", "SMART", "USD", "1 Y");            System.out.println("====================================");
            if (storico != null && storico.size() > 0) {
                System.out.println("SUCCESSO! Ho scaricato " + storico.size() + " giorni di contrattazione.");

                // Statistiche
                System.out.printf("Prezzo Minimo: %.2f\n", storico.getMin());
                System.out.printf("Prezzo Massimo: %.2f\n", storico.getMax());

                // Disegniamo il grafico
                System.out.println("Apertura del grafico in corso...");
                storico.plot("Grafico Storico " + simbolo + " (Ultimo Anno)");

            } else {
                System.out.println("ATTENZIONE: Nessun dato storico trovato.");
            }
            System.out.println("====================================");

        } catch (Exception e) {
            e.printStackTrace();
        }

    } // <--- FINE DEL METODO MAIN (Ora racchiude tutto!)

} // <--- FINE DELLA CLASSE
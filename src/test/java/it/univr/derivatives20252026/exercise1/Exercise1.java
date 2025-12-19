package it.univr.derivatives20252026.exercise1;

import java.time.LocalDate;
import java.util.Set;
import java.util.TreeMap;

import it.univr.derivatives.marketdataprovider.MarketDataProvider;
import it.univr.derivatives.utils.HestonCalibrationClass;
import it.univr.derivatives.utils.TimeSeries;
import net.finmath.fouriermethod.calibration.CalibratedModel.OptimizationResult;
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;
import net.finmath.modelling.descriptor.HestonModelDescriptor;

/**
 * <p><strong>Esercizio 1: Model Risk e Ricalibrazione Storica.</strong></p>
 * * Questa classe implementa l'analisi di stabilità dei parametri del modello di Heston
 * attraverso una procedura di calibrazione giornaliera (Rolling Calibration).
 * * <p>L'obiettivo è osservare come i 5 parametri del modello (<tt>sigma, theta, kappa, xi, rho</tt>)
 * evolvono nel tempo per adattarsi alle nuove superfici di volatilità di mercato.</p>
 * * <p>La classe utilizza un approccio <b>Sequenziale (Bootstrapping)</b>: i parametri ottimali
 * calibrati al giorno <i>t</i> vengono utilizzati come <i>Initial Guess</i> per la calibrazione
 * del giorno <i>t+1</i>. Questo garantisce una maggiore continuità e riduce il rischio
 * di cadere in minimi locali distanti tra un giorno e l'altro.</p>
 * * @author Alice Bonizzoni
 * *@author Federico Alberighi
 * @version 1.0
 */
public class Exercise1 {

    /**
     * Punto di ingresso dell'applicazione.
     * <p>Il workflow è il seguente:</p>
     * <ol>
     * <li>Caricamento delle superfici di volatilità storiche (da file Excel/CSV).</li>
     * <li>Inizializzazione delle serie storiche (TimeSeries) per il salvataggio dei risultati.</li>
     * <li>Definizione dei parametri di partenza (Guess iniziale).</li>
     * <li>Esecuzione del loop giornaliero di calibrazione (con aggiornamento sequenziale).</li>
     * <li>Generazione dei grafici finali.</li>
     * </ol>
     * * @param args Argomenti da riga di comando (non utilizzati in questo esercizio).
     * @throws Exception Se si verificano errori durante il caricamento dei dati o l'ottimizzazione.
     */
    public static void main(String[] args) throws Exception {

        // =======================================================================
        // 1. CARICAMENTO DATI DI MERCATO
        // =======================================================================
        System.out.println("Caricamento dati di mercato...");

        // TreeMap ordinata per data contenente le superfici di volatilità (Smile/Skew)
        TreeMap<LocalDate, OptionSurfaceData> marketData = MarketDataProvider.getVolatilityDataContainer();
        Set<LocalDate> keys = marketData.keySet();

        // =======================================================================
        // 2. PREPARAZIONE STRUTTURE DATI
        // =======================================================================

        // Inizializzazione delle TimeSeries per tracciare l'evoluzione di ogni parametro
        TimeSeries volatilityTimeSeries = new TimeSeries(); // Sigma (volatilità spot)
        TimeSeries thetaTimeSeries = new TimeSeries();      // Theta (varianza di lungo periodo)
        TimeSeries kappaTimeSeries = new TimeSeries();      // Kappa (velocità di mean reversion)
        TimeSeries xiTimeSeries = new TimeSeries();         // Xi (volatilità della volatilità)
        TimeSeries rhoTimeSeries = new TimeSeries();        // Rho (correlazione spot-vol)
        TimeSeries rmseTimeSeries = new TimeSeries();       // RMSE (errore di calibrazione)

        /*
         * Parametri iniziali (Initial Guess) per il primo giorno di calibrazione.
         * L'ordine nell'array è fondamentale per l'ottimizzatore:
         * [0]: volatility (sqrt(V0))
         * [1]: theta
         * [2]: kappa
         * [3]: xi
         * [4]: rho
         */
        double[] currentParameters = new double[] {
                0.0423,
                0.0818,
                0.8455,
                0.4639,
                -0.4
        };

        // Istanza della classe helper che incapsula la logica di Finmath
        HestonCalibrationClass calibrator = new HestonCalibrationClass();

        /*
         * Definizione finestra temporale di analisi.
         * È consigliabile limitare il periodo per gestire i tempi di calcolo.
         */
        LocalDate startDate = LocalDate.of(2010, 3, 1);
        LocalDate limit = LocalDate.of(2010, 4, 15);

        System.out.println("Inizio calibrazione dal " + startDate + " al " + limit);
        System.out.println("-----------------------------------------------------------------------------------------------------------------------------------");
        // Header della tabella di output per il monitoraggio in console
        System.out.printf("%-12s | %-10s | %-10s | %-10s | %-10s | %-10s | %-10s | %-6s | %-8s%n",
                "Data", "RMSE", "Vol", "Theta", "Kappa", "Xi", "Rho", "Iter", "Time(s)");
        System.out.println("-----------------------------------------------------------------------------------------------------------------------------------");

        // =======================================================================
        // 3. LOOP DI CALIBRAZIONE GIORNALIERA
        // =======================================================================
        for(LocalDate today : keys) {

            // Skip delle date fuori dalla finestra temporale selezionata
            if(today.isBefore(startDate)) continue;
            if(today.isAfter(limit)) break;

            // Recupero la superficie di volatilità specifica per il giorno corrente
            OptionSurfaceData todaysMarket = marketData.get(today);

            try {
                // Timer per monitorare le performance di calibrazione
                long startMillis = System.currentTimeMillis();

                /*
                 * --- CHIAMATA CORE ALLA CALIBRAZIONE ---
                 * Passiamo 'currentParameters' che contiene i risultati del giorno precedente (t-1).
                 * Questo implementa la logica sequenziale (Bootstrapping dei parametri).
                 */
                OptimizationResult result = calibrator.calibrate(today, todaysMarket, currentParameters);

                long endMillis = System.currentTimeMillis();
                double calculationTime = ((endMillis - startMillis) / 1000.0);

                // --- ESTRAZIONE RISULTATI ---
                double rmse = result.getRootMeanSquaredError();
                int iterations = result.getIterations();

                // Estrazione dei parametri calibrati dal descrittore del modello Heston
                HestonModelDescriptor calibratedDescriptor = (HestonModelDescriptor) result.getModel().getModelDescriptor();

                double calibVol = calibratedDescriptor.getVolatility();
                double calibTheta = calibratedDescriptor.getTheta();
                double calibKappa = calibratedDescriptor.getKappa();
                double calibXi = calibratedDescriptor.getXi();
                double calibRho = calibratedDescriptor.getRho();

                // --- OUTPUT E AGGIORNAMENTO DATI ---

                if (Double.isFinite(rmse)) {
                    // Stampa formattata dei risultati
                    System.out.printf("%-12s | %-10.6f | %-10.4f | %-10.4f | %-10.4f | %-10.4f | %-10.4f | %-6d | %-8.3f%n",
                            today, rmse, calibVol, calibTheta, calibKappa, calibXi, calibRho, iterations, calculationTime);

                    /*
                     * AGGIORNAMENTO SEQUENZIALE (CRUCIALE):
                     * Sovrascriviamo 'currentParameters' con i valori appena trovati.
                     * Nella prossima iterazione, l'ottimizzatore partirà da questi valori,
                     * aumentando la probabilità di convergenza rapida e stabilità.
                     */
                    currentParameters[0] = calibVol;
                    currentParameters[1] = calibTheta;
                    currentParameters[2] = calibKappa;
                    currentParameters[3] = calibXi;
                    currentParameters[4] = calibRho;

                    // Salvataggio nelle strutture per il plotting
                    volatilityTimeSeries.add(today, calibVol);
                    thetaTimeSeries.add(today, calibTheta);
                    kappaTimeSeries.add(today, calibKappa);
                    xiTimeSeries.add(today, calibXi);
                    rhoTimeSeries.add(today, calibRho);
                    rmseTimeSeries.add(today, rmse);
                } else {
                    // Gestione robusta dei fallimenti numerici (RMSE infinito o NaN)
                    System.out.printf("%-12s | FAILED (RMSE: %s) | Iter: %d | Time: %.3fs%n",
                            today, rmse, iterations, calculationTime);

                    // Inserimento di NaN per indicare un buco nei dati (evita linee errate nei grafici)
                    volatilityTimeSeries.add(today, Double.NaN);
                    thetaTimeSeries.add(today, Double.NaN);
                    kappaTimeSeries.add(today, Double.NaN);
                    xiTimeSeries.add(today, Double.NaN);
                    rhoTimeSeries.add(today, Double.NaN);
                    rmseTimeSeries.add(today, Double.NaN);
                }

            } catch (Exception e) {
                // Catch generico per non interrompere l'intero loop se un solo giorno fallisce
                System.err.println("Errore critico durante la calibrazione del " + today + ": " + e.getMessage());
            }
        }

        // =======================================================================
        // 4. GENERAZIONE GRAFICI
        // =======================================================================
        System.out.println("Generazione grafici in corso...");
        try {
            if(rmseTimeSeries.size() > 0) {
                // Visualizzazione dei grafici tramite JFreeChart (incapsulato in TimeSeries)
                rmseTimeSeries.plot("Calibration Error (RMSE)");
                volatilityTimeSeries.plot("Parameter: Initial Volatility (sigma)");
                thetaTimeSeries.plot("Parameter: Theta (Long Run Variance)");
                kappaTimeSeries.plot("Parameter: Kappa (Mean Reversion)");
                xiTimeSeries.plot("Parameter: Xi (Vol of Vol)");
                rhoTimeSeries.plot("Parameter: Rho (Correlation)");
            } else {
                System.out.println("Nessun dato valido raccolto per generare i grafici.");
            }
        } catch (Exception e) {
            System.out.println("Errore durante il plotting: " + e.getMessage());
        }
    }
}
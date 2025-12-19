package it.univr.derivatives20252026.exercise3;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.Set;
import java.util.TreeMap;

import it.univr.derivatives.marketdataprovider.MarketDataProvider;
import it.univr.derivatives.utils.HestonCalibrationClass;
import net.finmath.exception.CalculationException;
import net.finmath.fouriermethod.calibration.CalibratedModel.OptimizationResult;
import net.finmath.functions.HestonModel; // Classe del Prof per le Greche (Delta)
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;
import net.finmath.modelling.descriptor.HestonModelDescriptor;
import net.finmath.optimizer.SolverException;
import net.finmath.time.daycount.DayCountConvention;
import net.finmath.time.daycount.DayCountConvention_ACT_365;

/**
 * Esercizio 3: Delta Hedging Storico nel modello di Heston.
 * Implementazione completa: Calibrazione -> Vendita Call -> Hedging Dinamico -> PnL.
 */
public class Exercise3 {

    public static void main(String[] args) throws SolverException, CalculationException {

        DecimalFormat formatter = new DecimalFormat("0.0000");
        DecimalFormat moneyFormatter = new DecimalFormat("0.00");

        System.out.println("--- ESERCIZIO 3: Delta Hedging con Dati Storici (Heston) ---");

        // =======================================================================
        // 1. CARICAMENTO DATI DI MERCATO
        // =======================================================================
        // marketData contiene le superfici di volatilità e le curve forward (prezzi)
        TreeMap<LocalDate, OptionSurfaceData> marketData = MarketDataProvider.getVolatilityDataContainer();
        Set<LocalDate> dates = marketData.keySet();

        // Fissiamo il giorno iniziale (Punto 1 della traccia)
        LocalDate startDate = LocalDate.of(2006, 1, 2);

        if (!marketData.containsKey(startDate)) {
            System.err.println("Errore: Dati non disponibili per la data " + startDate);
            return;
        }

        // =======================================================================
        // 2. CALIBRAZIONE HESTON (Solo al giorno iniziale)
        // =======================================================================
        System.out.println("1. Calibrazione modello Heston alla data " + startDate + "...");

        HestonCalibrationClass calibrator = new HestonCalibrationClass();
        double[] initialGuess = new double[] { 0.04, 0.08, 0.5, 0.4, -0.4 };

        OptimizationResult calibrationResult = calibrator.calibrate(startDate, marketData.get(startDate), initialGuess);
        HestonModelDescriptor hmd = (HestonModelDescriptor) calibrationResult.getModel().getModelDescriptor();

        // Salviamo i parametri calibrati che terremo FISSI per tutta la simulazione
        double sigma = hmd.getVolatility();
        double theta = hmd.getTheta();
        double kappa = hmd.getKappa();
        double xi = hmd.getXi();
        double rho = hmd.getRho();

        System.out.println("   Parametri Calibrati (FISSI): Sigma=" + formatter.format(sigma) +
                ", Theta=" + formatter.format(theta) + ", Kappa=" + formatter.format(kappa) +
                ", Xi=" + formatter.format(xi) + ", Rho=" + formatter.format(rho));

        // =======================================================================
        // 3. VENDITA DELL'OPZIONE (Punto 2 della traccia)
        // =======================================================================
        int monthsToMaturity = 2;
        LocalDate maturityDate = startDate.plusMonths(monthsToMaturity);
        DayCountConvention dayCounter = new DayCountConvention_ACT_365();

        // Recuperiamo lo Spot Iniziale dalla curva Forward presente nei dati
        double initialSpot = marketData.get(startDate).getEquityForwardCurve().getValue(0.0);

        // Setup Opzione: Call, Strike 95% Spot
        double strike = initialSpot * 0.95;
        double riskFreeRate = 0.0;
        double dividendYield = 0.0;

        double timeToMaturityInitial = dayCounter.getDaycountFraction(startDate, maturityDate);

        // Calcolo del Premio Iniziale (usando il modello Fourier di Finmath)
        net.finmath.fouriermethod.products.EuropeanOption option =
                new net.finmath.fouriermethod.products.EuropeanOption(timeToMaturityInitial, strike);

        // Modello Heston per il pricing (Finmath)
        net.finmath.fouriermethod.models.HestonModel pricingModel =
                new net.finmath.fouriermethod.models.HestonModel(
                        initialSpot, riskFreeRate, sigma, theta, kappa, xi, rho
                );

        double optionPremium = option.getValue(pricingModel);

        System.out.println("\n2. Vendita Opzione Call");
        System.out.println("   Maturity: " + maturityDate + " (T=" + formatter.format(timeToMaturityInitial) + ")");
        System.out.println("   Spot Iniziale: " + moneyFormatter.format(initialSpot));
        System.out.println("   Strike (95%) : " + moneyFormatter.format(strike));
        System.out.println("   Premio Incassato: " + moneyFormatter.format(optionPremium));

        // =======================================================================
        // 4. INIZIALIZZAZIONE STRATEGIA DI HEDGING
        // =======================================================================
        // Calcolo Delta Iniziale usando la classe statica fornita dal Prof (net.finmath.functions.HestonModel)
        double currentDelta = HestonModel.hestonOptionDelta(
                initialSpot, riskFreeRate, dividendYield,
                sigma, theta, kappa, xi, rho,
                timeToMaturityInitial, strike);

        // Costruzione Portafoglio di Replica:
        // Incassiamo il premio, ma spendiamo soldi per comprare 'Delta' azioni.
        double stocksHeld = currentDelta;
        double bankAccount = optionPremium - (stocksHeld * initialSpot);

        System.out.println("\n3. Inizializzazione Hedging");
        System.out.println("   Delta Iniziale: " + formatter.format(currentDelta));
        System.out.println("   Azioni Comprate: " + formatter.format(stocksHeld));
        System.out.println("   Conto Banca Init: " + moneyFormatter.format(bankAccount));
        System.out.println("----------------------------------------------------------");

        // =======================================================================
        // 5. LOOP DI HEDGING DINAMICO (Punto 3 della traccia)
        // =======================================================================
        LocalDate previousDate = startDate;

        for (LocalDate currentDate : dates) {
            // Saltiamo le date fino al giorno dopo l'inizio
            if (!currentDate.isAfter(startDate)) continue;
            // Ci fermiamo alla scadenza
            if (currentDate.isAfter(maturityDate)) break;

            // Recupero Dati di Mercato (Spot Corrente)
            OptionSurfaceData todaysData = marketData.get(currentDate);
            double currentSpot = todaysData.getEquityForwardCurve().getValue(0.0);

            // A. Maturazione Interessi sul Conto Banca
            double dt = dayCounter.getDaycountFraction(previousDate, currentDate);
            bankAccount = bankAccount * Math.exp(riskFreeRate * dt);

            // B. Calcolo Tempo a Scadenza residuo
            double timeToMaturity = dayCounter.getDaycountFraction(currentDate, maturityDate);

            // Se siamo troppo vicini a scadenza (es. meno di mezza giornata), fermiamo il ribilanciamento
            // per evitare instabilità numeriche del Delta (Gamma esplosivo).
            if (timeToMaturity < 0.001) break;

            // C. Calcolo Nuovo Delta
            // IMPORTANTE: Usiamo Spot Corrente ma Parametri Heston FISSI (Risk of Model)
            double newDelta = HestonModel.hestonOptionDelta(
                    currentSpot, riskFreeRate, dividendYield,
                    sigma, theta, kappa, xi, rho,
                    timeToMaturity, strike);

            // D. Ribilanciamento (Rebalancing)
            double deltaChange = newDelta - stocksHeld;
            double transactionCost = deltaChange * currentSpot;

            bankAccount -= transactionCost; // Addebito/Accredito costo transazione
            stocksHeld = newDelta;          // Aggiornamento posizione titoli

            previousDate = currentDate;
        }

        // =======================================================================
        // 6. CALCOLO ERRORE A SCADENZA (Punto 4 della traccia)
        // =======================================================================

        // Troviamo la data disponibile nei dati più vicina alla scadenza reale (floorKey)
        LocalDate finalDate = marketData.floorKey(maturityDate);
        double finalSpot = marketData.get(finalDate).getEquityForwardCurve().getValue(0.0);

        // Valore del Portafoglio di Replica (Soldi in Banca + Valore Azioni finali)
        double replicaPortfolioValue = bankAccount + (stocksHeld * finalSpot);

        // Payoff Reale dell'Opzione (Liability verso il cliente)
        // Essendo una Call venduta, dobbiamo pagare max(S - K, 0)
        double optionPayoff = Math.max(finalSpot - strike, 0.0);

        // Errore di Hedging (PnL della strategia)
        double hedgingError = replicaPortfolioValue - optionPayoff;

        System.out.println("\n--- RISULTATI A SCADENZA (" + finalDate + ") ---");
        System.out.println("Spot Finale DAX      : " + moneyFormatter.format(finalSpot));
        System.out.println("Valore Portafoglio   : " + moneyFormatter.format(replicaPortfolioValue) + " (Banca + Azioni)");
        System.out.println("Payoff Dovuto (Call) : " + moneyFormatter.format(optionPayoff));
        System.out.println("----------------------------------------------------------");
        System.out.println("HEDGING ERROR (PnL)  : " + moneyFormatter.format(hedgingError));
        System.out.println("----------------------------------------------------------");
        System.out.println("Nota: L'errore non è zero. Abbiamo coperto il rischio Delta (Prezzo),");
        System.out.println("ma siamo rimasti esposti al rischio Vega (Volatilità) e Vol-of-Vol.");
    }
}
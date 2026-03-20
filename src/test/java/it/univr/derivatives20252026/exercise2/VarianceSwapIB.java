package it.univr.derivatives20252026.exercise2;
import java.time.LocalDate;
import java.util.function.DoubleUnaryOperator;

import it.univr.derivatives.marketdataprovider.IBKRMarketDataProvider;
import it.univr.derivatives.utils.CarrMadanGenericIntegrator;
import it.univr.derivatives.utils.HestonCalibrationClass;
import net.finmath.fouriermethod.calibration.CalibratedModel.OptimizationResult;
import net.finmath.fouriermethod.models.HestonModel;
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;
import net.finmath.modelling.descriptor.HestonModelDescriptor;
public class VarianceSwapIB {
    public static void main(String[] args) throws Exception {

        System.out.println("--- EXERCISE 2: Variance Swap Pricing (LIVE IBKR DATA) ---");

        // =======================================================================
        // STEP 1: CALIBRATION (LIVE IBKR DATA)
        // =======================================================================
        System.out.println("\n1. Collegamento a IBKR e scaricamento superficie live...");

        // Usiamo Apple (AAPL) come esempio, ma potresti passare questi parametri come input
        String symbol = "SPX";
        String secType = "IND";
        String exchange = "CBOE";
        String currency = "USD";

        // CHIAMATA AL TUO NUOVO MARKET DATA PROVIDER
        OptionSurfaceData marketData = IBKRMarketDataProvider.getLiveVolatilitySurface(symbol, secType, exchange, currency);

        if(marketData == null) {
            throw new RuntimeException("Impossibile recuperare i dati live da TWS. Assicurati che sia aperta!");
        }

        System.out.println("\n2. Avvio calibrazione del Modello di Heston sui dati reali...");

        // Initial guess per l'ottimizzatore
        double[] guess = new double[] {
                0.20, // sigma (volatilità iniziale)
                0.04, // theta (varianza di lungo periodo)
                2.0,  // kappa (velocità di mean reversion)
                0.5,  // xi (vol of vol)
                -0.7  // rho (correlazione)
        };

        HestonCalibrationClass calibrator = new HestonCalibrationClass();
        // Usiamo la data odierna per la calibrazione live
        LocalDate today = LocalDate.now();
        OptimizationResult result = calibrator.calibrate(today, marketData, guess);

        HestonModelDescriptor realParams = (HestonModelDescriptor) result.getModel().getModelDescriptor();

        System.out.println("   Calibration RMSE: " + result.getRootMeanSquaredError());
        System.out.printf("   Parametri Calibrati su %s: Sigma=%.4f, Theta=%.4f, Kappa=%.4f, Xi=%.4f, Rho=%.4f%n",
                symbol, realParams.getVolatility(), realParams.getTheta(), realParams.getKappa(), realParams.getXi(), realParams.getRho());


        // =======================================================================
        // STEP 2: PRICING ENVIRONMENT SETUP
        // =======================================================================
        System.out.println("\n3. Configurazione ambiente di pricing (Spot=100 per normalizzazione)...");

        double initialSpot = 100; // Usiamo 100 per rendere il prezzo del VarSwap una percentuale leggibile
        double riskFreeRate = 0;
        // Assumiamo un tasso privo di rischio del 3%
        double[] availableMaturities = marketData.getMaturities();
        double maturity = availableMaturities[0]; // prima scadenza disponibile (~31/365)
        System.out.printf("Scadenza reale usata: %.4f anni (%.0f giorni)%n",
                maturity, maturity * 365);
        double forwardPrice = initialSpot * Math.exp(riskFreeRate * maturity);
        double discountFactor = Math.exp(-riskFreeRate * maturity);

        HestonModel pricingModel = new HestonModel(
                initialSpot,
                riskFreeRate,
                realParams.getVolatility(),
                realParams.getTheta(),
                realParams.getKappa(),
                realParams.getXi(),
                realParams.getRho()
        );


        // =======================================================================
        // STEP 3: METHOD A - ANALYTICAL FORMULA
        // =======================================================================
        double v0 = realParams.getVolatility() * realParams.getVolatility();
        double theta = realParams.getTheta();
        double kappa = realParams.getKappa();

        double Factor = (1 - Math.exp(-kappa * maturity)) / (kappa * maturity);
        double fairVarSwapRate = theta + (v0 - theta) * Factor;


        // =======================================================================
        // STEP 4: METHOD B - STATIC REPLICATION (Carr-Madan)
        // =======================================================================
        DoubleUnaryOperator varianceSwapSecondDerivative = k -> 1.0 / (k * k);

        CarrMadanGenericIntegrator replicator = new CarrMadanGenericIntegrator(
                pricingModel,
                maturity,
                forwardPrice,
                discountFactor,
                1000
        );

        double integralValue = replicator.calculateIntegralValue(varianceSwapSecondDerivative);
        double varianceSwapRateReplication = (2.0 / (maturity * discountFactor)) * integralValue;


        // =======================================================================
        // RESULTS COMPARISON
        // =======================================================================
        System.out.println("\n--- RISULTATI VARIANCE SWAP LIVE (" + symbol + ") ---");
        System.out.printf("Maturity:     %.4f anni (30 giorni)%n", maturity);
        System.out.printf("Analitico:    %.6f%n", fairVarSwapRate);
        System.out.printf("Replicazione: %.6f%n", varianceSwapRateReplication);

        double diff = Math.abs(fairVarSwapRate - varianceSwapRateReplication);
        System.out.printf("Differenza:   %.8f%n", diff);

        if(diff < 1E-3) {
            System.out.println(">> SUCCESSO: Il prezzo live è consistente tra i due metodi.");
        } else {
            System.out.println(">> NOTA: Piccola divergenza dovuta alla curvatura dello skew live.");
        }

        System.out.println("\nIl valore calcolato rappresenta la varianza attesa 'equa' per i prossimi 30 giorni.");
        System.out.printf("Volatilità corrispondente (Strike Vol): %.2f%%%n", Math.sqrt(fairVarSwapRate) * 100);
    }
}


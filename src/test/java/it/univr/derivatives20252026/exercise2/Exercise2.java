package it.univr.derivatives20252026.exercise2;

import java.text.DecimalFormat;
import java.time.LocalDate;
import java.util.function.DoubleUnaryOperator;

import it.univr.derivatives.marketdataprovider.MarketDataProvider;
import it.univr.derivatives.utils.CarrMadanGenericIntegrator;
import it.univr.derivatives.utils.HestonCalibrationClass;
import net.finmath.exception.CalculationException;
import net.finmath.fouriermethod.calibration.CalibratedModel.OptimizationResult;
import net.finmath.fouriermethod.models.HestonModel;
import net.finmath.fouriermethod.products.EuropeanOption;
import net.finmath.integration.TrapezoidalRealIntegrator;
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;
import net.finmath.modelling.descriptor.HestonModelDescriptor;

public class Exercise2 {

    /**
     * Demonstrates variance swap pricing via analytic and replication methods
     */
    public static void main(String[] args) throws Exception {

        System.out.println("--- ESERCIZIO 2: Variance Swap Pricing (Heston vs Replica) ---");
        DecimalFormat df = new DecimalFormat("0.00000000");

        // =======================================================================
        // STEP 1: CALIBRAZIONE (Mondo Reale)
        // =======================================================================
        System.out.println("\n1. Calibrazione sui dati di mercato reali...");

        LocalDate calibrationDate = LocalDate.of(2010, 1, 7);
        OptionSurfaceData marketData = MarketDataProvider.getVolatilityDataContainer().get(calibrationDate);

        if(marketData == null) {
            throw new RuntimeException("Dati non trovati per la data: " + calibrationDate);
        }

        // Initial guess
        double[] guess = new double[] {
                0.0423, // volatility (sqrt(V0))
                0.0818, // theta
                0.8455, // kappa
                0.4639, // xi (nu)
                -0.4    // rho
        };

        HestonCalibrationClass calibrator = new HestonCalibrationClass();
        OptimizationResult result = calibrator.calibrate(calibrationDate, marketData, guess);

        HestonModelDescriptor realParams = (HestonModelDescriptor) result.getModel().getModelDescriptor();

        System.out.println("   RMSE Calibrazione: " + df.format(result.getRootMeanSquaredError()));
        System.out.printf("   Parametri: Sigma=%.4f, Theta=%.4f, Kappa=%.4f, Xi=%.4f, Rho=%.4f%n",
                realParams.getVolatility(), realParams.getTheta(), realParams.getKappa(), realParams.getXi(), realParams.getRho());


        // =======================================================================
        // STEP 2: SETUP AMBIENTE DI PRICING (Mondo Laboratorio: S=100, r=0)
        // =======================================================================
        System.out.println("\n2. Configurazione ambiente di pricing (S=100, r=0)...");

        double initialSpot = 100;
        double riskFreeRate = 0.0;
        double maturity = 30 / 252.0; // ~1 mese

        // CALCOLO VARIABILI FINANZIARIE
        double forwardPrice = initialSpot * Math.exp(riskFreeRate * maturity);
        double discountFactor = Math.exp(-riskFreeRate * maturity);


        // --- C. Creazione Modello Heston ---
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
        // STEP 3: METODO A - FORMULA ANALITICA
        // =======================================================================
        // Formula (5): K* = Theta + (V0 - Theta) * (1-e^-kT)/(kT)

        double v0 = realParams.getVolatility() * realParams.getVolatility();
        double theta = realParams.getTheta();
        double kappa = realParams.getKappa();

        double Factor = (1 - Math.exp(-kappa * maturity)) / (kappa * maturity);

        double fairVarSwapRate = theta + (v0 - theta) * Factor;


        // =======================================================================
        // STEP 4: METODO B - REPLICA STATICA (APPROCCIO GENERICO)
        // =======================================================================

        // 1. Definiamo "CHI" è il nostro Payoff Generico
        // Per il Variance Swap, la derivata seconda g''(K) è 1 / K^2
        DoubleUnaryOperator varianceSwapSecondDerivative = k -> 1.0 / (k * k);

        // 2. Istanziamo il nostro Integratore Generico
        CarrMadanGenericIntegrator replicator = new CarrMadanGenericIntegrator(
                pricingModel,
                maturity,
                forwardPrice,
                discountFactor,
                300 // numero punti
        );

        // 3. Calcoliamo il valore dell'integrale (Somma Pesata Opzioni)
        double integralValue = replicator.calculateIntegralValue(varianceSwapSecondDerivative);

        // 4. Scaling finale specifico per il Variance Swap Rate
        // K_var = (2 / (T * DF)) * Integrale
        double varianceSwapRateReplication = (2.0 / (maturity * discountFactor)) * integralValue;


        // =======================================================================
        // RISULTATI
        // =======================================================================
        System.out.println("\n--- RISULTATI CONFRONTO ---");
        System.out.println("Maturity:  " + df.format(maturity));
        System.out.println("Analitico: " + df.format(fairVarSwapRate));
        System.out.println("Replica:   " + df.format(varianceSwapRateReplication));

        double diff = Math.abs(fairVarSwapRate - varianceSwapRateReplication);
        System.out.println("Differenza:" + df.format(diff));

        if(diff < 1E-4) {
            System.out.println(">> SUCCESS: Risultati coerenti.");
        } else {
            System.out.println(">> WARNING: Controllare i parametri.");
        }
    }
}
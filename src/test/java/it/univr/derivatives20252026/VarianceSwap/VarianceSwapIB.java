package it.univr.derivatives20252026.VarianceSwap;

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
        // CONFIGURAZIONE
        // =======================================================================
        String symbol   = "SPX";
        String secType  = "IND";
        String exchange = "CBOE";
        String currency = "USD";

        // Zero rates della curva SOFR/Treasury per le 7 scadenze target:
        // 30gg, 60gg, 90gg, 180gg, 1y, 1.5y, 2y
        // Aggiornare con i valori di mercato correnti (es. da Bloomberg, FRED, IBKR).
//https://home.treasury.gov/resource-center/data-chart-center/interest-rates/TextView?type=daily_treasury_yield_curve&field_tdr_date_value=202603
        double[] zeroRates = {
                0.0373,  // 30gg  — 1 mese
                0.0372,  // 60gg  — 2 mesi (interpolato)
                0.0373,  // 90gg  — 3 mesi
                0.0376,  // 180gg — 6 mesi
                0.0373,  // 365gg — 1 anno
                0.0376,  // 548gg — interpolato tra 1y e 2y
                0.0379   // 730gg — 2 anni
        };

        // Tasso usato per la superficie — passato a buildFinmathSurface
        // Non usato nel pricing del variance swap (r=0 per semplicità)
        // double riskFreeRate30d = zeroRates[0];

        // =======================================================================
        // STEP 1: SCARICO SUPERFICIE LIVE DA IBKR
        // =======================================================================
        System.out.println("\n1. Collegamento a IBKR e scaricamento superficie live...");

        OptionSurfaceData marketData = IBKRMarketDataProvider.getLiveVolatilitySurface(
                symbol, secType, exchange, currency, zeroRates);

        if (marketData == null)
            throw new RuntimeException("Impossibile recuperare dati live da TWS. Assicurati che sia aperta!");

        // =======================================================================
        // STEP 2: CALIBRAZIONE HESTON
        // =======================================================================
        System.out.println("\n2. Avvio calibrazione Heston sui dati reali...");

        double[] guess = {
                0.20,  // sigma  — vol iniziale
                0.04,  // theta  — varianza long-run
                2.0,   // kappa  — velocità mean reversion
                0.5,   // xi     — vol-of-vol
                -0.7    // rho    — correlazione
        };

        HestonCalibrationClass calibrator = new HestonCalibrationClass();
        OptimizationResult result = calibrator.calibrate(LocalDate.now(), marketData, guess);

        HestonModelDescriptor p = (HestonModelDescriptor) result.getModel().getModelDescriptor();

        System.out.println("   Calibration RMSE: " + result.getRootMeanSquaredError());
        System.out.printf("   Parametri: Sigma=%.4f, Theta=%.4f, Kappa=%.4f, Xi=%.4f, Rho=%.4f%n",
                p.getVolatility(), p.getTheta(), p.getKappa(), p.getXi(), p.getRho());

        // =======================================================================
        // STEP 3: SETUP AMBIENTE DI PRICING
        // r=0, q=0 — "fake world" identico alla classe VarianceSwap su dati storici.
        // Giustificazione: a T=30gg il discount factor con r=5% vale ~0.9957,
        // impatto sul fair strike < 0.5bp — trascurabile rispetto all'errore del modello.
        // Con r=0: forward=spot=100, DF=1 → formula di replica si semplifica.
        // =======================================================================
        System.out.println("\n3. Configurazione ambiente di pricing (Spot=100, r=0)...");

        double[] availableMaturities = marketData.getMaturities();
        double maturity = availableMaturities[0]; // prima scadenza reale (~30gg)
        System.out.printf("   Scadenza reale: %.4f anni (%.0f giorni)%n", maturity, maturity * 365);

        double initialSpot    = 100.0;
        double riskFreeRate   = 0.0;   // r=0 per semplicità — vedi commento sopra
        // Financial Variables Calculation
        double forwardPrice = initialSpot * Math.exp(riskFreeRate * maturity);
        double discountFactor = Math.exp(-riskFreeRate * maturity);

        HestonModel pricingModel = new HestonModel(
                initialSpot,
                riskFreeRate,
                p.getVolatility(),
                p.getTheta(),
                p.getKappa(),
                p.getXi(),
                p.getRho()
        );

        // =======================================================================
        // STEP 4: METODO A — FORMULA ANALITICA HESTON
        // E^Q[∫₀ᵀ v_t dt] = θ·T + (v₀ - θ)·(1 - e^{-κT}) / κ
        // =======================================================================
        double v0     = p.getVolatility() * p.getVolatility();
        double theta  = p.getTheta();
        double kappa  = p.getKappa();

        double meanReversionFactor = (1.0 - Math.exp(-kappa * maturity)) / (kappa * maturity);
        double fairVarSwapRate     = theta + (v0 - theta) * meanReversionFactor;

        // =======================================================================
        // STEP 5: METODO B — REPLICA STATICA (Carr-Madan)
        // K_var = (2/T) ∫ [C(K)/K² + P(K)/K²] dK
        // =======================================================================
        DoubleUnaryOperator kernel = k -> 1.0 / (k * k);

        CarrMadanGenericIntegrator replicator = new CarrMadanGenericIntegrator(
                pricingModel, maturity, forwardPrice, discountFactor, 1000);

        double integralValue           = replicator.calculateIntegralValue(kernel);
        double varianceSwapReplication = (2.0 / (maturity * discountFactor)) * integralValue;

        // =======================================================================
        // RISULTATI
        // =======================================================================
        System.out.println("\n--- RISULTATI VARIANCE SWAP LIVE (" + symbol + ") ---");
        System.out.printf("Maturity:        %.4f anni (%.0f giorni)%n", maturity, maturity * 365);
        System.out.printf("Analitico:       %.6f%n", fairVarSwapRate);
        System.out.printf("Replicazione:    %.6f%n", varianceSwapReplication);
        System.out.printf("Differenza:      %.8f%n", Math.abs(fairVarSwapRate - varianceSwapReplication));

        if (Math.abs(fairVarSwapRate - varianceSwapReplication) < 1e-3)
            System.out.println(">> SUCCESSO: prezzi consistenti tra i due metodi.");
        else
            System.out.println(">> NOTA: divergenza dovuta alla curvatura dello skew live.");

        System.out.printf("%nFair strike (vol): %.2f%%%n", Math.sqrt(fairVarSwapRate) * 100);
    }
}

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

/**
 * <p><strong>Exercise 2: Variance Swap Pricing (Analytical vs. Static Replication).</strong></p>
 * <p>
 * This class compares two different methodologies for pricing a <b>Variance Swap</b> under the Heston Model.
 * A Variance Swap is a forward contract on the future realized variance of the underlying asset.
 * </p>
 * <p>
 * The two approaches implemented are:
 * <ol>
 * <li>
 * <b>Method A (Analytical):</b> Uses the closed-form solution for the expected average variance in the Heston model.
 * <br>Formula: \( E[V]_{0,T} = \theta + (V_0 - \theta) \frac{1 - e^{-\kappa T}}{\kappa T} \)
 * </li>
 * <li>
 * <b>Method B (Static Replication):</b> Uses the Carr-Madan formula to replicate the payoff using a portfolio of OTM vanilla options.
 * <br>Concept: Any twice-differentiable payoff \( g(S_T) \) can be replicated by a continuum of options.
 * <br>For Variance Swaps, the payoff function is related to \( \ln(S_T) \), implying a weighting function \( g''(K) = \frac{2}{K^2} \).
 * </li>
 * </ol>
 * </p>
 * <p>
 * The goal is to verify that the "Model-Free" replication price converges to the theoretical model price
 * when the options are priced using the model itself (Consistency Check).
 * </p>
 *
 * @author Federico Alberighi
 * @author Alice Bonizzoni
 * @version 1.1
 */
public class Exercise2 {

    /**
     * Main execution method for the Variance Swap Pricing exercise.
     * <p>The workflow is as follows:</p>
     * <ol>
     * <li><b>Calibration:</b> Calibrates the Heston model on real market data (Jan 2010) to get realistic parameters.</li>
     * <li><b>Environment Setup:</b> Sets up a "Laboratory" pricing environment (Standard Spot=100, Rates=0) to isolate variance effects.</li>
     * <li><b>Analytical Pricing:</b> Calculates the fair variance swap rate using the Heston specific formula.</li>
     * <li><b>Replication Pricing:</b> Calculates the fair rate by integrating weighted OTM option prices (Carr-Madan).</li>
     * <li><b>Comparison:</b> Checks if the difference between the two methods is negligible.</li>
     * </ol>
     *
     * @param args Command line arguments (not used).
     * @throws Exception If errors occur during calibration or pricing.
     */
    public static void main(String[] args) throws Exception {

        System.out.println("--- EXERCISE 2: Variance Swap Pricing (Heston vs Replication) ---");
        DecimalFormat df = new DecimalFormat("0.00000000");

        // =======================================================================
        // STEP 1: CALIBRATION (Real World Data)
        // =======================================================================
        System.out.println("\n1. Calibration on real market data...");

        LocalDate calibrationDate = LocalDate.of(2010, 01, 07);
        OptionSurfaceData marketData = MarketDataProvider.getVolatilityDataContainer().get(calibrationDate);

        if(marketData == null) {
            throw new RuntimeException("Data not found for date: " + calibrationDate);
        }

        // Initial guess for the optimizer {sigma, theta, kappa, xi, rho}
        double[] guess = new double[] {
                0.0323, // volatility (sqrt(V0))
                0.0818, // theta
                1.0,    // kappa
                0.4639, // xi (nu)
                -0.4    // rho
        };

        HestonCalibrationClass calibrator = new HestonCalibrationClass();
        OptimizationResult result = calibrator.calibrate(calibrationDate, marketData, guess);

        HestonModelDescriptor realParams = (HestonModelDescriptor) result.getModel().getModelDescriptor();

        System.out.println("   Calibration RMSE: " + df.format(result.getRootMeanSquaredError()));
        System.out.printf("   Parameters: Sigma=%.4f, Theta=%.4f, Kappa=%.4f, Xi=%.4f, Rho=%.4f%n",
                realParams.getVolatility(), realParams.getTheta(), realParams.getKappa(), realParams.getXi(), realParams.getRho());


        // =======================================================================
        // STEP 2: PRICING ENVIRONMENT SETUP (Laboratory World: S=100, r=0)
        // We use r=0 and q=0 to simplify the variance swap valuation.
        // =======================================================================
        System.out.println("\n2. Pricing environment configuration (S=100, r=0)...");

        double initialSpot = 100;
        double riskFreeRate = 0.0;
        double maturity = 30 / 252.0; // ~1 month (30 trading days)

        // Financial Variables Calculation
        double forwardPrice = initialSpot * Math.exp(riskFreeRate * maturity);
        double discountFactor = Math.exp(-riskFreeRate * maturity);


        // --- Create Heston Model for Pricing ---
        // We inject the parameters calibrated from Step 1 into this clean environment.
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
        // Formula: K_var = Theta + (V0 - Theta) * (1 - exp(-k*T)) / (k*T)
        // =======================================================================

        double v0 = realParams.getVolatility() * realParams.getVolatility();
        double theta = realParams.getTheta();
        double kappa = realParams.getKappa();

        // Mean Reversion Factor
        double Factor = (1 - Math.exp(-kappa * maturity)) / (kappa * maturity);

        double fairVarSwapRate = theta + (v0 - theta) * Factor;


        // =======================================================================
        // STEP 4: METHOD B - STATIC REPLICATION (GENERIC APPROACH)
        // Using Carr-Madan Formula for any payoff g(S)
        // =======================================================================

        // 1. Define the Payoff Convexity g''(K)
        // For a Variance Swap (log contract), the second derivative is 1 / K^2
        DoubleUnaryOperator varianceSwapSecondDerivative = k -> 1.0 / (k * k);

        // 2. Instantiate the Generic Integrator
        // This utility integrates weighted OTM Calls and Puts
        CarrMadanGenericIntegrator replicator = new CarrMadanGenericIntegrator(
                pricingModel,
                maturity,
                forwardPrice,
                discountFactor,
                1000 // Number of integration points (accuracy)
        );

        // 3. Compute the Integral Value (Sum of weighted options)
        double integralValue = replicator.calculateIntegralValue(varianceSwapSecondDerivative);

        // 4. Final Scaling for Variance Swap Rate
        // K_var = (2 / (T * DF)) * Integral
        // The factor 2 comes from the Taylor expansion of log(S/K).
        double varianceSwapRateReplication = (2.0 / (maturity * discountFactor)) * integralValue;


        // =======================================================================
        // RESULTS COMPARISON
        // =======================================================================
        System.out.println("\n--- COMPARISON RESULTS ---");
        System.out.println("Maturity:   " + df.format(maturity));
        System.out.println("Analytical: " + df.format(fairVarSwapRate));
        System.out.println("Replication:" + df.format(varianceSwapRateReplication));

        double diff = Math.abs(fairVarSwapRate - varianceSwapRateReplication);
        System.out.println("Difference: " + df.format(diff));

        if(diff < 1E-4) {
            System.out.println(">> SUCCESS: Results are consistent.");
        } else {
            System.out.println(">> WARNING: Check parameters or integration bounds.");
        }
    }
}
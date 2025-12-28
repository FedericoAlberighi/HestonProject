package it.univr.derivatives.utils;

import java.util.function.DoubleUnaryOperator;

import net.finmath.exception.CalculationException;
import net.finmath.fouriermethod.models.HestonModel;
import net.finmath.fouriermethod.products.EuropeanOption;
import net.finmath.integration.TrapezoidalRealIntegrator;

/**
 * Implementation of the <b>Carr-Madan Static Replication</b> formula for generic payoffs.
 * <p>
 * This utility class allows pricing any derivative with a twice-differentiable payoff function \( g(S_T) \)
 * by replicating it using a portfolio of Vanilla Options (Calls and Puts).
 * </p>
 * <p>
 * Where:
 * <ul>
 * <li>\( F \): Forward price of the underlying asset.</li>
 * <li>\( P(K) \): Price of a Put option with strike \( K \).</li>
 * <li>\( C(K) \): Price of a Call option with strike \( K \).</li>
 * <li>\( g''(K) \): The second derivative of the payoff function (the "weight" of the options).</li>
 * </ul>
 * </p>
 *
 * @author Federico Alberighi
 * @author Alice Bonizzoni
 * @version 1.1
 */
public class CarrMadanGenericIntegrator {

    /** The pricing model used to value the vanilla options (e.g., Heston Model). */
    private final HestonModel model;

    /** The time to maturity \( T \) of the derivative. */
    private final double maturity;

    /** The forward price \( F(0, T) \) of the underlying asset. */
    private final double forwardPrice;

    /** The discount factor \( DF(0, T) \) for the corresponding maturity. */
    private final double discountFactor;

    /** The number of integration points used for the numerical quadrature (Trapezoidal rule). */
    private final int numberOfPoints;

    /**
     * Constructor for the static replication engine.
     *
     * @param model
     * The financial model used to calculate the prices of the vanilla options (Calls/Puts)
     * required for the integral.
     * @param maturity
     * The maturity date \( T \) (in years) of the claim.
     * @param forwardPrice
     * The Forward price \( F \) used as the split point for the integral (Puts for \( K < F \), Calls for \( K > F \)).
     * @param discountFactor
     * The discount factor to present value the cash flows.
     * @param numberOfPoints
     * Accuracy of the numerical integration (e.g., 500 or 1000).
     */
    public CarrMadanGenericIntegrator(HestonModel model, double maturity, double forwardPrice, double discountFactor, int numberOfPoints) {
        this.model = model;
        this.maturity = maturity;
        this.forwardPrice = forwardPrice;
        this.discountFactor = discountFactor;
        this.numberOfPoints = numberOfPoints;
    }

    /**
     * Calculates the value of the convex part of the payoff (the integral of OTM options).
     * <p>
     * This method performs the numerical integration of the weighted option prices.
     * It splits the domain into two parts:
     * <ul>
     * <li><b>Put Integral:</b> From near-zero to Forward \( F \).</li>
     * <li><b>Call Integral:</b> From Forward \( F \) to a sufficiently high boundary.</li>
     * </ul>
     * </p>
     *
     * @param payoffSecondDerivative
     * A {@link DoubleUnaryOperator} representing \( g''(K) \).
     * <br>Examples:
     * <ul>
     * <li>Variance Swap: \( K \to \frac{2}{K^2} \) (or \( 1/K^2 \) depending on convention)</li>
     * <li>Gamma Swap: \( K \to \frac{1}{K} \)</li>
     * <li>Entropy Swap: \( K \to \frac{1}{K \ln(K)} \)</li>
     * </ul>
     *
     * @return The Present Value (PV) of the replication leg.
     */
    public double calculateIntegralValue(DoubleUnaryOperator payoffSecondDerivative) {

        // --------------------------------------------------------------------
        // 1. DEFINE CALL INTEGRAND (for Strike K > Forward)
        // Integrand = g''(K) * PriceCall(K)
        // --------------------------------------------------------------------
        DoubleUnaryOperator integrandCall = strike -> {
            try {
                if (strike <= 0) return 0.0;

                // Model Price for Call Option
                double callPrice = new EuropeanOption(maturity, strike).getValue(model);

                // Weight given by the payoff convexity g''(K)
                double weight = payoffSecondDerivative.applyAsDouble(strike);

                return weight * callPrice;
            } catch (CalculationException e) {
                return 0.0;
            }
        };

        // --------------------------------------------------------------------
        // 2. DEFINE PUT INTEGRAND (for Strike K < Forward)
        // Integrand = g''(K) * PricePut(K)
        // Note: We use Put-Call Parity to compute Put prices if the model only provides Calls,
        // or directly if supported. Here: Put = Call - (F - K)*DF
        // --------------------------------------------------------------------
        DoubleUnaryOperator integrandPut = strike -> {
            try {
                // Safety cut-off to avoid singularities at K=0 (e.g. 1/K^2)
                if (strike <= forwardPrice * 0.001) return 0.0;

                double callPrice = new EuropeanOption(maturity, strike).getValue(model);

                // Synthetic Put via Parity: P = C + (K - F) * DF
                // (Since Finmath EuropeanOption might default to Call value, we adjust manually)
                double putPrice = callPrice + (strike - forwardPrice) * discountFactor;

                // Weight given by the payoff convexity g''(K)
                double weight = payoffSecondDerivative.applyAsDouble(strike);

                return weight * putPrice;
            } catch (CalculationException e) {
                return 0.0;
            }
        };

        // --------------------------------------------------------------------
        // 3. EXECUTE INTEGRATION (Numerical Quadrature)
        // --------------------------------------------------------------------

        // Put Integral: From 1% of Forward up to Forward
        TrapezoidalRealIntegrator putsIntegrator = new TrapezoidalRealIntegrator(
                forwardPrice * 0.01,
                forwardPrice,
                numberOfPoints
        );

        // Call Integral: From just above Forward up to 400% of Forward (Deep OTM)
        TrapezoidalRealIntegrator callsIntegrator = new TrapezoidalRealIntegrator(
                forwardPrice * 1.0001, // Start slightly above F to avoid overlap/singularity
                forwardPrice * 4.0,
                numberOfPoints
        );

        double sumPuts = putsIntegrator.integrate(integrandPut);
        double sumCalls = callsIntegrator.integrate(integrandCall);

        return sumPuts + sumCalls;
    }
}
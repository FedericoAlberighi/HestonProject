package it.univr.derivatives.utils;

import java.util.function.DoubleUnaryOperator;

import net.finmath.exception.CalculationException;
import net.finmath.fouriermethod.models.HestonModel;
import net.finmath.fouriermethod.products.EuropeanOption;
import net.finmath.integration.TrapezoidalRealIntegrator;

/**
 * Implementazione della formula di replica statica di Carr-Madan per payoff generici.
 * * La formula generale è:
 * Value = Integral_{0}^{F} g''(K) * Put(K) dK  +  Integral_{F}^{Infinity} g''(K) * Call(K) dK
 */
public class CarrMadanGenericIntegrator {

    private final HestonModel model;
    private final double maturity;
    private final double forwardPrice;
    private final double discountFactor;
    private final int numberOfPoints;

    /**
     * Costruttore del motore di replica.
     * * @param model: Il modello di pricing (es. Heston)
     * @param maturity: La scadenza T
     * @param forwardPrice: Il prezzo Forward F(0,T)
     * @param discountFactor: Il fattore di sconto DF(0,T)
     * @param numberOfPoints: Numero di punti per l'integrale (es. 300)
     */
    public CarrMadanGenericIntegrator(HestonModel model, double maturity, double forwardPrice, double discountFactor, int numberOfPoints) {
        this.model = model;
        this.maturity = maturity;
        this.forwardPrice = forwardPrice;
        this.discountFactor = discountFactor;
        this.numberOfPoints = numberOfPoints;
    }

    /**
     * Calcola il valore della parte convessa del payoff (l'integrale delle opzioni).
     * * @param payoffSecondDerivative (DoubleUnaryOperator): La funzione g''(K).
     * Per Variance Swap: K -> 1/(K^2)
     * Per Gamma Swap:    K -> 1/K
     * Per Entropy Swap:  K -> 1/(K*ln(K))
     * @return Il valore presente (PV) della gamba di replica opzionale.
     */
    public double calculateIntegralValue(DoubleUnaryOperator payoffSecondDerivative) {

        // --------------------------------------------------------------------
        // 1. DEFINIZIONE INTEGRANDO CALL (Strike K > Forward)
        // Integrando = g''(K) * PriceCall(K)
        // --------------------------------------------------------------------
        DoubleUnaryOperator integrandCall = strike -> {
            try {
                if (strike <= 0) return 0.0;

                // Prezzo Call dal Modello
                double callPrice = new EuropeanOption(maturity, strike).getValue(model);

                // Peso Generico g''(K)
                double weight = payoffSecondDerivative.applyAsDouble(strike);

                return weight * callPrice;
            } catch (CalculationException e) {
                return 0.0;
            }
        };

        // --------------------------------------------------------------------
        // 2. DEFINIZIONE INTEGRANDO PUT (Strike K < Forward)
        // Integrando = g''(K) * PricePut(K)
        // (Usiamo Put-Call Parity: Put = Call + (K - F) * DF)
        // --------------------------------------------------------------------
        DoubleUnaryOperator integrandPut = strike -> {
            try {
                if (strike <= forwardPrice * 0.001) return 0.0; // Cut-off sicurezza

                double callPrice = new EuropeanOption(maturity, strike).getValue(model);

                // Put sintetica tramite Parity
                double putPrice = callPrice + (strike - forwardPrice) * discountFactor;

                // Peso Generico g''(K)
                double weight = payoffSecondDerivative.applyAsDouble(strike);

                return weight * putPrice;
            } catch (CalculationException e) {
                return 0.0;
            }
        };

        // --------------------------------------------------------------------
        // 3. ESECUZIONE INTEGRAZIONE
        // --------------------------------------------------------------------

        // Integrale Put: da quasi zero (1% Forward) fino al Forward
        TrapezoidalRealIntegrator putsIntegrator = new TrapezoidalRealIntegrator(
                forwardPrice * 0.01,
                forwardPrice,
                numberOfPoints
        );

        // Integrale Call: dal Forward fino a molto OTM (400% Forward)
        TrapezoidalRealIntegrator callsIntegrator = new TrapezoidalRealIntegrator(
                forwardPrice * 1.01, // Partiamo appena sopra il forward
                forwardPrice * 4.0,
                numberOfPoints
        );

        double sumPuts = putsIntegrator.integrate(integrandPut);
        double sumCalls = callsIntegrator.integrate(integrandCall);

        return sumPuts + sumCalls;
    }
}
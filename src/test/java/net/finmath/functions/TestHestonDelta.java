package net.finmath.functions;

public class TestHestonDelta {
	
	
	public static void main(String[] args) {
		
		
		/*
		 * Consiglio: per garantire la stabilità numerica del calcolo del delta
		 * riscalare sempre in percentuale di S0, ossia  S0 -> 100
		 * e per lo strike riscalare strike a -> strike / S0 * 100
		 */
		
		double daxOggi = 5449.98;
		double strike = daxOggi * 0.6;
		
		
		
		final double initialStockValue = 100;
		final double riskFreeRate = 0.026010000000001234;
		final double dividendYield = 0.0;
		final double sigma = 0.14125143765947648;
		final double theta = 0.045965798856851606; 
		final double kappa = 1.9970298101347934;
		final double xi = 0.6400934607328624;
		final double rho = -0.49656048161582766;
		final double optionMaturity = 0.0027397260273972603;
		final double optionStrike = strike/daxOggi * 100;
		 
		double delta = HestonModel.hestonOptionDelta(
				initialStockValue,
				riskFreeRate,
				dividendYield,
				sigma, 
				theta, 
				kappa, 
				xi, 
				rho,
				optionMaturity,
				optionStrike);
		
		System.out.println(delta);	

	}

}

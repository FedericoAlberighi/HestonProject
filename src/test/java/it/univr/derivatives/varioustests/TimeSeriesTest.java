package it.univr.derivatives.varioustests;

import it.univr.derivatives.marketdataprovider.MarketDataProvider;
import it.univr.derivatives.utils.TimeSeries;

public class TimeSeriesTest {

	public static void main(String[] args) throws Exception {
		
		TimeSeries daxTimeSeries = MarketDataProvider.getDaxData();
		
		daxTimeSeries.plot("Dax Index");
		
		
		TimeSeries logReturns = daxTimeSeries.computeLogReturns();
		
		logReturns.plot("Dax Returns");
	}

}

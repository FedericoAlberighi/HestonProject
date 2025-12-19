package it.univr.derivatives.varioustests;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.TreeMap;

import it.univr.derivatives.marketdataprovider.MarketDataProvider;
import net.finmath.marketdata.model.volatilities.OptionSurfaceData;

public class MainAppTest {

	public static void main(String[] args) {
		
		TreeMap<LocalDate, OptionSurfaceData>  myDatabase = MarketDataProvider.getVolatilityDataContainer();
		
		
		LocalDate today = LocalDate.of(2006, 1, 2);
		
		OptionSurfaceData surfDataForToday = myDatabase.get(today);
		
		
		System.out.println(surfDataForToday.getSurface().toString());

	}

}

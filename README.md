# HestonProject

> **Heston Stochastic Volatility Model — Calibration, Variance Swap Pricing and Live Market Data Integration**
>
> Università degli Studi di Verona, A.Y. 2025/2026 — *Derivatives

---

## Overview

This project is structured in **two parts** with a clear boundary between them.

**Part 1 — University assignment** (co-developed with Alice Bonizzoni as part of the course): implementation of the Heston stochastic volatility model in Java, with two exercises:
- **Exercise 1:** study of parameter stability through daily rolling recalibration of the Heston model over a multi-year historical period (DAX, 2006–2015, Bloomberg data)
- **Exercise 2:** pricing of a Variance Swap under Heston, comparing the closed-form analytical formula against the Carr-Madan static replication approach

**Part 2 — Personal extension** (developed independently by Federico Alberighi): integration of the Interactive Brokers TWS API to replace the static Excel dataset with a live implied volatility surface built in real time from SPX options quoted on CBOE. Exercise 2 (Variance Swap pricing) is then re-run end-to-end on live market data.

Both parts share the same calibration and pricing engine — `HestonCalibrationClass` and `CarrMadanGenericIntegrator` — and produce the same `OptionSurfaceData` object as input to the Heston optimizer, making the historical and live workflows directly comparable.

---

## The Heston Model

The asset price and its variance follow the coupled SDEs:

$$dS(t) = r \, S(t) \, dt + \sqrt{V(t)} \, S(t) \, dW_1(t)

$$dV(t) = \kappa \left(\theta - V(t)\right) dt + \xi \sqrt{V(t)} \, dW_2(t)

$$dW_1 \, dW_2 = \rho \, dt$$

| Symbol | Name | Description |
|---|---|---|
| $\sigma$ | Initial volatility $(\sqrt{V_0})$ | Square root of instantaneous variance at $t=0$ |
| $\theta$ | Long-run variance | Level to which variance mean-reverts |
| $\kappa$ | Mean-reversion speed | How fast variance returns to $\theta$ |
| $\xi$ | Volatility of volatility | Standard deviation of the variance process |
| $\rho$ | Brownian correlation | Negative for equity (leverage effect) |

---

## Project Structure

```
HestonProject/
├── src/
│   ├── main/java/
│   │   ├── net/finmath/functions/
│   │   │   └── HestonModel.java                  # Heston option pricing via Carr-Madan FFT
│   │   ├── ibkr/
│   │   │   └── IBWrapperImpl.java                # EWrapper base class (~100 callback stubs)
│   │   └── it/univr/derivatives/
│   │       ├── marketdataprovider/
│   │       │   ├── MarketDataProvider.java        # Part 1: historical DAX surface from Excel
│   │       │   ├── IBKRMarketDataProvider.java    # Part 2: live surface facade (public API)
│   │       │   ├── IBKRConnection.java            # Part 2: TWS TCP connection manager
│   │       │   ├── SpotPriceFetcher.java          # Part 2: spot price (2-phase strategy)
│   │       │   ├── OptionChainFetcher.java        # Part 2: option chain + 7x7 grid matching
│   │       │   ├── VolatilityDownloader.java      # Part 2: implied vol download (reqMktData)
│   │       │   ├── VolSurfacePatcher.java         # Part 2: NaN patching + quality filter
│   │       │   └── FinmathSurfaceBuilder.java     # Part 2: discount/forward curves + surface
│   │       └── utils/
│   │           ├── HestonCalibrationClass.java    # Shared: Levenberg-Marquardt calibration
│   │           ├── CarrMadanGenericIntegrator.java # Shared: static replication integrator
│   │           └── TimeSeries.java                # Shared: time series container + plot
│   │
│   └── test/java/
│       ├── it/univr/derivatives/varioustests/
│       │   ├── TestSpotIBKR.java                  # Part 2 test: live spot + historical series
│       │   └── TestSuperficieIBKR.java            # Part 2 test: full live surface construction
│       └── it/univr/derivatives20252026/
│           ├── VarianceSwap/
│           │   ├── VarianceSwap.java              # Part 1 – Exercise 2: Variance Swap (historical)
│           │   └── VarianceSwapIB.java            # Part 2 – Exercise 2: Variance Swap (live IBKR)
│           └── Recalibration/
│               └── HistoricalRecalibration.java   # Part 1 – Exercise 1: rolling calibration (DAX)
│
└── src/test/resources/
    └── DAX Bloomi.xls                            # Historical DAX surface (Bloomberg)
```

---

## Part 1 — University Assignment

### Exercise 1: Parameter Stability (HistoricalRecalibration)

The Heston model is recalibrated every trading day on the full DAX implied volatility surface from 2006 to 2015. The goal is to observe how the five parameters ($\sigma$, $\theta$, $\kappa$, $\xi$, $\rho$) evolve and drift over time in response to changing market conditions — a study of **model risk** and **parameter instability**.

A **bootstrapping strategy** is used: the calibrated parameters at day $t$ become the initial guess for day $t+1$. This exploits parameter continuity across consecutive trading days, reduces optimisation time, and prevents the optimizer from landing in different local minima on adjacent days.

The market data file (`DAX Bloomi.xls`) is a Bloomberg export. Each row is one trading day and contains: reference date, DAX spot level, a 7×7 implied volatility grid (7 maturities × 7 strike scalings), and 7 zero rates from the Euribor curve.

### Exercise 2: Variance Swap Pricing (VarianceSwap)

A Variance Swap is a forward contract on the future realised variance of the underlying asset. Under Heston, two pricing approaches are compared:

**Method A — Analytical formula:**
$$E[V] = \theta + (v_0 - \theta) \cdot \frac{1 - e^{-\kappa T}}{\kappa T}$$

**Method B — Carr-Madan static replication.

The two methods must converge when the model is calibrated consistently on the surface used for pricing. A significant divergence indicates model mis-specification or surface quality issues.

The calibration is performed on the DAX surface at a specific historical date (January 2010), and pricing is run in a normalised environment (spot = 100, r = 0) to isolate the pure variance effect and make the two methods directly comparable.

---

## Part 2 — Personal Extension: Live IBKR Integration

### Motivation

The university assignment relied on a static Bloomberg Excel file as market data. The goal of Part 2 was to replace this entirely with **live market data** pulled in real time from Interactive Brokers, making the full pipeline — surface construction, Heston calibration, Variance Swap pricing — executable on any trading day against actual current market prices.

### Architecture

The live data pipeline is built as a layered system. `IBKRMarketDataProvider` is the only public-facing class; all other components are package-private and invisible to the rest of the project.

```
IBKRMarketDataProvider  (public facade — the only entry point)
       │
       ├── [PARALLEL] SpotPriceFetcher      → reqHistoricalData, clientId 999/995
       │
       └── [PARALLEL] OptionChainFetcher    → reqContractDetails, clientId 998
                              │
                              ▼  7×7 grid: Call ≥ ATM forward, Put < ATM forward
                       VolatilityDownloader  → reqMktData tick "106", clientId 997
                                               delayed data type 3, tickerId = i*100+j
                              │
                              ▼
                       VolSurfacePatcher     → linear interp / flat extrap / cross-maturity
                              │
                              ▼
                       FinmathSurfaceBuilder → DiscountCurve + ForwardCurve + OptionSurfaceData
```

Spot retrieval and option chain download run **in parallel** (`CompletableFuture.supplyAsync`) because they open independent TWS connections and neither depends on the other's result.

### Target volatility surface grid

The pipeline always builds the same 7×7 structure as the historical Bloomberg surface, so that `HestonCalibrationClass` receives input in an identical format regardless of the data source.

| | 90% | 95% | 97.5% | ATM | 102.5% | 105% | 110% |
|---|---|---|---|---|---|---|---|
| 30d | Put | Put | Put | Call | Call | Call | Call |
| 60d | Put | Put | Put | Call | Call | Call | Call |
| 90d | Put | Put | Put | Call | Call | Call | Call |
| 180d | Put | Put | Put | Call | Call | Call | Call |
| 1Y | Put | Put | Put | Call | Call | Call | Call |
| 1.5Y | Put | Put | Put | Call | Call | Call | Call |
| 2Y | Put | Put | Put | Call | Call | Call | Call |

### Exercise 2 live (VarianceSwapIB)

`VarianceSwapIB` runs the same Variance Swap pricing as the university exercise, but sourcing the implied volatility surface live from IBKR instead of the historical Excel file. It also uses current US Treasury zero rates (from treasury.gov) instead of historical Euribor rates, making the full pricing pipeline realistic for the current market environment.

---

## Dependencies

Managed via Maven (`pom.xml`). Java 17 required.

| Library | Version | Purpose |
|---|---|---|
| `net.finmath:finmath-lib` | 6.0.15 | Heston model, calibration, curve interpolation, FFT pricing |
| `net.finmath:finmath-lib-plot-extensions` | 0.5.6 | TimeSeries plotting |
| `org.apache.poi:poi` | 5.2.5 | Reading historical DAX surface from `.xls` |
| `org.apache.poi:poi-ooxml` | 5.2.5 | Excel support |
| `com.google.protobuf:protobuf-java` | 4.29.3 | Required by IBKR TWS API |
| IBKR TWS API | bundled | Interactive Brokers Java client (`com.ib.client`) |
| `org.junit.jupiter` | 5.11.0 | Unit testing |

> **IBKR API note:** `com.ib.client` and `com.ib.controller` are not on Maven Central. They are the official IB Java API bundled directly in the project. Source: [interactivebrokers.github.io](https://interactivebrokers.github.io).

---

## Quickstart

### Prerequisites

- Java 17+
- Maven 3.8+
- **Part 2 only:** Interactive Brokers TWS open and logged in — see [TWS Configuration](#tws-configuration)

### Build

```bash
git clone https://github.com/<your-username>/HestonProject.git
cd HestonProject
mvn clean compile
```

### Part 1 — Historical exercises

**Exercise 1 — Rolling calibration on DAX (2006–2015):**
```bash
mvn exec:java -Dexec.mainClass="it.univr.derivatives20252026.Recalibration.HistoricalRecalibration"
```

**Exercise 2 — Variance Swap pricing on DAX (January 2010):**
```bash
mvn exec:java -Dexec.mainClass="it.univr.derivatives20252026.VarianceSwap.VarianceSwap"
```

### Part 2 — Live IBKR exercises

> TWS must be open and configured before running any of these.

**Test: live spot price + 1-year historical series (AAPL):**
```bash
mvn exec:java -Dexec.mainClass="it.univr.derivatives.varioustests.TestSpotIBKR"
```

**Test: full live volatility surface construction (SPX):**
```bash
mvn exec:java -Dexec.mainClass="it.univr.derivatives.varioustests.TestSuperficieIBKR"
```

**Exercise 2 live — Variance Swap on SPX with live IBKR data:**
```bash
mvn exec:java -Dexec.mainClass="it.univr.derivatives20252026.VarianceSwap.VarianceSwapIB"
```

---

## TWS Configuration

Required only for Part 2. Before running any live class:

1. Open Trader Workstation and log in (paper or live account)
2. Go to **Edit → Global Configuration → API → Settings**
3. Enable **"Enable ActiveX and Socket Clients"**
4. Set port to **7497** (paper trading) or **7496** (live)
5. Add `127.0.0.1` to the **Trusted IP Addresses** list

> The live classes use client IDs 995–999. Make sure no other API client is using those IDs on the same TWS session.

## Authors

**Part 1 — University assignment**, co-developed by:

| | |
|---|---|
| **Federico Alberighi** | Università degli Studi di Verona |
| **Alice Bonizzoni** | Università degli Studi di Verona |

Classes: `HestonCalibrationClass`, `CarrMadanGenericIntegrator`, `HistoricalRecalibration`, `VarianceSwap`

**Part 2 — Live IBKR extension**, developed independently by **Federico Alberighi**:

Classes: `IBKRMarketDataProvider`, `IBKRConnection`, `SpotPriceFetcher`, `OptionChainFetcher`, `VolatilityDownloader`, `VolSurfacePatcher`, `FinmathSurfaceBuilder`, `VarianceSwapIB`

---

Model theory and finmath-lib course integration by **Prof. Alessandro Gnoatto** (Università degli Studi di Verona).

---

## References

- finmath-lib: [finmath.net](http://finmath.net)
- Interactive Brokers TWS API

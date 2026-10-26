Task 1: Core Domain Models & Serialization
├── 1.1: Core records (InstrumentId, Venue, RawMarketTick, EventDefinition, EventOutcome)
└── 1.2: Jackson serialization module and round-trip unit tests

Task 2: Canonical Event Mapping Engine
├── 2.1: EventContractRegistry interface and InMemoryEventRegistry
└── 2.2: Mapping logic and tests (map Kalshi FED-XX -> Canonical FOMCEvent)

Task 3: Feed Harness & Mock Ingestion
├── 3.1: MarketConnector interface & FeedListener callback
├── 3.2: ReplayMockConnector reading from src/test/resources/fomc_ticks.csv
└── 3.3: Integration test streaming 10,000 synthetic ticks over Virtual Threads

Task 4: Normalizer & Staleness Filter
├── 4.1: StalenessFilter and CrossedBookFilter implementations
└── 4.2: Unit tests verifying rejected/flagged anomalous ticks

Task 5: Surface Aggregation & Quant Hook
├── 5.1: MarketQuantifier<T> interface + SimpleBinaryQuantifier
├── 5.2: ImpliedProbabilitySurface accumulator
└── 5.3: End-to-end test verifying unified P(Outcome) output

Task 6: Friction & Arb Evaluator
├── 6.1: FeeSchedule + SlippageModel
└── 6.2: DiscrepancyEngine flagging cross-venue net spread > threshold
# Core Principles

Every successful platform begins with a philosophy. Obsinity’s philosophy is that **observability should preserve truth, not just compress it into numbers**. Too many systems chase efficiency at the expense of fidelity; Obsinity takes the opposite stance.

## Event-Centric, Not Metric-Centric
Most observability tools collapse everything into counters and gauges, stripping away nuance. Obsinity flips the model: **events are the source of truth**. Metrics are derived from events, never the other way around. This ensures you always have the ability to drill down from high-level KPIs to the raw facts. It’s the difference between a summary report and the full story behind it.

## PostgreSQL-Native
Why fight the database wars? PostgreSQL is proven, mature, and endlessly extensible. Obsinity embraces it as the backbone. Instead of inventing exotic storage engines, Obsinity invests in **partitioning, indexing, and SQL semantics**. This gives organizations a foundation they already trust, while ensuring observability data fits seamlessly into existing governance and compliance processes.

## OpenTelemetry-Shaped
The world is converging on OTEL as the lingua franca of telemetry. Obsinity doesn’t reinvent the wheel — it builds on that standard. By mirroring the OTEL envelope (`service.id`, spans, traces, timestamps, outcomes), Obsinity makes adoption frictionless. Familiarity breeds confidence, and interoperability is baked in from day one.

## Configurable Retention & Masking
Every enterprise wrestles with compliance and privacy. Obsinity acknowledges that **not all data deserves the same lifespan**. Some events need to be kept for audits, others are useful for hours not years. With flexible retention and masking at the attribute level, Obsinity gives teams the controls they need to satisfy regulators without drowning in data.

## Operational Simplicity
Complexity kills adoption. Obsinity takes pride in being **operationally boring**: one PostgreSQL core, clear rollup intervals, no sprawling microservices to babysit. Rollups are generated at ingestion flush, meaning queries stay fast without expensive background jobs. Simplicity here translates to reliability in production.

## SDK-Driven Developer Experience
Instrumentation is often treated as a burden. Obsinity changes that narrative. With lightweight annotations (`@Flow`, `@Step`, `@OnEvent`), developers can embed observability directly into code without friction. Contracts are enforced at compile and runtime, making telemetry both **predictable and trustworthy**. In short: Obsinity makes observability something developers actually *want* to do.


# Reviewed counterexample sources

These eight source files are verbatim copies of the independent ROUND3 probes, not production fixes or newly executed tests. Their reports describe commands, cached dependencies, expected failing requirement assertions, good controls and limits. All original logs/classpaths remain in the unique temporary directories cited by the reports. Synthetic data/credentials only; no user audio/provider/model/service calls.

- C1/BillingRecoveryCheck.java: four401/402 capped-budget counterexamples and exact-charge reconciliation controls.
- C2/C2PreStartMigrationFixture.kt +stale_prestart_disposition.py: actual worker pre-start CompanionOnly escape and exact production SQL stale-failure disposition.
- C3/AdversarialC3.kt: capped429 reservation, header-failure boundary and working cancellation controls.
- C4/ProviderBoundaryProbe.java +FutureEvidenceProbe.java: present whitespace/absent provider text and unknown source metadata dropped by overlay.
- C6/C6BoundaryAdversary.java: real suspension inside final refresh, new unarchived audio/source references, both eviction paths.
- C7/DottedDateProbe.java: nonblocking extensionless dotted date loss.

They target the reviewed CLOUD snapshot and cached artifacts. Root must adapt project/classpath arguments to its integrated snapshot before reusing them, add durable regressions to normal test suites and report new results honestly. Copying them does not verify a correction or combined compatibility. Do not start live services, models or devices. Source files are evidence; raw per-review reports remain authoritative about executed scope and exit outcomes.

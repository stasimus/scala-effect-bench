# Blocking TCP results

These default-runtime results omit Kyo's documented blocking hint. See the [blocking-hint control](../blocking-hint/report.md)
and [investigation](../blocking-investigation/report.md) before interpreting the high-concurrency gap.

Recorded: 2026-09-06T10:48:07.137942+00:00. JDK 25.0.3; JMH 1.37.
Machine: macOS-26.6.1-arm64-arm-64bit-Mach-O; 16 CPU cores.

Three forks; five 1-second warmup and five 1-second measurement iterations; one JMH caller; fixed 2 GiB G1 client heap.
Each operation completes 256 requests. Throughput and latency use separate runs.
Throughput includes JMH 99.9% confidence-interval half-widths. Ratios compare means against CE default; overlapping intervals are flagged.
Latency percentiles describe complete batches in a closed-loop workload, including the runner. They are not individual request latency or an open-loop service SLA.
B/batch is client JVM allocation from the throughput run, including runtime activity. It is not retained heap, native stack memory, or peak process memory.

The local TCP server runs in a separate JVM with identical settings for every case; its allocation is excluded, but its CPU shares this machine.
Persistent connections, one outstanding request per connection, identical static worker lanes. Setup and connection establishment are excluded.
The server sleeps for the configured delay before replying; actual delay includes OS timer and scheduling overhead.

[Method and reproduction](../../docs/blocking-io.md). [Raw JSON](current.json), [run log](run.log), [provenance](metadata.json).

## Server delay 0 us, concurrency 8

| Runtime | Batches/s | Relative to CE | Batch p50 ms | Batch p99 ms | Client B/batch |
| --- | ---: | --- | ---: | ---: | ---: |
| CE default | 469.74 ± 3.36 | baseline | 2.142 | 2.454 | 71,811 |
| CE virtual calls | 270.04 ± 6.00 | 0.57x | 3.674 | 4.108 | 387,566 |
| Kyo default | 178.11 ± 0.84 | 0.38x | 5.710 | 6.456 | 103,596 |

## Server delay 0 us, concurrency 64

| Runtime | Batches/s | Relative to CE | Batch p50 ms | Batch p99 ms | Client B/batch |
| --- | ---: | --- | ---: | ---: | ---: |
| CE default | 488.97 ± 5.87 | baseline | 2.023 | 2.400 | 167,254 |
| CE virtual calls | 455.42 ± 15.93 | 0.93x | 2.134 | 2.687 | 474,004 |
| Kyo default | 177.36 ± 1.37 | 0.36x | 6.586 | 7.193 | 141,123 |

## Server delay 1000 us, concurrency 8

| Runtime | Batches/s | Relative to CE | Batch p50 ms | Batch p99 ms | Client B/batch |
| --- | ---: | --- | ---: | ---: | ---: |
| CE default | 22.08 ± 0.22 | baseline | 49.414 | 63.961 | 74,679 |
| CE virtual calls | 21.76 ± 0.26 | 0.99x (CIs overlap) | 49.349 | 72.496 | 373,118 |
| Kyo default | 18.44 ± 0.20 | 0.84x | 54.067 | 68.351 | 113,822 |

## Server delay 1000 us, concurrency 64

| Runtime | Batches/s | Relative to CE | Batch p50 ms | Batch p99 ms | Client B/batch |
| --- | ---: | --- | ---: | ---: | ---: |
| CE default | 152.81 ± 1.56 | baseline | 6.816 | 8.202 | 165,007 |
| CE virtual calls | 137.51 ± 3.51 | 0.90x | 7.602 | 10.956 | 458,999 |
| Kyo default | 4.96 ± 0.01 | 0.03x | 201.851 | 206.307 | 154,376 |

CE virtual calls uses IO.blocking(...).evalOn a virtual-thread executor for each request, retaining CE's default compute runtime.
Kyo uses Sync.defer on its default scheduler. This comparison does not replace CE's compute pool or enable Kyo's optional virtual workers.
These measurements cover successful blocking TCP exchanges on one machine. They do not establish a general library ranking or test cancellation/resource safety.

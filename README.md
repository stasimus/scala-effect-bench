# Scala effect benchmarks

JMH benchmarks comparing equivalent cats-effect and Kyo constructions. Only benchmark code is adjusted; libraries remain unchanged.

[Blocking TCP and virtual-thread experiment](docs/blocking-io.md).

[CE, Kyo, Gears and Ox: blocking/nonblocking TCP results](results/four-io-measured/report.md).

[Blog: Kyo's blocking gap and manual flush](https://sgektor.blogspot.com/2026/09/blocking-io-made-kyo-30x-slower-then-i.html).

cats-effect **3.7.1**, Kyo **1.0.0-RC6**, fs2 **3.13.0**, cats-core **2.13.0**, Scala **3.8.4**.

These pairs use the same application-level construction. The streaming rows compare fs2 on IO
with Kyo Stream. Native API diagnostics and withdrawn cancellation comparisons are excluded.

## Measurement

Recorded: 2026-09-05T10:14:53.817173+00:00. Platform: macOS-26.6.1-arm64-arm-64bit-Mach-O.
Machine: Mac15,9; 16 CPU cores; 48 GiB RAM.
JDK 25.0.3; JMH 1.37; G1; fixed 2 GB heap; library scheduler/tracing defaults.

Three independent JVM forks per case; five 1-second warmup and five 1-second measurement iterations
per fork; one JMH caller thread. Each operation processes the entire configured workload.
Throughput is ops/s (higher is better), with JMH's reported 99.9% confidence-interval half-width.
The ratio is the ratio of means, rounded; it is not a confidence interval for the speedup.
Rows whose throughput confidence intervals overlap are flagged; their mean ratios do not establish a winner.
Short iterations and one machine limit generalization. Runner overhead is included, never subtracted.

[Raw JSON](results/matched/current.json), [run log](results/matched/run.log), [machine, command, source and dependency hashes](results/matched/metadata.json).

Validation passed 984 checks before measurement: outputs/order, exact-once execution, worker concurrency,
batch boundaries, empty/all-failed inputs, partial batches, queue backpressure, and returned permits.

## Runner baseline

| Construction | Parameters | CE ops/s | Kyo ops/s | Ratio of means | CE B/op | Kyo B/op |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| Runner overhead | n/a | 119,173.45 ± 2,576.87 | 143,294.95 ± 5,626.38 | Kyo ~1.20× | 1,075 | 584 |

## Core chains

| Construction | Parameters | CE ops/s | Kyo ops/s | Ratio of means | CE B/op | Kyo B/op |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| Deep suspended bind | depth=1000 | 43,344.98 ± 625.48 | 43,772.78 ± 1,000.45 | Kyo ~1.01× (CIs overlap) | 95,040 | 80,569 |
| Deep suspended bind | depth=10000 | 7,514.90 ± 92.52 | 6,615.88 ± 73.78 | CE ~1.14× | 959,088 | 800,561 |
| Left-associated suspended bind | depth=1000 | 31,541.66 ± 1,244.48 | 296.08 ± 19.19 | CE ~106.53× | 88,800 | 16,092,711 |
| Left-associated suspended bind | depth=10000 | 5,220.89 ± 31.50 | 2.76 ± 0.02 | CE ~1894.87× | 942,561 | 1,602,545,940 |
| Map chain from suspended zero | depth=1000 | 44,957.01 ± 439.09 | 42,241.77 ± 1,272.57 | CE ~1.06× | 48,843 | 46,569 |
| Map chain from suspended zero | depth=10000 | 7,898.86 ± 83.06 | 6,598.88 ± 205.52 | CE ~1.20× | 542,904 | 478,573 |

## Parallel constructions

`size=4096`, `parallelism=8`. Bounded traversal uses the same benchmark-local worker
strategy on both sides. Success collection starts one child per element and joins all children;
four fail, and 4,092 successes are returned in order. This is not `Async.gather`.

| Construction | Parameters | CE ops/s | Kyo ops/s | Ratio of means | CE B/op | Kyo B/op |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| Bounded workers | work=0 | 3,910.19 ± 139.19 | 4,417.53 ± 174.71 | Kyo ~1.13× | 1,386,482 | 1,580,538 |
| Bounded workers | work=64 | 3,136.75 ± 53.20 | 1,410.97 ± 37.94 | CE ~2.22× | 1,390,519 | 1,603,831 |
| Parallel attempt + collect successes | work=0 | 961.31 ± 84.19 | 893.93 ± 13.86 | CE ~1.08× (CIs overlap) | 5,373,016 | 4,426,766 |
| Parallel attempt + collect successes | work=64 | 870.78 ± 108.57 | 619.41 ± 19.42 | CE ~1.41× | 5,376,501 | 4,463,636 |

## Primitives

`ops=1000`; queue capacity 64 integers. Queue and spawn/join use one producer/child at a time.

| Construction | Parameters | CE ops/s | Kyo ops/s | Ratio of means | CE B/op | Kyo B/op |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| CAS reference updates | n/a | 36,391.43 ± 3,730.17 | 36,536.30 ± 392.65 | Kyo ~1.00× (CIs overlap) | 111,198 | 110,705 |
| Complete then read promise | n/a | 12,833.45 ± 231.59 | 11,670.20 ± 115.51 | CE ~1.10× | 503,073 | 446,525 |
| Queue: one producer / consumer | n/a | 6,885.46 ± 72.56 | 9,965.27 ± 87.86 | Kyo ~1.45× | 386,094 | 382,802 |
| Uncontended non-reentrant permit | n/a | 2,532.39 ± 26.23 | 12,712.57 ± 1,226.12 | Kyo ~5.02× | 1,945,464 | 329,642 |
| Sequential child spawn / join | n/a | 1,949.69 ± 33.01 | 5,363.24 ± 131.32 | Kyo ~2.75× | 1,454,321 | 522,596 |

## Streaming constructions

`size=10000`, `chunkSize=64`, `parallelism=4`, queue capacity 64 chunks.
Both parallel streams complete and emit a whole batch before starting the next.
These are matched constructions, not the native `parEvalMap / mapPar` implementations.

| Construction | Parameters | fs2 ops/s | Kyo ops/s | Ratio of means | fs2 B/op | Kyo B/op |
| --- | --- | ---: | ---: | --- | ---: | ---: |
| Sequential chunk transformation | work=0 | 1,453.25 ± 89.95 | 1,171.51 ± 7.71 | fs2 ~1.24× | 3,079,615 | 4,600,001 |
| Sequential chunk transformation | work=64 | 584.38 ± 19.91 | 496.22 ± 8.31 | fs2 ~1.18× | 3,080,599 | 4,604,984 |
| Parallel chunk transformation | work=0 | 548.73 ± 17.72 | 1,058.25 ± 20.29 | Kyo ~1.93× | 5,011,564 | 5,111,114 |
| Parallel chunk transformation | work=64 | 413.34 ± 8.31 | 458.17 ± 7.05 | Kyo ~1.11× | 5,018,415 | 5,111,258 |
| Queue-backed chunk stream | work=0 | 1,414.26 ± 20.72 | 5,235.16 ± 43.74 | Kyo ~3.70× | 1,646,022 | 673,610 |
| Queue-backed chunk stream | work=64 | 584.47 ± 17.05 | 1,021.27 ± 25.40 | Kyo ~1.75× | 1,649,548 | 677,712 |

`work=0` is an increment; `work=64` adds 64 rounds of identical integer arithmetic.
B/op means total allocated bytes per complete operation, not retained memory or peak heap.

## Scope

These successful-work microbenchmarks do not measure cancellation, resource safety, real I/O,
tail latency, ecosystem, or application performance. Library implementation costs remain part
of the measurements. Historical native-API results are preserved in
[the archived report](results/archive/result-before-matched.md), not mixed into this table.

Reproduce with `python3 scripts/run_matched.py --output results/matched-new`.

# CE, Kyo, Gears and Ox: TCP I/O

CE 3.7.1, Kyo 1.0.0-RC6, Gears 0.3.1, Ox 1.0.6; Scala 3.8.4.
JDK 25.0.3, JMH 1.37. Recorded 2026-09-06T16:11:58.948330+00:00.
Three forks, ten 1-second warmup and five 1-second measurement iterations; one JMH caller; 2 GiB G1 client heap.
Each batch completes 256 requests across 8 or 64 persistent connections, with one outstanding request per connection.
The identical separate server JVM requests a 1 ms sleep before each response; actual time includes OS scheduling.

Blocking uses java.net.Socket. Nonblocking uses shared AsynchronousSocketChannel code with two platform completion threads.
Gears and Ox execute their direct-style waits on virtual threads in both modes. The mode names identify socket APIs, not carrier blocking.
Root/worker creation, runtime entry, scoped lifetime management, callbacks and ordered result construction are measured.
Every worker outcome is collected, all workers are joined, then the first lane-ordered failure is propagated.

Batches/s includes JMH 99.9% confidence-interval half-widths. Ratios compare mean throughput against CE in the same group.
p50/p99 are closed-loop batch latency from separate sample-time runs, not per-request latency or an external arrival-rate SLA.
Allocation and CPU come from throughput runs. Client CPU includes runtime, GC and completion threads, excluding the separate server.
Allocation is not retained heap, native stacks or peak RSS. The server still shares this host's CPU.

[Methods and sources](../../docs/four-library-io.md). [Provenance](metadata.json), [run log](run.log).

## Blocking, concurrency 8: defaults

| Runtime | Batches/s | Relative to CE | Batch p50 ms | Batch p99 ms | B/batch | Client CPU ms/batch |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| CE | 22.00 ± 0.23 | baseline | 45.679 | 76.478 | 106,228 | 6.473 |
| Kyo | 18.31 ± 0.18 | 0.83x | 53.346 | 69.749 | 136,238 | 8.147 |
| Gears | 21.06 ± 0.56 | 0.96x | 46.203 | 75.674 | 83,435 | 10.503 |
| Ox | 21.32 ± 1.45 | 0.97x (CIs overlap) | 45.941 | 87.965 | 70,781 | 10.202 |

## Blocking, concurrency 64: defaults

| Runtime | Batches/s | Relative to CE | Batch p50 ms | Batch p99 ms | B/batch | Client CPU ms/batch |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| CE | 149.00 ± 2.93 | baseline | 6.603 | 7.834 | 373,993 | 8.752 |
| Kyo | 4.96 ± 0.02 | 0.03x | 201.327 | 205.521 | 299,567 | 14.405 |
| Gears | 138.62 ± 5.82 | 0.93x | 7.176 | 8.389 | 185,124 | 12.079 |
| Ox | 139.40 ± 5.38 | 0.94x | 7.328 | 12.001 | 136,170 | 11.580 |

## Nonblocking, concurrency 8: defaults

| Runtime | Batches/s | Relative to CE | Batch p50 ms | Batch p99 ms | B/batch | Client CPU ms/batch |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| CE | 20.57 ± 1.21 | baseline | 45.023 | 48.339 | 336,268 | 9.552 |
| Kyo | 23.04 ± 0.14 | 1.12x | 43.319 | 45.405 | 396,871 | 8.790 |
| Gears | 20.26 ± 1.06 | 0.99x (CIs overlap) | 46.399 | 76.027 | 327,122 | 11.729 |
| Ox | 21.91 ± 0.15 | 1.06x (CIs overlap) | 47.579 | 79.823 | 205,083 | 9.581 |

## Nonblocking, concurrency 64: defaults

| Runtime | Batches/s | Relative to CE | Batch p50 ms | Batch p99 ms | B/batch | Client CPU ms/batch |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| CE | 141.77 ± 1.83 | baseline | 7.209 | 7.953 | 593,045 | 5.865 |
| Kyo | 137.08 ± 0.85 | 0.97x | 7.287 | 8.187 | 552,350 | 7.715 |
| Gears | 136.58 ± 1.27 | 0.96x | 7.365 | 8.287 | 429,094 | 9.408 |
| Ox | 133.90 ± 2.85 | 0.94x | 7.496 | 8.274 | 267,034 | 8.964 |

## Blocking, concurrency 64: explicit alternatives

| Runtime | Batches/s | Relative to CE | Batch p50 ms | Batch p99 ms | B/batch | Client CPU ms/batch |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| CE default | 149.00 ± 2.93 | baseline | 6.603 | 7.834 | 373,993 | 8.752 |
| CE virtual lanes | 133.25 ± 4.91 | 0.89x | 7.561 | 10.206 | 474,882 | 13.479 |
| Kyo queue flush | 81.86 ± 25.62 | 0.55x | 10.715 | 13.014 | 299,141 | 9.287 |
| Kyo flush + fixed64 + scan64 | 144.89 ± 3.60 | 0.97x (CIs overlap) | 6.742 | 8.240 | 290,498 | 9.016 |

CE virtual lanes shifts each complete worker loop once to a virtual-thread executor, retaining the default compute runtime.
Kyo queue flush calls Scheduler.get.flush() before every exchange. Fixed64 additionally sets coreWorkers, minWorkers, maxWorkers and scheduleStride to 64.
These are disclosed alternatives, not an exhaustive search for each library's best tuning. Default-runtime rows remain separate.

This suite measures successful TCP batches on one host. It does not establish general library speed or equivalent cancellation guarantees.
Checks cover outputs, exactly-once execution, limits, worker overlap, thread placement, join-all failure handling, connection reuse, and callback-transport cancellation.
Cross-library parent cancellation and resource-safety equivalence need separate tests. Socket providers, callback adapters and scoped task bookkeeping contribute to the results.

Raw JMH files: [defaults](default.json), [alternatives](controls.json), [fixed Kyo](kyo-tuned.json).

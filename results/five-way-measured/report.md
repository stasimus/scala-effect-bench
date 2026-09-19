# CE, Kyo, Loom, Ox and Gears

CE 3.7.1, Kyo 1.0.0-RC6, Ox 1.0.6, Gears 0.3.1; Scala 3.8.4.
JDK 25.0.3, JMH 1.37. Started 2026-09-19T14:18:47.280968+00:00.
3 JVM forks, 3 warmup and 3 measurement iterations of 1 s per case; one JMH caller; 2 GiB G1 heap.
Each operation includes runtime entry, task creation, work and result collection. Entry overhead is reported separately and never subtracted.
Allocation is bytes allocated per complete operation; CPU is client process CPU milliseconds per operation. Neither measures retained memory or native stacks.
Throughput errors are JMH's 99.9% confidence-interval half-widths. Ratios are ratios of means, not speedup confidence intervals; overlapping throughput intervals do not establish a winner.
One host, short iterations and a fixed case order limit generalization. These results describe the listed constructions, including their adapters and bookkeeping.

[Methods and API mapping](../../docs/five-way.md). [Metadata](metadata.json), [run log](run.log), [synthetic JSON](synthetic.json), [TCP JSON](tcp.json).

Loom uses JDK virtual threads, ArrayBlockingQueue, CompletableFuture, Semaphore and AtomicReference. Ox uses forks, channels and Flow, with JDK promises, permits and atomics. Gears uses Futures, channels and semaphores, with JDK atomics.
Pipeline rows compare fs2, Kyo Stream, Ox Flow and explicit Loom/Gears loops with the same chunk barriers. They compare workloads across different APIs.

## Bounded workers

parallelism=8, size=4096, work=0

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 3,917.02 ± 78.30 | baseline | 1,386,485 | 1.2731 |
| Kyo | 4,495.77 ± 70.92 | 1.15x | 1,580,654 | 0.2387 |
| Loom / JDK | 4,498.56 ± 166.64 | 1.15x | 104,422 | 1.5140 |
| Ox | 4,654.51 ± 691.04 | 1.19x (CIs overlap) | 107,316 | 1.4479 |
| Gears | 4,084.21 ± 171.99 | 1.04x (CIs overlap) | 113,227 | 1.5802 |

## Bounded workers

parallelism=8, size=4096, work=64

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 3,059.76 ± 92.18 | baseline | 1,390,550 | 1.5152 |
| Kyo | 1,399.36 ± 25.94 | 0.46x | 1,582,643 | 0.7807 |
| Loom / JDK | 3,420.27 ± 71.03 | 1.12x | 106,388 | 2.0468 |
| Ox | 3,579.77 ± 229.72 | 1.17x | 109,364 | 1.9532 |
| Gears | 3,213.95 ± 49.88 | 1.05x | 115,239 | 2.0969 |

## Collect successes

parallelism=8, size=4096, work=0

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 1,040.09 ± 77.99 | baseline | 5,372,896 | 1.9657 |
| Kyo | 904.39 ± 50.40 | 0.87x | 4,459,511 | 1.2231 |
| Loom / JDK | 677.08 ± 95.60 | 0.65x | 2,070,747 | 18.6692 |
| Ox | 884.33 ± 15.42 | 0.85x | 2,991,379 | 14.0065 |
| Gears | 200.16 ± 10.11 | 0.19x | 5,955,502 | 23.2503 |

## Collect successes

parallelism=8, size=4096, work=64

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 935.90 ± 40.33 | baseline | 5,376,201 | 2.5110 |
| Kyo | 644.45 ± 15.15 | 0.69x | 4,529,162 | 1.6931 |
| Loom / JDK | 670.09 ± 98.42 | 0.72x | 2,073,487 | 19.0333 |
| Ox | 847.62 ± 62.20 | 0.91x (CIs overlap) | 2,996,340 | 15.0676 |
| Gears | 186.61 ± 8.43 | 0.20x | 6,127,587 | 24.8130 |

## CAS reference updates

capacity=64, ops=1000

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 37,647.38 ± 1,062.29 | baseline | 111,208 | 0.0283 |
| Kyo | 37,840.78 ± 640.59 | 1.01x (CIs overlap) | 110,705 | 0.0290 |
| Loom / JDK | 94,825.82 ± 1,522.17 | 2.52x | 14,440 | 0.0108 |
| Ox | 94,607.62 ± 3,173.09 | 2.51x | 15,005 | 0.0109 |
| Gears | 81,841.79 ± 5,547.63 | 2.17x | 15,832 | 0.0126 |

## Complete then read promise

capacity=64, ops=1000

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 12,777.98 ± 304.87 | baseline | 503,059 | 0.0818 |
| Kyo | 11,825.03 ± 217.40 | 0.93x | 446,530 | 0.0912 |
| Loom / JDK | 127,631.05 ± 3,394.09 | 9.99x | 38,424 | 0.0082 |
| Ox | 91,542.31 ± 25,575.45 | 7.16x | 38,984 | 0.0116 |
| Gears | 22,935.00 ± 824.73 | 1.79x | 327,864 | 0.0458 |

## Single-producer / consumer queue

capacity=64, ops=1000

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 6,730.12 ± 129.04 | baseline | 385,745 | 0.2875 |
| Kyo | 10,071.56 ± 211.66 | 1.50x | 382,819 | 0.1073 |
| Loom / JDK | 9,361.64 ± 760.54 | 1.39x | 24,729 | 0.2840 |
| Ox | 9,546.15 ± 322.31 | 1.42x | 24,573 | 0.2313 |
| Gears | 5,131.65 ± 226.75 | 0.76x | 247,097 | 0.3894 |

## Uncontended non-reentrant permit

capacity=64, ops=1000

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 2,459.74 ± 62.40 | baseline | 1,945,454 | 0.4282 |
| Kyo | 13,662.29 ± 909.43 | 5.55x | 329,642 | 0.0789 |
| Loom / JDK | 60,196.52 ± 225.86 | 24.47x | 497 | 0.0168 |
| Ox | 62,244.29 ± 5,548.71 | 25.31x | 1,067 | 0.0163 |
| Gears | 57,282.19 ± 1,052.20 | 23.29x | 7,211 | 0.0179 |

## Sequential child spawn / join

capacity=64, ops=1000

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 1,894.00 ± 85.25 | baseline | 1,454,165 | 1.0706 |
| Kyo | 5,355.48 ± 103.57 | 2.83x | 526,062 | 0.1983 |
| Loom / JDK | 558.17 ± 42.52 | 0.29x | 466,293 | 6.0306 |
| Ox | 494.49 ± 50.67 | 0.26x | 700,459 | 6.6138 |
| Gears | 376.62 ± 25.50 | 0.20x | 1,575,980 | 7.3910 |

## Sequential chunk transformation

capacity=64, chunkSize=64, parallelism=4, size=10000, work=0

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 1,493.97 ± 148.34 | baseline | 3,078,668 | 0.7564 |
| Kyo | 1,194.04 ± 24.14 | 0.80x | 4,602,545 | 0.9485 |
| Loom / JDK | 23,791.76 ± 2,119.39 | 15.93x | 211,066 | 0.0437 |
| Ox | 29,700.43 ± 830.08 | 19.88x | 209,563 | 0.0351 |
| Gears | 25,645.54 ± 648.16 | 17.17x | 212,462 | 0.0406 |

## Sequential chunk transformation

capacity=64, chunkSize=64, parallelism=4, size=10000, work=64

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 593.36 ± 31.02 | baseline | 3,082,731 | 1.7654 |
| Kyo | 509.95 ± 21.78 | 0.86x | 4,602,544 | 2.1399 |
| Loom / JDK | 1,205.70 ± 52.89 | 2.03x | 213,128 | 0.8492 |
| Ox | 1,209.45 ± 51.51 | 2.04x | 211,597 | 0.8546 |
| Gears | 1,212.05 ± 38.24 | 2.04x | 214,489 | 0.8506 |

## Parallel chunk transformation

capacity=64, chunkSize=64, parallelism=4, size=10000, work=0

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 512.26 ± 11.11 | baseline | 5,013,690 | 3.7553 |
| Kyo | 1,058.98 ± 14.71 | 2.07x | 5,108,815 | 1.0452 |
| Loom / JDK | 686.64 ± 23.31 | 1.34x | 621,575 | 6.1589 |
| Ox | 677.32 ± 19.57 | 1.32x | 752,323 | 6.0355 |
| Gears | 694.87 ± 15.97 | 1.36x | 1,143,049 | 5.1705 |

## Parallel chunk transformation

capacity=64, chunkSize=64, parallelism=4, size=10000, work=64

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 394.69 ± 6.33 | baseline | 5,025,688 | 4.8569 |
| Kyo | 460.96 ± 6.26 | 1.17x | 5,111,257 | 2.3107 |
| Loom / JDK | 566.20 ± 20.38 | 1.43x | 618,130 | 8.2079 |
| Ox | 557.53 ± 26.49 | 1.41x | 754,910 | 8.2656 |
| Gears | 515.21 ± 18.14 | 1.31x | 1,167,355 | 7.6198 |

## Queue-backed chunk pipeline

capacity=64, chunkSize=64, parallelism=4, size=10000, work=0

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 1,381.31 ± 48.57 | baseline | 1,646,275 | 1.2581 |
| Kyo | 5,282.19 ± 67.35 | 3.82x | 675,295 | 0.2053 |
| Loom / JDK | 28,616.06 ± 1,244.33 | 20.72x | 4,994 | 0.0705 |
| Ox | 28,484.97 ± 1,287.81 | 20.62x | 9,646 | 0.0738 |
| Gears | 19,631.68 ± 1,086.58 | 14.21x | 39,066 | 0.0921 |

## Queue-backed chunk pipeline

capacity=64, chunkSize=64, parallelism=4, size=10000, work=64

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 599.07 ± 16.59 | baseline | 1,650,044 | 2.3811 |
| Kyo | 1,012.77 ± 20.84 | 1.69x | 677,754 | 1.0639 |
| Loom / JDK | 1,036.18 ± 24.73 | 1.73x | 8,614 | 1.2383 |
| Ox | 1,034.92 ± 5.48 | 1.73x | 13,539 | 1.2066 |
| Gears | 1,080.80 ± 20.72 | 1.80x | 51,300 | 1.1709 |

## Runtime entry

No workload parameters.

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 126,888.46 ± 3,155.16 | baseline | 1,075 | 0.0086 |
| Kyo | 152,380.44 ± 1,174.36 | 1.20x | 584 | 0.0074 |
| Loom / JDK | 168,466.55 ± 3,509.29 | 1.33x | 424 | 0.0064 |
| Ox | 163,094.71 ± 2,883.94 | 1.29x | 986 | 0.0066 |
| Gears | 132,616.01 ± 1,792.03 | 1.05x | 1,747 | 0.0082 |

## Sequential arithmetic: separate programming-style baseline

depth=1000

CE/Kyo execute the suspended deep-bind calculation. Loom/Ox/Gears execute a direct while loop, which the JIT may simplify. This is not an equivalent bind-chain implementation; no relative speedup is reported.

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 43,684.54 ± 787.45 | n/a | 95,056 | 0.0242 |
| Kyo | 46,307.61 ± 754.92 | n/a | 80,579 | 0.0238 |
| Loom / JDK | 167,392.86 ± 2,258.28 | n/a | 456 | 0.0064 |
| Ox | 162,622.95 ± 1,797.08 | n/a | 1,027 | 0.0066 |
| Gears | 132,977.17 ± 3,317.54 | n/a | 1,872 | 0.0082 |

## Sequential arithmetic: separate programming-style baseline

depth=10000

CE/Kyo execute the suspended deep-bind calculation. Loom/Ox/Gears execute a direct while loop, which the JIT may simplify. This is not an equivalent bind-chain implementation; no relative speedup is reported.

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op |
| --- | ---: | --- | ---: | ---: |
| CE | 7,425.72 ± 142.66 | n/a | 959,089 | 0.1397 |
| Kyo | 7,277.73 ± 846.05 | n/a | 800,568 | 0.1481 |
| Loom / JDK | 166,387.90 ± 4,312.33 | n/a | 456 | 0.0065 |
| Ox | 163,867.41 ± 2,213.57 | n/a | 1,021 | 0.0065 |
| Gears | 131,091.15 ± 3,900.72 | n/a | 1,888 | 0.0082 |

## TCP request batches

delayMicros=1000, parallelism=8, size=256, transport=blocking

256 exchanges through persistent loopback connections. The separate server requests 1 ms per response and shares the host CPU. p50/p99 are closed-loop batch latencies from separate sample-time trials.

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op | Batch p50 ms | Batch p99 ms |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| CE | 20.96 ± 0.11 | baseline | 107,497 | 11.0355 | 47.514 | 49.227 |
| Kyo | 18.35 ± 0.27 | 0.88x | 136,658 | 7.4853 | 53.412 | 68.619 |
| Loom / JDK | 21.09 ± 0.34 | 1.01x (CIs overlap) | 70,582 | 15.7963 | 47.251 | 48.630 |
| Ox | 20.98 ± 0.34 | 1.00x (CIs overlap) | 74,675 | 15.9448 | 47.120 | 48.434 |
| Gears | 21.12 ± 0.34 | 1.01x (CIs overlap) | 86,965 | 15.1543 | 47.448 | 48.632 |

## TCP request batches

delayMicros=1000, parallelism=64, size=256, transport=blocking

256 exchanges through persistent loopback connections. The separate server requests 1 ms per response and shares the host CPU. p50/p99 are closed-loop batch latencies from separate sample-time trials.

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op | Batch p50 ms | Batch p99 ms |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| CE | 145.10 ± 9.03 | baseline | 374,996 | 9.4630 | 6.906 | 8.243 |
| Kyo | 4.95 ± 0.03 | 0.03x | 305,745 | 24.7690 | 201.327 | 204.472 |
| Loom / JDK | 143.49 ± 5.65 | 0.99x (CIs overlap) | 120,063 | 11.7958 | 7.283 | 8.225 |
| Ox | 140.29 ± 6.62 | 0.97x (CIs overlap) | 135,487 | 12.0379 | 7.307 | 8.395 |
| Gears | 140.28 ± 9.81 | 0.97x (CIs overlap) | 187,563 | 12.2448 | 7.193 | 8.128 |

## TCP request batches

delayMicros=1000, parallelism=8, size=256, transport=nonblocking

256 exchanges through persistent loopback connections. The separate server requests 1 ms per response and shares the host CPU. p50/p99 are closed-loop batch latencies from separate sample-time trials.

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op | Batch p50 ms | Batch p99 ms |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| CE | 21.12 ± 0.92 | baseline | 336,946 | 11.8041 | 47.710 | 50.287 |
| Kyo | 22.97 ± 0.12 | 1.09x | 397,641 | 10.5494 | 43.450 | 44.499 |
| Loom / JDK | 21.04 ± 0.40 | 1.00x (CIs overlap) | 151,046 | 14.5243 | 47.514 | 48.978 |
| Ox | 20.87 ± 0.42 | 0.99x (CIs overlap) | 211,989 | 15.9324 | 47.710 | 49.349 |
| Gears | 21.00 ± 0.55 | 0.99x (CIs overlap) | 339,553 | 15.7347 | 47.186 | 49.021 |

## TCP request batches

delayMicros=1000, parallelism=64, size=256, transport=nonblocking

256 exchanges through persistent loopback connections. The separate server requests 1 ms per response and shares the host CPU. p50/p99 are closed-loop batch latencies from separate sample-time trials.

| Runtime | Ops/s ± error | Relative to CE | B/op | CPU ms/op | Batch p50 ms | Batch p99 ms |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| CE | 141.53 ± 3.07 | baseline | 593,309 | 5.9713 | 7.184 | 7.743 |
| Kyo | 139.99 ± 1.73 | 0.99x (CIs overlap) | 552,889 | 7.9566 | 7.225 | 8.101 |
| Loom / JDK | 137.69 ± 3.44 | 0.97x (CIs overlap) | 199,882 | 9.1259 | 7.340 | 7.988 |
| Ox | 136.78 ± 3.48 | 0.97x (CIs overlap) | 272,144 | 9.2921 | 7.356 | 8.045 |
| Gears | 139.25 ± 2.57 | 0.98x (CIs overlap) | 430,149 | 9.5853 | 7.242 | 7.848 |

Successful-work performance does not establish equivalent cancellation, timeout, race or resource-cleanup guarantees. The TCP checks cover join-all failure handling; separate cancellation comparisons remain out of scope.

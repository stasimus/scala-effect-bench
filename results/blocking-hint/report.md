# Blocking hint control

Recorded 2026-09-06T13:15:44.226962+00:00. JDK 25.0.3; 64 persistent connections; 256 requests/batch; 1 ms requested server delay.
Three forks, five 1-second warmup and measurement iterations; fixed 2 GiB G1 client heap. 48 correctness checks passed.
Same worker loops and TCP fixture. CE uses IO.blocking; Kyo flushes its local scheduler queue before each blocking call.
Throughput includes JMH 99.9% confidence-interval half-widths. Latency is sampled batch latency from separate runs; short runs limit p99 precision.
Allocation covers the client JVM, including background activity, and excludes server allocation and native memory.

| Runtime | Batches/s | Batch p50 ms | Batch p99 ms | Client B/batch |
| --- | ---: | ---: | ---: | ---: |
| CE default | 150.77 ± 3.83 | 6.586 | 7.848 | 165,037 |
| Kyo with flush | 54.72 ± 15.17 | 17.039 | 22.675 | 162,933 |

This focused control covers one workload; it does not establish a general ranking.
[Raw data](current.json), [log](run.log), [provenance](metadata.json).

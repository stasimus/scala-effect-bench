# Cats Effect versus Loom: memory consumption

CE 3.7.1; JDK 25.0.3; Scala 3.8.4; macOS. G1, initial heap 64 MiB, maximum heap 2 GiB, no pre-touch or NMT.
Three fresh JVMs per case. Cells are the median [minimum, maximum] across JVMs. Memory is MiB unless stated otherwise.
Physical footprint and RSS come from macOS proc_pid_rusage for the client PID. They are different OS accounting metrics and are not added together.
Peak is the maximum of the kernel's reported lifetime peak and observed footprints through the final snapshot. It includes startup, warmup, work and explicit GC. The separate TCP server is excluded.
Occupied heap is sampled after three confirmed full collections; it approximates live heap and is not an allocation-rate metric.

[Method and reproduction](../../docs/memory.md). [Raw measurements](memory.json). [Provenance](metadata.json).

## Waiting tasks: 0 payload bytes per task

Each child retains its own byte array across the wait and one join handle remains reachable per child. CE waits on Deferred; Loom uses CountDownLatch and a virtual-thread executor.
Post-GC process memory is reported while every task remains held. Incremental heap/task subtracts the warmed baseline; it includes wait objects, task handles and payload array headers.

| Tasks | Runtime | Live heap | Incremental heap B/task | Physical footprint | RSS | Peak footprint | Heap after release |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | ce | 5.68 [5.67, 5.68] | 903.85 [901.80, 912.49] | 117.78 [115.20, 117.92] | 141.00 [138.42, 141.16] | 117.84 [115.24, 117.97] | 4.85 [4.83, 4.85] |
| 1,000 | loom | 5.52 [5.49, 5.52] | 2425.90 [2394.91, 2426.46] | 76.19 [75.88, 76.97] | 99.19 [98.84, 99.92] | 76.30 [75.97, 77.03] | 3.23 [3.23, 3.23] |
| 10,000 | ce | 13.52 [13.52, 13.55] | 914.63 [914.63, 915.35] | 126.14 [124.66, 128.06] | 149.34 [147.89, 151.30] | 127.88 [125.06, 128.47] | 5.17 [5.16, 5.20] |
| 10,000 | loom | 26.59 [26.50, 26.60] | 2451.60 [2442.21, 2453.09] | 89.89 [81.28, 106.77] | 112.89 [104.25, 129.73] | 90.42 [81.84, 107.30] | 3.27 [3.27, 3.27] |
| 100,000 | ce | 92.82 [92.80, 92.83] | 923.08 [922.60, 923.13] | 236.91 [236.88, 240.39] | 260.08 [260.03, 263.58] | 293.55 [264.78, 296.36] | 8.37 [8.36, 8.37] |
| 100,000 | loom | 146.56 [137.04, 154.88] | 1503.20 [1403.39, 1590.38] | 250.06 [239.61, 259.61] | 272.94 [262.53, 282.50] | 253.16 [253.13, 274.38] | 4.35 [4.29, 4.48] |

## Waiting tasks: 1024 payload bytes per task

Each child retains its own byte array across the wait and one join handle remains reachable per child. CE waits on Deferred; Loom uses CountDownLatch and a virtual-thread executor.
Post-GC process memory is reported while every task remains held. Incremental heap/task subtracts the warmed baseline; it includes wait objects, task handles and payload array headers.

| Tasks | Runtime | Live heap | Incremental heap B/task | Physical footprint | RSS | Peak footprint | Heap after release |
| ---: | --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1,000 | ce | 6.62 [6.61, 6.62] | 1946.86 [1929.02, 1950.02] | 124.17 [120.84, 125.55] | 147.45 [144.14, 148.84] | 124.24 [120.94, 125.59] | 4.80 [4.80, 4.80] |
| 1,000 | loom | 6.51 [6.51, 6.51] | 3456.98 [3448.57, 3460.50] | 82.89 [80.49, 85.53] | 105.94 [103.50, 108.59] | 82.95 [80.56, 85.64] | 3.24 [3.23, 3.24] |
| 10,000 | ce | 23.27 [23.24, 23.29] | 1938.81 [1938.23, 1941.17] | 141.72 [141.59, 143.05] | 165.00 [164.86, 166.34] | 143.42 [141.97, 144.25] | 5.15 [5.12, 5.17] |
| 10,000 | loom | 36.14 [36.12, 36.29] | 3451.98 [3450.23, 3463.89] | 123.64 [121.89, 127.31] | 146.69 [144.92, 150.38] | 124.28 [122.34, 127.50] | 3.27 [3.27, 3.31] |
| 100,000 | ce | 190.96 [190.61, 191.17] | 1952.11 [1948.57, 1954.57] | 418.45 [399.61, 420.08] | 441.58 [422.72, 443.20] | 422.88 [401.69, 422.99] | 8.34 [8.33, 8.36] |
| 100,000 | loom | 228.70 [227.52, 247.40] | 2364.42 [2351.99, 2560.53] | 389.99 [386.81, 398.06] | 412.84 [409.72, 420.95] | 393.28 [391.92, 401.80] | 4.36 [4.36, 4.36] |

## TCP client memory

256 exchanges per batch, 8 or 64 persistent connections, requested 1 ms server delay. After 32 warmup batches, memory is sampled during five seconds of continuous batches. Each batch's ordered output is checked.
Active footprint/RSS are medians of 50 samples per JVM. Post-work heap is measured after the driver has joined and full GC has completed, with persistent sockets still open. It is not the heap of a suspended in-flight batch.

| Connections | Transport | Runtime | Active footprint | Active RSS | Peak footprint | Post-work heap |
| ---: | --- | --- | ---: | ---: | ---: | ---: |
| 8 | blocking | ce | 113.75 [111.81, 115.00] | 137.06 [135.14, 138.31] | 113.83 [111.95, 115.13] | 5.20 [5.17, 5.21] |
| 8 | blocking | loom | 76.52 [75.45, 76.64] | 99.59 [98.50, 99.70] | 76.64 [75.50, 77.47] | 3.79 [3.79, 3.80] |
| 8 | nonblocking | ce | 125.10 [121.74, 125.75] | 148.37 [145.02, 149.03] | 127.66 [124.64, 128.63] | 5.07 [5.06, 5.08] |
| 8 | nonblocking | loom | 89.14 [78.91, 94.36] | 112.17 [101.95, 117.41] | 90.24 [79.75, 95.41] | 3.74 [3.74, 3.74] |
| 64 | blocking | ce | 152.49 [139.59, 152.59] | 175.77 [162.83, 175.83] | 152.92 [140.34, 153.31] | 5.47 [5.47, 5.48] |
| 64 | blocking | loom | 90.32 [87.94, 101.08] | 113.46 [111.06, 124.19] | 96.70 [93.34, 106.77] | 3.88 [3.84, 3.90] |
| 64 | nonblocking | ce | 135.71 [133.90, 139.06] | 158.98 [157.18, 162.33] | 136.16 [134.39, 139.58] | 5.18 [5.18, 5.18] |
| 64 | nonblocking | loom | 105.89 [99.61, 111.19] | 128.95 [122.69, 134.27] | 107.64 [101.56, 114.88] | 3.81 [3.80, 3.83] |

These measurements cover the listed wait primitives, task handles, payload and TCP construction. They do not establish a universal number of bytes per fiber or virtual thread. Stack depth, application state, heap policy and workload duration can change the result.

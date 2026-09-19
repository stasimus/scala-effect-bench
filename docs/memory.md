# CE versus Loom: memory consumption

## Results

[Summary and charts](../results/memory-ce-loom-measured/analysis.md),
[full report](../results/memory-ce-loom-measured/report.md),
[raw measurements](../results/memory-ce-loom-measured/memory.json),
[run metadata](../results/memory-ce-loom-measured/metadata.json).

The completed study covers 60 fresh JVMs: waiting tasks and TCP clients, with live heap,
process footprint, RSS and peak footprint. Each case has three JVM measurements.

## Run

Run from the repository root on macOS with JDK 25.0.3, sbt and Python 3.
The runner needs access to process memory counters and local TCP sockets. Choose a fresh output directory.

```sh
python3 scripts/run_memory.py --output results/memory-new
```

Allow 10 to 15 minutes after compilation. The runner validates first and writes reports,
raw samples and GC logs to the output directory. It uses G1 with a 64 MiB initial and 2 GiB maximum heap.

For an eight-case smoke check:

```sh
python3 scripts/run_memory.py --profile smoke --output results/memory-smoke-new
```

Smoke results are unsuitable for memory comparisons.

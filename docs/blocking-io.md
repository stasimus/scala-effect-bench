# Blocking TCP and virtual threads

## Results

- [Default runtimes and virtual calls](../results/blocking/report.md).
- [Queue-flush JMH comparison](../results/blocking-hint/report.md).
- [Controlled diagnostics](../results/blocking-investigation/report.md).
- [Scheduler investigation and tuning results](../results/kyo-blocking-research/report-source.md).

JMH reports include throughput, latency and allocation. Diagnostic timings are separate from JMH scores.

## Run

Run from the repository root with JDK 25.0.3, sbt and Python 3. Use a fresh output directory for each run.

Default-runtime comparison, about 20 minutes after compilation:

```sh
python3 scripts/run_blocking.py --output results/blocking-new
```

CE versus Kyo with queue flush, at 64 connections and a requested 1 ms server delay:

```sh
python3 scripts/run_blocking_hint.py --output results/blocking-hint-new
```

Scheduler diagnostics, three fresh JVMs per variant:

```sh
python3 scripts/research_kyo_blocking.py \
  --cases ce plain flush64Stride64 --repeat 3 \
  --output results/kyo-blocking-research-new
```

The JMH runners validate before measuring and write reports, raw results and logs to the output directory.
The diagnostic runner writes observations and logs.

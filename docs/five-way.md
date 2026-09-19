# CE, Kyo, Loom, Ox and Gears

## Results

[Measured report](../results/five-way-measured/report.md): 130 synthetic and TCP configurations,
including throughput, batch latency, allocation and CPU.

[Charts](../results/five-way-measured/),
[synthetic data](../results/five-way-measured/synthetic.json),
[TCP data](../results/five-way-measured/tcp.json),
[run metadata](../results/five-way-measured/metadata.json).

## Run

Run from the repository root with JDK 25.0.3, sbt and Python 3. Choose a fresh output directory.

```sh
python3 scripts/run_five_way.py --output results/five-way-new
```

Allow about 45 minutes after compilation. The runner validates first, then uses three JVM forks,
three 1-second warmups and three 1-second measurements per case. Reports, raw results and logs go in the output directory.

For a smoke check of the harness:

```sh
python3 scripts/run_five_way.py --profile smoke --output results/five-way-smoke-new
```

The smoke profile runs short benchmarks; its results are unsuitable for performance comparisons.

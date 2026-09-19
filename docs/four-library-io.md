# Four-library TCP comparison

## Results

[Measured report](../results/four-io-measured/report.md): CE, Kyo, Gears and Ox;
blocking and callback TCP, including Kyo tuning and CE virtual lanes.

The report covers throughput, batch latency, allocation and CPU across 38 configurations.
[Raw results and logs](../results/four-io-measured/).

## Run

Run from the repository root with JDK 25.0.3, sbt and Python 3. Choose a fresh output directory.

```sh
python3 scripts/run_four_io.py --output results/four-io-new
```

Allow about 40 minutes after compilation. The runner validates first, then uses three JVM forks,
ten 1-second warmups and five 1-second measurements per case. Reports and raw results go in the output directory.

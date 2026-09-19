#!/usr/bin/env python3
"""Derive blog analysis and optional figures from a verified five-way measurement."""
import argparse
import json
from pathlib import Path
import statistics

from report_five_way import EXPECTED, RUNTIMES, load_verified


def analyze(rows):
    groups = {}
    for row in rows:
        if row["mode"] != "thrpt":
            continue
        params = {k: v for k, v in row["params"].items() if k != "runtime"}
        group = (row["benchmark"], tuple(sorted(params.items())))
        if group not in groups:
            lookup = (row["benchmark"], "thrpt", tuple(sorted(row["params"].items())))
            groups[group] = dict(benchmark=row["benchmark"], label=EXPECTED[lookup], params=params, runtimes={})
        metric = row["primaryMetric"]
        forks = [statistics.mean(fork) for fork in metric["rawData"]]
        first = statistics.mean(fork[0] for fork in metric["rawData"])
        last = statistics.mean(fork[-1] for fork in metric["rawData"])
        groups[group]["runtimes"][row["params"]["runtime"]] = dict(
            throughput=metric["score"], error=metric["scoreError"],
            bytes_per_op=row["secondaryMetrics"]["gc.alloc.rate.norm"]["score"],
            cpu_ms_per_op=row["secondaryMetrics"]["client.cpu"]["score"],
            fork_means=forks, fork_cv=statistics.stdev(forks) / statistics.mean(forks),
            last_vs_first_iteration=last / first)
    result = list(groups.values())
    for group in result:
        metrics = group["runtimes"]
        baseline = metrics["ce"]
        ranked = sorted(metrics, key=lambda runtime: metrics[runtime]["throughput"], reverse=True)
        best = metrics[ranked[0]]
        group["fastest_mean"] = ranked[0]
        group["fastest_interval_separated"] = all(
            best["throughput"] - best["error"] > metrics[r]["throughput"] + metrics[r]["error"] for r in ranked[1:])
        for runtime, value in metrics.items():
            value["throughput_relative_to_ce"] = value["throughput"] / baseline["throughput"]
            value["ci_overlaps_ce"] = abs(value["throughput"] - baseline["throughput"]) <= value["error"] + baseline["error"]
            value["allocation_relative_to_ce"] = value["bytes_per_op"] / baseline["bytes_per_op"]
            value["cpu_relative_to_ce"] = value["cpu_ms_per_op"] / baseline["cpu_ms_per_op"]
        if "SequentialBaseline" in group["benchmark"]:
            group["fastest_mean"] = None
            group["fastest_interval_separated"] = None
            for value in metrics.values():
                for name in ("throughput_relative_to_ce", "ci_overlaps_ce", "allocation_relative_to_ce", "cpu_relative_to_ce"):
                    value[name] = None
    for row in rows:
        if row["mode"] == "sample":
            params = tuple(sorted((k, v) for k, v in row["params"].items() if k != "runtime"))
            value = groups[(row["benchmark"], params)]["runtimes"][row["params"]["runtime"]]
            p = row["primaryMetric"]["scorePercentiles"]
            samples = sum(count for fork in row["primaryMetric"]["rawDataHistogram"]
                          for iteration in fork for _, count in iteration)
            value.update(batch_p50_ms=1000 * p["50.0"], batch_p99_ms=1000 * p["99.0"], latency_samples=samples)
    return result


def figures(groups, output):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt

    plt.rcParams.update({"font.family": "DejaVu Sans", "font.size": 10,
                         "axes.spines.top": False, "axes.spines.right": False,
                         "axes.spines.left": False, "axes.titleweight": "bold"})
    colors = {"ce": "#2864a0", "kyo": "#cb6235", "loom": "#287d70", "ox": "#8156a5", "gears": "#a98025"}
    names = {"ce": "Cats Effect", "kyo": "Kyo", "loom": "Plain Loom", "ox": "Ox", "gears": "Gears"}

    def select(suffix, **params):
        return next(g for g in groups if g["benchmark"].endswith(suffix) and all(g["params"].get(k) == str(v) for k, v in params.items()))

    def plot(cases, filename, title, subtitle):
        fig, axes = plt.subplots(2, 2, figsize=(12, 7.2), constrained_layout=True)
        for ax, (group, heading) in zip(axes.flat, cases):
            order = list(RUNTIMES)[::-1]
            scores = [group["runtimes"][r]["throughput"] for r in order]
            errors = [group["runtimes"][r]["error"] for r in order]
            clipped = [[min(s, e) for s, e in zip(scores, errors)], errors]
            ax.barh([names[r] for r in order], scores, color=[colors[r] for r in order],
                    xerr=clipped, error_kw=dict(ecolor="#333333", capsize=3, lw=1))
            ax.set_title(heading, loc="left", fontsize=11, pad=10)
            ax.set_xlabel("Completed batches / second" if "IoBench" in group["benchmark"] else "Complete operations / second")
            ax.set_xlim(left=0)
            ax.tick_params(axis="y", length=0)
            ax.grid(axis="x", alpha=0.18)
            ax.set_axisbelow(True)
            ax.ticklabel_format(axis="x", style="plain", useOffset=False)
        fig.suptitle(title + "\n" + subtitle, fontsize=14, ha="left", x=0.01)
        fig.savefig(output / (filename + ".png"), dpi=180, facecolor="white")
        fig.savefig(output / (filename + ".svg"), facecolor="white")
        plt.close(fig)

    plot([
        (select("ParallelBench.workers", work=0), "4,096 items, 8 workers: increment"),
        (select("ParallelBench.workers", work=64), "4,096 items, 8 workers: 64 arithmetic rounds"),
        (select("PrimitivesBench.spawnJoin"), "1,000 sequential child spawn/join pairs"),
        (select("PrimitivesBench.queue"), "1,000 queue entries, capacity 64"),
    ], "concurrency-throughput", "Task shape changes the comparison", "Three JVM forks; error bars show JMH 99.9% confidence intervals")
    plot([
        (select("PipelineBench.sequentialChunks", work=0), "Sequential chunks: increment"),
        (select("PipelineBench.sequentialChunks", work=64), "Sequential chunks: 64 arithmetic rounds"),
        (select("PipelineBench.queueChunks", work=0), "Queue-backed chunks: increment"),
        (select("PipelineBench.queueChunks", work=64), "Queue-backed chunks: 64 arithmetic rounds"),
    ], "pipeline-throughput", "More work per value narrows the pipeline gap", "10,000 values in chunks of 64; different APIs; JMH 99.9% intervals")
    plot([
        (select("IoBench.requests", transport="blocking", parallelism=8), "Blocking sockets: 8 connections"),
        (select("IoBench.requests", transport="blocking", parallelism=64), "Blocking sockets: 64 connections"),
        (select("IoBench.requests", transport="nonblocking", parallelism=8), "Callback sockets: 8 connections"),
        (select("IoBench.requests", transport="nonblocking", parallelism=64), "Callback sockets: 64 connections"),
    ], "tcp-throughput", "TCP: the transport and concurrency limit matter", "256 exchanges per batch; requested 1 ms server delay; JMH 99.9% intervals")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--plots", action="store_true")
    args = parser.parse_args()
    rows, meta = load_verified(args.directory)
    if meta["profile"] != "measured":
        parser.error("Blog analysis requires the measured profile")
    groups = analyze(rows)
    (args.directory / "analysis.json").write_text(json.dumps(groups, indent=2) + "\n")
    if args.plots:
        figures(groups, args.directory)
    for group in groups:
        if "SequentialBaseline" in group["benchmark"]:
            print(f"{group['benchmark']} {group['params']}: separate programming-style baseline; no ratios")
            continue
        ratios = " ".join(f"{r}={v['throughput_relative_to_ce']:.2f}x{'~' if v['ci_overlaps_ce'] else ''}" for r, v in group["runtimes"].items())
        print(f"{group['benchmark']} {group['params']}: {ratios}")


if __name__ == "__main__":
    main()

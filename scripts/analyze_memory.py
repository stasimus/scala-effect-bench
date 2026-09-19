#!/usr/bin/env python3
"""Create figures and derived data from verified CE/Loom memory measurements."""
import argparse
import json
from pathlib import Path
import statistics

from report_memory import load_verified, metrics


def summarize(rows):
    result = []
    keys = sorted({(r["mode"], r["count"], r["setting"], r["runtime"]) for r in rows})
    for mode, count, setting, runtime in keys:
        selected = [r for r in rows if (r["mode"], r["count"], r["setting"], r["runtime"]) == (mode,count,setting,runtime)]
        per_fork = [metrics(r) for r in sorted(selected,key=lambda r:r["fork"])]
        result.append(dict(mode=mode,count=count,setting=setting,runtime=runtime,forks=per_fork,
                           metrics={k:dict(median=statistics.median(v[k] for v in per_fork),
                                           minimum=min(v[k] for v in per_fork), maximum=max(v[k] for v in per_fork))
                                    for k in per_fork[0]}))
    return result


def figures(groups, output):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import numpy as np
    plt.rcParams.update({"font.family":"DejaVu Sans", "font.size":10,
                         "axes.spines.top":False,"axes.spines.right":False,"axes.titleweight":"bold"})
    colors = {"ce":"#2864a0","loom":"#287d70"}
    labels = {"ce":"Cats Effect","loom":"Plain Loom"}
    mib = 1024**2

    def select(mode,count,setting,runtime,metric):
        return next(g for g in groups if (g["mode"],g["count"],g["setting"],g["runtime"]) == (mode,count,setting,runtime))["metrics"][metric]

    def save(fig,name):
        fig.savefig(output / f"{name}.png",dpi=180,facecolor="white")
        fig.savefig(output / f"{name}.svg",facecolor="white")
        plt.close(fig)

    fig,axes=plt.subplots(2,2,figsize=(11,7),constrained_layout=True)
    counts=[1000,10000,100000]
    for col,payload in enumerate(("0","1024")):
        for row,(metric,label) in enumerate((("live_heap","Live heap after GC"),("footprint","Process physical footprint after GC"))):
            ax=axes[row,col]
            for runtime in colors:
                values=[select("parked",n,payload,runtime,metric) for n in counts]
                y=[v["median"]/mib for v in values]
                err=[[(v["median"]-v["minimum"])/mib for v in values],[(v["maximum"]-v["median"])/mib for v in values]]
                ax.errorbar(counts,y,yerr=err,marker="o",capsize=4,color=colors[runtime],label=labels[runtime])
            ax.set_title(f"{label}\n{int(payload):,} payload bytes per waiting task",loc="left",fontsize=11)
            ax.set_xscale("log")
            ax.set_xticks(counts,["1,000","10,000","100,000"])
            ax.set_xlabel("Waiting tasks")
            ax.set_ylabel("MiB")
            ax.set_ylim(bottom=0)
            ax.grid(alpha=.18)
            ax.legend(frameon=False)
    fig.suptitle("Live heap and whole-process memory can disagree\nThree fresh JVMs; points are medians, bars show the full range",ha="left",x=.01,fontsize=14)
    save(fig,"waiting-memory")

    fig,axes=plt.subplots(1,2,figsize=(11,4.5),constrained_layout=True)
    configs=[(8,"blocking"),(8,"nonblocking"),(64,"blocking"),(64,"nonblocking")]
    x=np.arange(len(configs))
    for ax,(metric,title) in zip(axes,(("footprint","During continuous TCP work"),("peak_footprint","Peak through the final snapshot"))):
        for i,runtime in enumerate(colors):
            values=[select("tcp",n,t,runtime,metric) for n,t in configs]
            y=[v["median"]/mib for v in values]
            err=[[(v["median"]-v["minimum"])/mib for v in values],[(v["maximum"]-v["median"])/mib for v in values]]
            ax.bar(x+(i-.5)*.35,y,width=.35,yerr=err,capsize=3,color=colors[runtime],label=labels[runtime])
        ax.set_xticks(x,["8\nblocking","8\ncallbacks","64\nblocking","64\ncallbacks"])
        ax.set_ylabel("Client physical footprint (MiB)")
        ax.set_title(title,loc="left",fontsize=11)
        ax.set_xlabel("Connections and transport")
        ax.grid(axis="y",alpha=.18)
        ax.set_axisbelow(True)
        ax.legend(frameon=False)
    fig.suptitle("TCP client memory: Cats Effect versus plain Loom\nThree fresh JVMs; medians and full ranges; server excluded",ha="left",x=.01,fontsize=14)
    save(fig,"tcp-memory")


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory",type=Path)
    parser.add_argument("--plots",action="store_true")
    args=parser.parse_args()
    rows,meta=load_verified(args.directory)
    if meta["profile"] != "measured":
        parser.error("Analysis requires the measured profile")
    groups=summarize(rows)
    (args.directory / "analysis.json").write_text(json.dumps(groups,indent=2)+"\n")
    if args.plots:
        figures(groups,args.directory)
    for g in groups:
        m=g["metrics"]
        heap=m["live_heap" if g["mode"]=="parked" else "post_heap"]["median"]/1048576
        print(f"{g['mode']} {g['count']} {g['setting']} {g['runtime']}: heap={heap:.2f} MiB footprint={m['footprint']['median']/1048576:.2f} MiB peak={m['peak_footprint']['median']/1048576:.2f} MiB")

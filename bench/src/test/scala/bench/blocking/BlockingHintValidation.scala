package bench.blocking

object BlockingHintValidation:
    def main(args: Array[String]): Unit =
        var checks = 0
        for runtime <- Vector("ce", "kyoFlush"); limit <- Vector(1, 8, 64); delay <- Vector(0, 1000) do
            for size <- Vector(0, 1, 17, 256) do
                val bench = new BlockingHintBench
                bench.runtime = runtime
                bench.parallelism = limit
                bench.delayMicros = delay
                bench.size = size
                bench.setup()
                try
                    assert(bench.requests() == Vector.tabulate(size)(_ + 1), s"$runtime/$limit/$delay/$size")
                    checks += 1
                finally bench.teardown()
        println(s"PASS $checks blocking hint checks")

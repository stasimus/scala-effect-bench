package bench.memory

import java.util.concurrent.{Executors, TimeUnit}

object Validation:
    def main(args: Array[String]): Unit =
        var checks = 0
        for runtime <- Vector("ce", "loom") do
            val executor = if runtime == "loom" then Executors.newVirtualThreadPerTaskExecutor() else null
            try
                for count <- Vector(0, 1, 8, 1000); bytes <- Vector(0, 1024) do
                    val tasks = new WaitingTasks(runtime, count, bytes, executor)
                    tasks.awaitReady()
                    require(tasks.completed == 0)
                    Thread.sleep(25)
                    require(tasks.completed == 0, "Waiting work completed early")
                    tasks.releaseAndCheck()
                    require(tasks.completed == count)
                    checks += 1
            finally if executor != null then
                executor.shutdownNow()
                require(executor.awaitTermination(10, TimeUnit.SECONDS))
        println(s"PASS $checks memory workload checks")

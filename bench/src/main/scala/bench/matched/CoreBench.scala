package bench.matched

import cats.effect.IO
import kyo.*
import org.openjdk.jmh.annotations.*

class CoreBench extends MatchedBase:
    @Param(Array("1000", "10000")) var depth: Int = 0

    @Benchmark def ceDeepBind(): Int = ce {
        def loop(i: Int): IO[Int] =
            if i == depth then IO.pure(i) else IO(i + 1).flatMap(loop)
        loop(0)
    }
    @Benchmark def kyoDeepBind(): Int = ky {
        def loop(i: Int): Int < Sync =
            if i == depth then i else Sync.defer(i + 1).map(loop)
        loop(0)
    }

    @Benchmark def ceLeftBind(): Int = ce {
        var acc = IO(0)
        var i = 0
        while i < depth do
            acc = acc.flatMap(v => IO(v + 1))
            i += 1
        acc
    }
    @Benchmark def kyoLeftBind(): Int = ky {
        var acc: Int < Sync = Sync.defer(0)
        var i = 0
        while i < depth do
            acc = acc.map(v => Sync.defer(v + 1))
            i += 1
        acc
    }

    // Both chains begin with a suspended value, so Kyo cannot eagerly evaluate pure inputs.
    @Benchmark def ceMapChain(): Int = ce {
        var acc = IO(0)
        var i = 0
        while i < depth do
            acc = acc.map(_ + 1)
            i += 1
        acc
    }
    @Benchmark def kyoMapChain(): Int = ky {
        var acc: Int < Sync = Sync.defer(0)
        var i = 0
        while i < depth do
            acc = acc.map(_ + 1)
            i += 1
        acc
    }

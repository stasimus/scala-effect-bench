package bench

import cats.effect.IO
import cats.syntax.all.*
import java.util.concurrent.atomic.AtomicInteger

object PoolParTraverse:

    /** parTraverseN with Kyo's strategy: `n` worker fibers pulling elements by index instead of a fiber and a semaphore permit per
      * element. Same result order, same failure semantics (first error cancels everything in flight). Differs observably from the real
      * one in fiber granularity: elements share worker fibers, so IOLocal mutations leak between elements handled by the same worker.
      */
    def parTraverseNPool[A, B](n: Int)(items: Vector[A])(f: A => IO[B]): IO[Vector[B]] =
        if items.isEmpty then IO.pure(Vector.empty)
        else
            IO(new AtomicInteger(0)).flatMap { idx =>
                IO(new Array[AnyRef](items.length)).flatMap { out =>
                    def worker: IO[Unit] =
                        IO(idx.getAndIncrement()).flatMap { i =>
                            if i >= items.length then IO.unit
                            else f(items(i)).flatMap(b => IO(out(i) = b.asInstanceOf[AnyRef])) >> worker
                        }
                    List.fill(math.min(n, items.length))(worker).parSequence_ *>
                        IO(Vector.tabulate(items.length)(i => out(i).asInstanceOf[B]))
                }
            }

end PoolParTraverse

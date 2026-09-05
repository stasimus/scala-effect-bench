package cats.effect.kernel

import cats.effect.IO

object MiniSemaphoreAccess:
    def apply(n: Int): IO[MiniSemaphore[IO]] = MiniSemaphore[IO](n)

    def withPermit[A](sem: MiniSemaphore[IO])(fa: IO[A]): IO[A] = sem.withPermit(fa)
end MiniSemaphoreAccess

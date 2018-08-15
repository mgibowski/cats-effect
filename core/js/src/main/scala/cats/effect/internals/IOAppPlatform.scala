/*
 * Copyright (c) 2017-2018 The Typelevel Cats-effect Project Developers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package cats
package effect
package internals

import cats.implicits._
import scala.concurrent.duration._
import scala.scalajs.js

private[effect] object IOAppPlatform {
  def main(args: Array[String], timer: Eval[Timer[IO]])(run: List[String] => IO[ExitCode]): Unit = {
    val io = mainFiber(args, timer)(run).flatMap { fiber =>
      installHandler(fiber) *> fiber.join
    }
    io.unsafeRunAsync {
      case Left(t) =>
        Logger.reportFailure(t)
        sys.exit(ExitCode.Error.code)
      case Right(0) =>
        ()
      case Right(code) =>
        sys.exit(code)
    }
  }

  def mainFiber(args: Array[String], timer: Eval[Timer[IO]])(run: List[String] => IO[ExitCode]): IO[Fiber[IO, Int]] = {
    // An infinite heartbeat to keep main alive.  This is similar to
    // `IO.never`, except `IO.never` doesn't schedule any tasks and is
    // insufficient to keep main alive.  The tick is fast enough that
    // it isn't silently discarded, as longer ticks are, but slow
    // enough that we don't interrupt often.  1 hour was chosen
    // empirically.
    def keepAlive: IO[Nothing] = timer.value.sleep(1.hour) >> keepAlive

    val program = run(args.toList).handleErrorWith {
      t => IO(Logger.reportFailure(t)) *> IO.pure(ExitCode.Error)
    }

    IO.race(keepAlive, program).flatMap {
      case Left(_) =>
        // This case is unreachable, but scalac won't let us omit it.
        IO.raiseError(new AssertionError("IOApp keep alive failed unexpectedly."))
      case Right(exitCode) =>
        IO.pure(exitCode.code)
    }.start(timer.value)
  }

  val defaultTimer: Timer[IO] = IOTimer.global

  private def installHandler(fiber: Fiber[IO, Int]): IO[Unit] = {
    def handler(code: Int) = () =>
      fiber.cancel.unsafeRunAsync { result =>
        result.swap.foreach(Logger.reportFailure)
        IO(sys.exit(code + 128))
      }

    IO {
      if (!js.isUndefined(js.Dynamic.global.process)) {
        val process = js.Dynamic.global.process
        process.on("SIGHUP", handler(1))
        process.on("SIGINT", handler(2))
        process.on("SIGTERM", handler(15))
      }
    }
  }
}

package com.twitter.algebird

import org.scalacheck.{ Gen, Prop }
import org.scalacheck.util.Pretty

trait ApproximateProperty {
  type Exact
  type Approx
  type Input
  type Result

  def exactGenerator: Gen[Exact]
  def inputGenerator(e: Exact): Gen[Input]

  def makeApproximate(e: Exact): Approx

  def exactResult(e: Exact, i: Input): Result
  def approximateResult(a: Approx, i: Input): Approximate[Result]
}

object ApproximateProperty {

  /**
   *  Generates a list of exactly n Ts.
   *  Useful because `Gen.listOfN(n, gen).sample` gives us Option[List[T]],
   *  while we often want List[T].
   */
  private def genListOf[T](n: Int, gen: Gen[T]): List[T] = {
    Gen.listOfN(n, gen).sample match {
      case Some(xs) => xs
      case _ => genListOf(n, gen)
    }
  }

  private def successesAndProbabilities(a: ApproximateProperty, objectReps: Int, inputReps: Int): (Int, Double, List[String]) = {
    val list = genListOf(objectReps, a.exactGenerator)
      .flatMap { exact =>
        val approx = a.makeApproximate(exact)
        genListOf(inputReps, a.inputGenerator(exact)).map { input =>
          val approxResult = a.approximateResult(approx, input)
          val exactResult = a.exactResult(exact, input)

          val success = approxResult.boundsContain(exactResult)
          val successInt = if (success) 1 else 0
          // val messages = if (success) List() else List(s"Exact: $exact. Approx: $approx. Exact result: $exactResult. Approx result: $approxResult.")
          val messages = if (success) List() else List(s"Exact result: $exactResult. Approx result: $approxResult.")
          (successInt, approxResult.probWithinBounds, messages)
        }
      }
    val monoid = implicitly[Monoid[(Int, Double, List[String])]]
    monoid.sum(list)
  }

  def toProp(a: ApproximateProperty, objectReps: Int, inputReps: Int, falsePositiveRate: Double): Prop =
    new Prop {
      def apply(params: Gen.Parameters) = {
        require(0 <= falsePositiveRate && falsePositiveRate <= 1)

        val (successes, sumOfProbabilities, exacts) = successesAndProbabilities(a, objectReps, inputReps)
        val n = objectReps * inputReps

        // Computed from Hoeffding's inequality, might be inaccurate
        // TODO Make sure this is correct
        val diff = scala.math.sqrt(n * scala.math.log(falsePositiveRate) / -2)

        val success = if (successes >= (sumOfProbabilities - diff)) Prop.Proof else Prop.False

        val argsList = List(("Successes", successes),
          ("Expected successes", "%.2f".format(sumOfProbabilities)),
          ("Required successes", "%.2f".format(sumOfProbabilities - diff))) ++
          { if (success == Prop.False) List(("Example failures:\n  >", exacts.take(5).mkString("\n  >"))) else Nil }

        val args = argsList.map {
          case (name, value) =>
            Prop.Arg(name, value, 0, value, Pretty.prettyAny(value), Pretty.prettyAny(value))
        }

        Prop.Result(success, args = args)
      }
    }

  def toProp(a: Iterable[ApproximateProperty], objectReps: Int, inputReps: Int, falsePositiveRate: Double): Prop = {
    require(0 <= falsePositiveRate && falsePositiveRate <= 1)

    val monoid = implicitly[Monoid[(Int, Double, List[String])]]
    val (successes, sumOfProbabilities, _) = monoid.sum(a.map { approximateProp =>
      successesAndProbabilities(approximateProp, objectReps, inputReps)
    })
    val n = objectReps * inputReps

    println("Foo")
    println(sumOfProbabilities - successes)

    (sumOfProbabilities - successes) > scala.math.sqrt(n * scala.math.log(falsePositiveRate) / -2)
  }
}
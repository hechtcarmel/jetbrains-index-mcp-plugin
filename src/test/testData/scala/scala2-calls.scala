package fixture.scala2.calls

object Target {
  def ping(): Int = 1
}

object CallerA {
  def thrice(): Int = Target.ping() + Target.ping() + Target.ping()
}

object CallerB {
  def once(): Int = Target.ping()
}

object CallerC {
  def alsoOnce(): Int = Target.ping()
}

class Counter {
  def current: Int = 1
  def plus(other: Int): Int = current + other
  def combine(that: Counter): Int = this plus that.current
  def total(): Int = combine(this) + current
}

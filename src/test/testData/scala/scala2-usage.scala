package fixture.scala2

object ServiceRunner {
  val defaultTask: String = "job"
  var runCount: Int = 0

  def runAll(worker: Worker): String = {
    runCount = runCount + 1
    worker.work(defaultTask)
  }

  def runEmployee(): String = {
    val emp = Employee("1", "Ada")
    runAll(emp)
  }
}

class ReportGenerator {
  def generate(emp: Employee): String = emp.doWork()
}

object BatchProcessor {
  def process(emp: Employee): String = new ReportGenerator().generate(emp)
}

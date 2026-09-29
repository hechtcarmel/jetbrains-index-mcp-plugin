package fixture.scala2

trait Identifiable {
  def id: String
}

trait Named extends Identifiable {
  def name: String
}

trait Worker extends Named {
  def work(task: String): String = s"$name:$task"
}

abstract class BaseService extends Worker {
  override def work(task: String): String = s"base-$task"
}

case class Employee(id: String, name: String) extends BaseService {
  override def work(task: String): String = s"$name handled $task"
  def doWork(): String = work("task")
}

class Contractor(val id: String, val name: String) extends BaseService {
  override def work(task: String): String = s"$name contracted $task"
}
